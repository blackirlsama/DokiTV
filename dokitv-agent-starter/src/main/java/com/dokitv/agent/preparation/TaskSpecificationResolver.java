package com.dokitv.agent.preparation;

import com.dokitv.agent.core.TaskTypes.Specification;
import java.time.LocalDate;
import java.util.Optional;

/** A business capability may resolve only conditions explicitly supported by its parser. */
public interface TaskSpecificationResolver {
    Optional<Specification> resolve(String original, LocalDate businessDate);
}
