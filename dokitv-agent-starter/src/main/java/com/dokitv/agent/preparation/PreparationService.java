package com.dokitv.agent.preparation;

import com.dokitv.agent.core.StopReason;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.harness.RuntimeHarness;
import com.dokitv.agent.core.PreparationTypes.*;
import com.dokitv.agent.runtime.RunState;
import com.dokitv.agent.runtime.control.*;
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
    private final com.dokitv.agent.runtime.store.RunRepository repository;
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
                              List<QueryReadinessPolicy> validators, TaskMeaning meaning, com.dokitv.agent.runtime.store.RunRepository repository) {
        this.faq = faq; this.model = model; this.policy = policy; this.harness = harness; this.validators = List.copyOf(validators); this.meaning = meaning; this.repository = repository;
    }
    public Result prepare(RunState run, String query) {
        Input input = new Input(query, run.businessDate, run.owner, run.memory());
        Set<String> degraded = new LinkedHashSet<>(), skipped = new LinkedHashSet<>();
        Instant faqDeadline = deadline(run, policy.faqTimeout());
        Branch<Optional<FaqAnswer>> lookup = submit(run, "faq", faqDeadline, c -> faq.find(input, c));
        Optional<FaqAnswer> hit;
        try { hit = await(run, lookup, faqDeadline); }
        catch (PreparationUnavailable e) { degraded.add("faq"); hit = Optional.empty(); }
        finally { dispose(lookup); }
        run.checkpoint();
        if (hit.isPresent()) return new Result(Route.FAQ,
                new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, Set.of("clarification", "rewrite", "emotion")), hit.get(), null);

        if (repository != null) run.memory(repository.memory(run));
        Input contextInput = new Input(query, run.businessDate, run.owner, run.memory());
        Optional<Clarification> required = harness.stage(run, "agent.prepare.validate", null, null, Map.of(),
                () -> validators.stream().map(v -> v.validate(contextInput)).flatMap(Optional::stream).filter(Clarification::required).findFirst());
        if (required.isPresent()) return new Result(Route.CLARIFY,
                new PreparedRequest(run.message, query, Emotion.UNKNOWN, degraded, Set.of("clarification", "rewrite", "emotion")), null, required.get().question());

        run.emit("PROGRESS", Map.of("stage", "preparing"));
        Instant end = deadline(run, policy.parallelTimeout());
        Instant rewriteEnd = earlier(end, deadline(run, policy.rewriteTimeout()));
        Instant emotionEnd = earlier(end, deadline(run, policy.emotionTimeout()));
        // Submit the critical branch first; all branches still start independently.
        Branch<Clarification> clarification = submit(run, "clarification", end, c -> model.clarify(contextInput, c));
        Branch<String> rewrite = submit(run, "rewrite", rewriteEnd, c -> model.rewrite(contextInput, c));
        Branch<Emotion> emotion = submit(run, "emotion", emotionEnd, c -> model.emotion(contextInput, c));
        try {
            Clarification question;
            try { question = await(run, clarification, end); }
            catch (PreparationUnavailable e) {
                degraded.add("clarification");
                question = new Clarification(true, "暂时无法确认查询条件，请明确要分析的视频、指标和日期后重试。");
            }
            if (question.required()) {
                skipped.add("rewrite");
                Emotion feeling = readyEmotion(run, emotion, emotionEnd, degraded, skipped);
                return new Result(Route.CLARIFY, new PreparedRequest(run.message, query, feeling, degraded, skipped), null, question.question());
            }
            String rewritten;
            try {
                rewritten = await(run, rewrite, rewriteEnd);
                if (!safeRewrite(query, rewritten)
                        || !meaning.resolve(query, run.businessDate).equals(meaning.resolve(rewritten, run.businessDate)))
                    throw new PreparationUnavailable();
            } catch (PreparationUnavailable e) { degraded.add("rewrite"); rewritten = query; }
            Emotion feeling = readyEmotion(run, emotion, emotionEnd, degraded, skipped);
            run.checkpoint();
            return new Result(Route.HARNESS, new PreparedRequest(run.message, rewritten, feeling, degraded, skipped), null, null);
        } finally { dispose(clarification); dispose(rewrite); dispose(emotion); }
    }
    private Emotion readyEmotion(RunState run, Branch<Emotion> branch, Instant end, Set<String> degraded, Set<String> skipped) {
        if (!branch.future().isDone()) { skipped.add("emotion"); return Emotion.UNKNOWN; }
        try { return await(run, branch, end); }
        catch (PreparationUnavailable e) { degraded.add("emotion"); return Emotion.UNKNOWN; }
    }
    private Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private Instant deadline(RunState run, Duration timeout) {
        Instant local = Instant.now().plus(timeout);
        return local.isBefore(run.deadline()) ? local : run.deadline();
    }
    private <T> Branch<T> submit(RunState run, String name, Instant deadline, Function<CallControl, T> operation) {
        CancellationToken token = new CancellationToken();
        CallControl control = new CallControl(deadline, token);
        FutureTask<Completed<T>> task = new FutureTask<>(() -> harness.stage(run, "agent.prepare." + name, null, null, Map.of(), () -> {
            control.checkpoint();
            if (!Instant.now().isBefore(deadline)) throw new PreparationUnavailable();
            try {
                if (!name.equals("faq")) run.preparationCallStarted();
                return new Completed<>(operation.apply(control), Instant.now());
            } catch (AgentException e) {
                if (!Set.of("CANCELLED", "DEADLINE_EXCEEDED").contains(e.code())) run.fail(e.code());
                throw e;
            }
        }));
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
