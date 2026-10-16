package com.xinghuiTec.wechat;

/** Controlled messages only; never attach provider responses or sensitive request values. */
public class WechatFailure extends RuntimeException {
    public final int status;
    public WechatFailure(int status, String message) { super(message); this.status = status; }
}
