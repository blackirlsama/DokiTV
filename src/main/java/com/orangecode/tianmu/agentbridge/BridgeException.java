package com.orangecode.tianmu.agentbridge;

public final class BridgeException extends RuntimeException {
    private final String code;
    private final int status;
    public BridgeException(String code, int status) { super(code); this.code = code; this.status = status; }
    public String code() { return code; }
    public int status() { return status; }
}
