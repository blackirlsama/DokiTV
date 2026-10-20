package com.dokitv.agent.core;

public class AgentException extends RuntimeException {
    private final String code;
    public AgentException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
