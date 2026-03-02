package com.orangecode.tianmu.common;

public enum SuccessCode {
    SUCCESS(0, "ok");
    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    SuccessCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
