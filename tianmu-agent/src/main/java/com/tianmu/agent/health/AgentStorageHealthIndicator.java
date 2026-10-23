package com.tianmu.agent.health;

import com.tianmu.agent.runtime.store.ObjectStore;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Required storage affects readiness. Redis and Trace are degradable and do not affect liveness. */
@Component("agentStorageHealthIndicator")
public final class AgentStorageHealthIndicator implements HealthIndicator {
    private final RunRepository repository;
    private final ObjectProvider<ObjectStore> objects;
    public AgentStorageHealthIndicator(RunRepository repository, ObjectProvider<ObjectStore> objects) {
        this.repository = repository; this.objects = objects;
    }
    @Override public Health health() {
        try {
            repository.verifyReady();
            objects.ifAvailable(ObjectStore::verifyReady);
            return Health.up().build();
        } catch (Exception e) {
            // Neither exception text nor credentials/paths are part of an anonymous probe response.
            return Health.down().build();
        }
    }
}
