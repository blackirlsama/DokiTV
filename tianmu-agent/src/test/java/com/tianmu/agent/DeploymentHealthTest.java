package com.tianmu.agent;

import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.health.AgentStorageHealthIndicator;
import com.tianmu.agent.runtime.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.Status;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentHealthTest {
    @TempDir Path directory;
    AgentStorageHealthIndicator indicator(RunRepository repository, ObjectStore objects) {
        var beans = new DefaultListableBeanFactory();
        if (objects != null) beans.registerSingleton("objects", objects);
        return new AgentStorageHealthIndicator(repository, beans.getBeanProvider(ObjectStore.class));
    }
    @Test void healthyPrivateDestinationIsProbedWithoutLeavingObjects() throws Exception {
        var repository = mock(RunRepository.class);
        var objects = new FileObjectStore(directory);
        assertEquals(Status.UP, indicator(repository, objects).health().getStatus());
        verify(repository).verifyReady();
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
    @Test void unavailableWriterMakesReadinessDownWithoutPublishingSensitiveDetails() {
        var repository = mock(RunRepository.class);
        doThrow(new AgentException("STORAGE_FENCED", "sensitive-internal-location")).when(repository).verifyReady();
        var health = indicator(repository, null).health();
        assertEquals(Status.DOWN, health.getStatus()); assertTrue(health.getDetails().isEmpty());
    }
    @Test void missingObjectDestinationMakesReadinessDown() throws Exception {
        var objects = new FileObjectStore(directory.resolve("objects"));
        Files.delete(directory.resolve("objects"));
        assertEquals(Status.DOWN, indicator(mock(RunRepository.class), objects).health().getStatus());
    }
    @Test void memoryModeDoesNotRequireAnExternalObjectStore() {
        assertEquals(Status.UP, indicator(mock(RunRepository.class), null).health().getStatus());
    }
}
