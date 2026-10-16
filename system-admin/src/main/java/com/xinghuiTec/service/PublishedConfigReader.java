package com.xinghuiTec.service;

/** Narrow business-facing read contract; the implementation enforces the safe-key catalog. */
public interface PublishedConfigReader {
    String getPublishedValue(String key);
    boolean isCaptchaEnabled(boolean legacyDefault);
}
