package com.xinghuiTec.notification;

/** Stable, non-sensitive reason for a rejected event request. */
public class NotificationContractException extends RuntimeException {
    private final String code;

    public NotificationContractException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
