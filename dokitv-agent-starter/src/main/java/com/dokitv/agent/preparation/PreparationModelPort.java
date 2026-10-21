package com.dokitv.agent.preparation;

import com.dokitv.agent.core.PreparationTypes.*;
import com.dokitv.agent.runtime.control.CallControl;

/** Three concurrent, independent small-model calls, each consuming the same original input. */
public interface PreparationModelPort {
    Emotion emotion(Input input, CallControl control);
    Clarification clarify(Input input, CallControl control);
    String rewrite(Input input, CallControl control);
}
