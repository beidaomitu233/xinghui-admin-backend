package com.xinghuiTec.ai.config;

/** Optional completion of a user's durable test-and-use intent; never starts another provider call. */
public interface AiConfigurationTestObserver {
    void completed(long configId, long testId);
}
