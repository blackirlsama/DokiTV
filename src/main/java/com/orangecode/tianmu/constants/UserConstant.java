package com.orangecode.tianmu.constants;

/**
 * 用户相关常量
 */
public class UserConstant {
    public static final String PASSWORD_SALT = "TianMu";

    public static final String USER_ES_INDEX = "user";

    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";

    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";
}
