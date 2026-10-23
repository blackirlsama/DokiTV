package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

/** One critical understanding call and one optional, non-blocking emotion call. */
public interface PreparationModelPort {
    Understanding understand(Input input, CallControl control);
    Emotion emotion(Input input, CallControl control);
}
