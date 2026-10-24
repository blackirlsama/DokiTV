package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

/** Independent intent and clarification/condition candidates, plus optional emotion. */
public interface PreparationModelPort {
    Intent intent(Input input, CallControl control);
    Understanding understand(Input input, CallControl control);
    Emotion emotion(Input input, CallControl control);
}
