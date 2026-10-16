package com.xinghuiTec.ai.application;

/** Durable states: transport EOF is never an implicit success. */
public enum AiRequestStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INCOMPLETE, TIMED_OUT;

    public boolean terminal() {
        return this != QUEUED && this != RUNNING;
    }
}
