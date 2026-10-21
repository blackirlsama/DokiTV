package com.dokitv.agent.preparation;

import com.dokitv.agent.core.PreparationTypes.*;
import com.dokitv.agent.runtime.control.CallControl;
import java.util.Optional;

/** Return only authorized, approved, current FAQ answers. Business metrics must use tools/evidence. */
@FunctionalInterface
public interface FaqPort {
    Optional<FaqAnswer> find(Input input, CallControl control);
}
