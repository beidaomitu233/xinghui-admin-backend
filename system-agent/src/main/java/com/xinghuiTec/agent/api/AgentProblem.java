package com.xinghuiTec.agent.api;

/** Only these deliberately public messages may be returned to the model or workbench. */
public final class AgentProblem extends RuntimeException {
    private final String code;
    public AgentProblem(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
