package com.dokitv.agent;

import com.dokitv.agent.application.MemoryBuilder;
import com.dokitv.agent.core.*;
import com.dokitv.agent.core.AgentTypes.*;
import com.dokitv.agent.core.MemoryTypes.*;
import com.dokitv.agent.runtime.RunState;
import com.dokitv.agent.runtime.events.InMemoryRunEventStore;
import com.dokitv.agent.runtime.store.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class StorageBoundaryTest {
    @Test void storageEndpointsAuthenticateBeforeResolvingStorage() {
        var controller = new com.dokitv.agent.api.StorageController(null, credential -> null, null);
        UUID id = UUID.randomUUID();
        assertEquals("UNAUTHORIZED", assertThrows(AgentException.class, () -> controller.artifacts(id, null)).code());
        assertEquals("UNAUTHORIZED", assertThrows(AgentException.class, () -> controller.artifact(id, "ar_test", null)).code());
        assertEquals("UNAUTHORIZED", assertThrows(AgentException.class, () -> controller.delete(id, null)).code());
    }
    @Test void persistedLimitsStillArmTheIndependentWatchdog() throws Exception {
        Instant now = Instant.now(), deadline = now.plusMillis(150);
        RunState run = new RunState(UUID.randomUUID(), UUID.randomUUID(), 1, new AccessScope("t", "u", Set.of(1L)),
                "q", "q", now, deadline, RunBackend.MEMORY, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), true);
        run.bindPersistedControl(4); run.start();
        java.util.concurrent.CountDownLatch terminal = new java.util.concurrent.CountDownLatch(1);
        run.onTerminal(snapshot -> terminal.countDown());
        try (var loop = new com.dokitv.agent.runtime.control.LoopControl(com.dokitv.agent.runtime.control.LoopPolicy.defaults())) {
            loop.arm(run); assertTrue(terminal.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(deadline, run.deadline()); assertEquals(4, run.modelCallLimit());
            assertEquals("DEADLINE_EXCEEDED", run.snapshot().errorCode());
        }
    }
    @Test void failedDurableCommitCannotPublishAnswerOrConsumeTerminalRight() {
        AtomicBoolean broken = new AtomicBoolean(true); InMemoryRunEventStore events = new InMemoryRunEventStore();
        RunBackend backend = new RunBackend() {
            public Snapshot commit(RunState run, Snapshot proposed, String event, Object data, Object done) {
                assertEquals(Status.RUNNING, run.snapshot().status());
                if (broken.get()) throw new AgentException("STORAGE_UNAVAILABLE", "down");
                return RunBackend.super.commit(run, proposed, event, data, done);
            }
        };
        Instant now = Instant.now();
        RunState run = new RunState(UUID.randomUUID(), UUID.randomUUID(), 1, new AccessScope("t", "u", Set.of(1L)),
                "question", "question", now, now.plusSeconds(30), backend, events, new InMemoryEvidenceStore(), false);
        run.start(); var reservation = run.reserveFinalResponse();
        assertThrows(AgentException.class, () -> run.succeed(reservation, new Answer("answer", List.of()), StopReason.ANSWER));
        assertEquals(Status.RUNNING, run.snapshot().status()); assertNull(run.snapshot().answer());
        assertTrue(events.after(0).events().stream().noneMatch(e -> e.type().equals("DONE")));
        broken.set(false); run.fail("INTERNAL_ERROR");
        assertEquals(Status.FAILED, run.snapshot().status());
        assertEquals(List.of("ERROR", "DONE"), events.after(0).events().stream().filter(e -> !e.type().equals("PROGRESS")).map(Event::type).toList());
    }
    List<Message> history(int turns, int chars) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            UUID run = UUID.randomUUID();
            messages.add(new Message(UUID.randomUUID(), run, i * 2 + 1, "USER", "q".repeat(chars), Instant.now()));
            messages.add(new Message(UUID.randomUUID(), run, i * 2 + 2, "ASSISTANT", "a".repeat(chars), Instant.now()));
        }
        return messages;
    }
    @Test void memoryKeepsNewestCompletePairsAndSourceRemainsUnchanged() {
        var source = history(10, 50); var view = new MemoryBuilder().build(7, source);
        assertEquals(20, source.size()); assertEquals(8, view.recent().size());
        assertEquals(13, view.recent().get(0).sequence()); assertEquals(7, view.version());
        assertFalse(view.olderSummary().isBlank()); assertTrue(view.boundary().contains("CURRENT_FOCUS_BOUNDARY"));
        assertThrows(UnsupportedOperationException.class, () -> view.recent().clear());
    }
    @Test void rawHistoryAlsoHonorsTheTotalCharacterLimitWithoutSplittingPairs() {
        var source = history(10, 8000); var view = new MemoryBuilder().build(1, source);
        assertTrue(view.recent().size() % 2 == 0);
        assertTrue(view.olderSummary().length() + view.recent().stream().mapToInt(m -> m.text().length()).sum() <= 16000);
        assertTrue(view.truncated()); assertEquals(20, source.size());
        assertEquals(List.of(19L, 20L), view.recent().stream().map(Message::sequence).toList());
        for (int padding = 0; padding < 4; padding++) {
            var emoji = history(5, 1994); Message first = emoji.get(0), last = emoji.get(9);
            emoji.set(0, new Message(first.id(), first.runId(), first.sequence(), first.role(), "😀".repeat(100), first.at()));
            emoji.set(9, new Message(last.id(), last.runId(), last.sequence(), last.role(), "x".repeat(1994 + padding), last.at()));
            String summary = new MemoryBuilder().build(1, emoji).olderSummary();
            assertTrue(summary.isEmpty() || !Character.isHighSurrogate(summary.charAt(summary.length() - 1)));
        }
    }
    @Test void explicitClarificationResumesOnlyTheSupportedConditions() {
        assertEquals("查询视频1001 昨天的弹幕数", com.dokitv.agent.application.ClarificationInputs.combine("查询视频1001的弹幕数", "昨天"));
        assertThrows(AgentException.class, () -> com.dokitv.agent.application.ClarificationInputs.combine("生成复杂报告", "昨天"));
        assertThrows(AgentException.class, () -> com.dokitv.agent.application.ClarificationInputs.combine("查询视频1001的弹幕数", "视频1002和视频1003"));
        assertEquals("查询视频1001 2030-01-01的弹幕数", com.dokitv.agent.application.ClarificationInputs.combine("查询昨天的弹幕数", "视频1001", LocalDate.of(2030, 1, 2)));
        assertFalse(com.dokitv.agent.application.ClarificationInputs.confirmed("查询视频1001和视频1002的弹幕", LocalDate.of(2030, 1, 2)).containsKey("video_id"));
    }
    @Test void privateObjectsRejectTraversalAndDetectCorruption(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var store = new com.dokitv.agent.adapters.storage.FileObjectStore(directory);
        byte[] body = "{\"value\":128}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = com.dokitv.agent.adapters.storage.FileObjectStore.hash(body);
        store.put("ar_test.json", body); assertArrayEquals(body, store.get("ar_test.json", body.length, sha));
        assertThrows(AgentException.class, () -> store.put("../escape.json", body));
        java.nio.file.Files.write(directory.resolve("ar_test.json"), "corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(AgentException.class, () -> store.get("ar_test.json", body.length, sha));
        store.delete("ar_test.json"); assertFalse(java.nio.file.Files.exists(directory.resolve("ar_test.json")));
    }
}
