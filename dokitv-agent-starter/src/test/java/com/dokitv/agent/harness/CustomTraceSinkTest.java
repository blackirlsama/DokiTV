package com.dokitv.agent.harness;

import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.harness.trace.*;
import com.dokitv.agent.runtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** A registered sink must bypass built-in destination creation, independent of config class order. */
@SpringBootTest(properties = {"harness.trace.directory=./target/test-traces/custom", "harness.trace.sink=external-plugin"})
@Import(CustomTraceSinkTest.Plugin.class)
class CustomTraceSinkTest {
    static final List<TraceEnvelope> received = new CopyOnWriteArrayList<>();
    @TestConfiguration
    static class Plugin {
        @Bean TraceSink pluginSink() { return (event, json) -> received.add(event); }
    }
    @Autowired AgentRuntime runtime;
    @Autowired OutboxSpanExporter exporter;
    @Test void customSinkWorksWithoutRuntimeOrConfigurationEdits() {
        RunState run = new RunState(new AccessScope("demo", "alice", Set.of(1001L)), "hello");
        runtime.execute(run);
        exporter.flush().join(5, TimeUnit.SECONDS);
        assertEquals(Status.NEEDS_INPUT, run.snapshot().status());
        assertTrue(received.stream().anyMatch(event -> event.name().equals("agent.run")
                && event.attributes().get("run.id").equals(run.id.toString())));
    }
}
