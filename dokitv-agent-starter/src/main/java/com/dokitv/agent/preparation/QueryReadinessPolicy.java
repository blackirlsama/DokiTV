package com.dokitv.agent.preparation;

import com.dokitv.agent.core.PreparationTypes.*;
import java.util.Optional;

/** Deterministic business prerequisites supplement probabilistic model classification. Never grants access. */
public interface QueryReadinessPolicy {
    Optional<Clarification> validate(Input input);
}
