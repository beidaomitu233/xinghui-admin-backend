package com.xinghuiTec.ai.config;

/** Fixed codes only. Never retain upstream bodies, URLs, headers or exception causes. */
public final class AiModelCatalogException extends RuntimeException {
    private final int status;

    public AiModelCatalogException(int status, String code) {
        super("AI_MODEL_CATALOG:" + code);
        this.status = status;
    }

    public int status() { return status; }
}
