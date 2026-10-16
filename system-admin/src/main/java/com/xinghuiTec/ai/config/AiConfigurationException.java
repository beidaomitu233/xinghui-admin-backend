package com.xinghuiTec.ai.config;

/** Fixed operator message only. Never construct this from upstream errors or secret input. */
public final class AiConfigurationException extends RuntimeException {
    private final int status;
    public AiConfigurationException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
