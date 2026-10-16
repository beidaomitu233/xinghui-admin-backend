package com.xinghuiTec.ai.contract;

import java.util.Objects;

/** Safe boundary failure: no arbitrary message, provider body or secret-bearing cause. */
public final class AiRuntimeException extends RuntimeException {
    private final AiTypes.ErrorCode code;

    public AiRuntimeException(AiTypes.ErrorCode code) {
        super(Objects.requireNonNull(code).name());
        this.code = code;
    }

    public AiTypes.ErrorCode code() { return code; }
}
