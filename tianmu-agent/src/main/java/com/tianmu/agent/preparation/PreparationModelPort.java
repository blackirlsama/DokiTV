package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;

/** Three concurrent, independent small-model calls, each consuming the same original input. */
public interface PreparationModelPort {
    Emotion emotion(Input input, CallControl control);
    Clarification clarify(Input input, CallControl control);
    String rewrite(Input input, CallControl control);
}
