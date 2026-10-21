package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.Optional;

/** Return only authorized, approved, current FAQ answers. Business metrics must use tools/evidence. */
@FunctionalInterface
public interface FaqPort {
    Optional<FaqAnswer> find(Input input, CallControl control);
}
