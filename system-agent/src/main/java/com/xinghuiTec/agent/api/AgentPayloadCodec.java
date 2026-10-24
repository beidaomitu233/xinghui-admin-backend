package com.xinghuiTec.agent.api;

/** Application-owned encryption. The purpose binds ciphertext to its owner, session and slot. */
public interface AgentPayloadCodec {
    String seal(String purpose, String plaintext);
    String open(String purpose, String ciphertext);
}
