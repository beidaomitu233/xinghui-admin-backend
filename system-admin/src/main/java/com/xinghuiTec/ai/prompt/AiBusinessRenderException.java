package com.xinghuiTec.ai.prompt;

/** Internal stable errors. Neither variable values nor resolver causes are carried across this boundary. */
public final class AiBusinessRenderException extends RuntimeException {
    public enum Code {
        UNAUTHENTICATED, INVALID_IDENTITY, APP_NOT_REGISTERED, INVALID_REGISTRATION,
        DUPLICATE_REGISTRATION, CONTEXT_INVALID, BUSINESS_PERMISSION_REQUIRED,
        RESOURCE_NOT_FOUND, TENANT_MISMATCH, RESOURCE_FORBIDDEN, RESOLVER_FAILED,
        SCENE_UNAVAILABLE, VARIABLE_NOT_REGISTERED, MISSING_VARIABLE,
        UNDECLARED_VALUE, INVALID_VARIABLE_VALUE, OUTPUT_TOO_LONG
    }

    private final Code code;

    public AiBusinessRenderException(Code code) {
        super("业务提示词渲染失败：" + code.name(), null, false, true);
        this.code = code;
    }

    public Code code() { return code; }
}
