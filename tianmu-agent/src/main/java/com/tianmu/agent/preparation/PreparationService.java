package com.tianmu.agent.preparation;

import com.tianmu.agent.core.StopReason;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Bounded fan-out/fan-in. Only the coordinator prepares or delivers; policy denial uses the terminal arbiter. */
@Service
@EnableConfigurationProperties(PreparationPolicy.class)
public class PreparationService implements AutoCloseable {
    private final FaqPort faq;
    private final PreparationModelPort model;
    private final PreparationPolicy policy;
    private final RuntimeHarness harness;
    private final List<QueryReadinessPolicy> validators;
    private final TaskMeaning meaning;
    private final com.tianmu.agent.runtime.store.RunRepository repository;
    private final ThreadPoolExecutor calls = new ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(48), r -> { Thread t = new Thread(r, "agent-preparation"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private record Completed<T>(T value, Instant at) {}
    private record Branch<T>(FutureTask<Completed<T>> future, CancellationToken token, CancellationToken.Registration registration) {}
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness) {
        this(faq, model, policy, harness, List.of());
    }
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
                              List<QueryReadinessPolicy> validators) {
        this(faq, model, policy, harness, validators, TaskMeaning.defaults());
    }
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
                              List<QueryReadinessPolicy> validators, TaskMeaning meaning) {
        this(faq, model, policy, harness, validators, meaning, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PreparationService(FaqPort faq, PreparationModelPort model, PreparationPolicy policy, RuntimeHarness harness,
                              List<QueryReadinessPolicy> validators, TaskMeaning meaning, com.tianmu.agent.runtime.store.RunRepository repository) {
        this.faq = faq; this.model = model; this.policy = policy; this.harness = harness; this.validators = List.copyOf(validators); this.meaning = meaning; this.repository = repository;
    }
    public Result prepare(RunState run, String query) {
        Input input = new Input(query, run.businessDate, run.owner, run.memory());
        Set<String> degraded = new LinkedHashSet<>(), skipped = new LinkedHashSet<>();
        Instant faqDeadline = deadline(run, policy.faqTimeout());
        Branch<Optional<FaqAnswer>> lookup = submit(run, "faq", faqDeadline, input, c -> faq.find(input, c));
        Optional<FaqAnswer> hit;
        try { hit = await(run, lookup, faqDeadline); }
        catch (PreparationUnavailable e) { degraded.add("faq"); hit = Optional.empty(); }
        finally { dispose(lookup); }
        run.checkpoint();
        if (hit.isPresent()) return new Result(Route.FAQ,
                new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, Set.of("intent", "understanding", "emotion")), hit.get(), null);

        if (repository != null) run.memory(harness.stage(run, "agent.memory.load", null, null, Map.of(), () -> repository.memory(run)));
        Input contextInput = new Input(query, run.businessDate, run.owner, run.memory());
        run.emit("PROGRESS", Map.of("stage", "preparing"));
        Instant end = deadline(run, policy.understandingTimeout());
        Instant intentEnd = deadline(run, policy.intentTimeout());
        Instant emotionEnd = deadline(run, policy.emotionTimeout());
        // All branches read the same immutable snapshot. Only this coordinator merges results.
        Branch<Understanding> understanding = submit(run, "understanding", end, contextInput, c -> model.understand(contextInput, c));
        Branch<Intent> intent = submit(run, "intent", intentEnd, contextInput, c -> model.intent(contextInput, c));
        Branch<Emotion> emotion = submit(run, "emotion", emotionEnd, contextInput, c -> model.emotion(contextInput, c));
        try {
            Intent recognized;
            try { recognized = await(run, intent, intentEnd); }
            catch (PreparationUnavailable e) { degraded.add("intent"); recognized = Intent.UNKNOWN; }
            Understanding result;
            try { result = await(run, understanding, end); }
            catch (PreparationUnavailable e) {
                degraded.add("understanding");
                result = Understanding.clarify("暂时无法确认查询条件，请明确要分析的视频、指标和日期后重试。");
            }
            Optional<Clarification> required = harness.stage(run, "agent.prepare.validate", null, null, Map.of(),
                    () -> validators.stream().map(v -> v.validate(contextInput)).flatMap(Optional::stream).filter(Clarification::required).findFirst());
            if (required.isPresent()) result = Understanding.clarify(required.get().question());
            else if (recognized == Intent.UNKNOWN) {
                result = Understanding.clarify("暂时无法确认你的任务意图，请说明要查询的数据或需要帮助的具体问题。");
            } else if (!result.needsClarification() && !validIntent(contextInput, recognized)) {
                degraded.add("intent");
                result = Understanding.clarify("任务意图与查询条件存在冲突，请明确要查询的视频、日期和指标。");
            } else if (!result.needsClarification() && !validConditions(contextInput, result.conditions())) {
                degraded.add("understanding");
                result = Understanding.clarify("查询条件存在冲突，请明确要查询的视频、日期和指标。");
            }
            if (result.needsClarification()) {
                skipped.add("rewrite");
                Emotion feeling = readyEmotion(run, emotion, emotionEnd, degraded, skipped);
                return new Result(Route.CLARIFY, new PreparedRequest(run.message, query, feeling, degraded, skipped, recognized), null, result.clarificationQuestion());
            }
            String rewritten = result.rewrittenQuery();
            if (!safeRewrite(query, rewritten)
                    || !meaning.resolve(query, run.businessDate).equals(meaning.resolve(rewritten, run.businessDate))) {
                degraded.add("rewrite"); rewritten = query;
            }
            Emotion feeling = readyEmotion(run, emotion, emotionEnd, degraded, skipped);
            run.checkpoint();
            return new Result(Route.HARNESS, new PreparedRequest(run.message, rewritten, feeling, degraded, skipped, recognized), null, null);
        } finally { dispose(understanding); dispose(intent); dispose(emotion); }
    }
    private boolean validIntent(Input input, Intent intent) {
        return switch (meaning.resolve(input.query(), input.businessDate()).type()) {
            case "danmaku.report" -> intent == Intent.DANMAKU_REPORT;
            case "video.metric" -> intent == Intent.VIDEO_METRIC;
            default -> intent != Intent.VIDEO_METRIC && intent != Intent.DANMAKU_REPORT;
        };
    }
    /** Model candidates never grant scope or supply missing server-side task conditions. */
    private boolean validConditions(Input input, Map<String, Object> proposed) {
        Map<String, Object> expected = meaning.resolve(input.query(), input.businessDate()).conditions();
        if (!expected.keySet().equals(proposed.keySet())) return false;
        return expected.entrySet().stream().allMatch(e -> {
            Object actual = proposed.get(e.getKey());
            if (Set.of("start", "end").contains(e.getKey()) && e.getValue() instanceof String expectedTime && actual instanceof String candidateTime) {
                try { return LocalDateTime.parse(expectedTime).equals(LocalDateTime.parse(candidateTime)); }
                catch (DateTimeException ignored) { return false; }
            }
            if (e.getValue() instanceof Number number && actual instanceof Number candidate)
                return new java.math.BigDecimal(number.toString()).compareTo(new java.math.BigDecimal(candidate.toString())) == 0;
            return Objects.equals(e.getValue(), actual);
        });
    }
    private Emotion readyEmotion(RunState run, Branch<Emotion> branch, Instant end, Set<String> degraded, Set<String> skipped) {
        if (!branch.future().isDone()) { skipped.add("emotion"); return Emotion.UNKNOWN; }
        try { return await(run, branch, end); }
        catch (PreparationUnavailable e) { degraded.add("emotion"); return Emotion.UNKNOWN; }
    }
    private Instant deadline(RunState run, Duration timeout) {
        Instant local = Instant.now().plus(timeout);
        return local.isBefore(run.deadline()) ? local : run.deadline();
    }
    private <T> Branch<T> submit(RunState run, String name, Instant deadline, Input input, Function<CallControl, T> operation) {
        CancellationToken token = new CancellationToken();
        CallControl control = new CallControl(deadline, token);
        long submittedAt = System.nanoTime();
        long timeoutMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        FutureTask<Completed<T>> task = new FutureTask<>(() -> {
            // Trace receives the actual business result, not the internal deadline wrapper.
            java.util.concurrent.atomic.AtomicReference<Instant> completedAt = new java.util.concurrent.atomic.AtomicReference<>();
            T value = harness.stage(run, "agent.prepare." + name, null, null,
                    Map.of("execution.queue_ms", Math.max(0, (System.nanoTime() - submittedAt) / 1_000_000), "execution.timeout_ms", timeoutMs), () -> {
                com.tianmu.agent.harness.trace.TraceDetails.input(input);
                control.checkpoint();
                if (!Instant.now().isBefore(deadline)) throw new PreparationUnavailable();
                try {
                    if (!name.equals("faq")) run.preparationCallStarted();
                    T result = operation.apply(control);
                    completedAt.set(Instant.now());
                    return result;
                } catch (AgentException e) {
                    if (!Set.of("CANCELLED", "DEADLINE_EXCEEDED").contains(e.code())) run.fail(e.code());
                    throw e;
                }
            });
            return new Completed<>(value, completedAt.get());
        });
        CancellationToken.Registration registration = run.cancellation().onCancel(() -> {
            task.cancel(true); token.cancel(run.cancellation().reason());
        });
        try { calls.execute(harness.contextual(task)); }
        catch (RejectedExecutionException e) { task.cancel(false); token.cancel(StopReason.ADAPTER_TIMEOUT); }
        return new Branch<>(task, token, registration);
    }
    private <T> T await(RunState run, Branch<T> branch, Instant end) {
        run.checkpoint();
        try {
            // A completed sibling is still usable even if another branch consumed the shared deadline.
            Completed<T> result = branch.future().isDone() ? branch.future().get()
                    : branch.future().get(Math.max(1, Duration.between(Instant.now(), end).toNanos()), TimeUnit.NANOSECONDS);
            run.checkpoint();
            if (result.value() == null || result.at().isAfter(end)) throw new PreparationUnavailable();
            return result.value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); run.cancel(StopReason.SHUTDOWN); run.checkpoint();
            throw new PreparationUnavailable();
        } catch (ExecutionException e) {
            run.checkpoint();
            // Authorization and policy failures must never be silently degraded.
            if (e.getCause() instanceof AgentException a) throw a;
            throw new PreparationUnavailable();
        } catch (TimeoutException | CancellationException e) {
            run.checkpoint(); throw new PreparationUnavailable();
        }
    }
    private void dispose(Branch<?> branch) {
        branch.registration().close();
        if (!branch.future().isDone()) { branch.future().cancel(true); branch.token().cancel(StopReason.ADAPTER_TIMEOUT); }
    }
    /** Cheap guard only; semantic preservation still requires adapter evaluation. Never changes authorization. */
    static boolean safeRewrite(String original, String rewrite) {
        if (rewrite == null || rewrite.isBlank() || rewrite.length() > 2000) return false;
        if (!numbers(original).equals(numbers(rewrite))) return false;
        for (String anchor : List.of("昨天", "今天", "前天", "上周", "本周", "上月", "本月", "弹幕", "播放", "点赞", "评论"))
            if (original.contains(anchor) != rewrite.contains(anchor)) return false;
        return true;
    }
    private static List<String> numbers(String text) {
        return Pattern.compile("\\d+").matcher(text).results().map(m -> m.group()).toList();
    }
    private static class PreparationUnavailable extends RuntimeException {}
    @Override @PreDestroy public void close() { calls.shutdownNow(); }
}
