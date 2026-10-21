package com.dokitv.agent.runtime;

import com.dokitv.agent.core.AgentException;
import com.dokitv.agent.core.PreparationTypes.*;
import com.dokitv.agent.harness.RuntimeHarness;
import com.dokitv.agent.harness.hook.*;
import com.dokitv.agent.preparation.PreparationService;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Application entry pipeline. AgentRuntime only runs after preparation chooses HARNESS. */
@Component
public class RequestOrchestrator {
    private final PreparationService preparation;
    private final AgentRuntime runtime;
    private final RuntimeHarness harness;
    private final com.dokitv.agent.application.DeliveryService delivery;
    private final com.dokitv.agent.application.TaskAssembler assembler;
    public RequestOrchestrator(PreparationService preparation, AgentRuntime runtime, RuntimeHarness harness) {
        this(preparation, runtime, harness, new com.dokitv.agent.application.DeliveryService(harness),
                new com.dokitv.agent.application.TaskAssembler(new com.dokitv.agent.skill.SkillCatalog(), new com.dokitv.agent.prompt.PromptCatalog(), new com.dokitv.agent.tool.ToolCatalog()));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RequestOrchestrator(PreparationService preparation, AgentRuntime runtime, RuntimeHarness harness,
            com.dokitv.agent.application.DeliveryService delivery, com.dokitv.agent.application.TaskAssembler assembler) {
        this.preparation = preparation; this.runtime = runtime; this.harness = harness;
        this.delivery = delivery; this.assembler = assembler;
    }
    public void execute(RunState run) {
        try {
            harness.execute(run, () -> {
                if (!run.start()) return;
                String input = harness.stage(run, "agent.input", null, null, Map.of(),
                        () -> harness.transform(run, TransformPoint.INPUT, run.query()));
                harness.stage(run, "agent.route", HookPoint.PRE_ROUTE, null, Map.of(), () -> true);
                Result result = harness.stage(run, "agent.preparation", null, null, Map.of(), () -> preparation.prepare(run, input));
                run.prepared(result);
                switch (result.route()) {
                    case FAQ -> delivery.faq(run, result.faq());
                    case CLARIFY -> delivery.clarification(run, result.clarification());
                    case HARNESS -> {
                        var task = harness.stage(run, "agent.assemble", null, null, Map.of(), () -> assembler.assemble(run));
                        run.task(task); runtime.runPrepared(run, task);
                    }
                }
            });
        } catch (AgentException e) { run.fail(e.code()); }
        catch (Exception e) { run.fail("INTERNAL_ERROR"); }
    }
}
