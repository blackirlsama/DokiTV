package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import java.util.Optional;

/** Deterministic business prerequisites supplement probabilistic model classification. Never grants access. */
public interface QueryReadinessPolicy {
    Optional<Clarification> validate(Input input);
}
