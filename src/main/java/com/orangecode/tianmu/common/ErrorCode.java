package com.orangecode.tianmu.common;

/**
 * 自定义错误码
 */
public enum ErrorCode {
    PARAMS_ERROR(40000, "请求参数错误"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    INVALID_PARAMETER_ERROR(50003, "参数校验失败"),
    PLEASE_LOGIN(50004, "请先登录"),
    SAME_LOGIN_CONFLICT(50005, "账号已在其他地方登录"),
    TOKEN_EXPIRED(50006, "token已过期"),
    VIDEO_NOT_FOUND_ERROR(60000, "视频不存在"),
    MERGE_FILE_ERROR(60001, "合并文件失败"),
    VIDEO_GET_URL_ERROR(60002, "获取视频url失败"),
    CHUNK_FILE_LACK(60003, "分片文件缺失，请重新上传"),
    PHONE_EMAIL_ERROR(70000, "手机号/邮箱格式错误"),
    USER_ALREADY_EXISTS(70001, "用户已存在"),
    USER_NOT_EXISTS(70002, "用户不存在"),
    REGISTER_ERROR(70003, "注册失败"),
    LOGIN_ERROR_CODE(70004, "验证码错误"),
    LOGIN_ERROR(70005, "登录失败, 用户名或密码错误"),
    VIDEO_LIKED_ERROR(70006, "视频已点赞"),
    VIDEO_LIKED_NOT_EXISTS(70007, "视频未点赞"),
    VIDEO_FAVORITE_ERROR(70008, "视频已收藏"),
    VIDEO_FAVORITE_NOT_EXISTS(70009, "视频未收藏"),
    VIDEO_COIN_ERROR(70010, "视频已投币"),
    USER_COIN_ERROR(70011, "用户投币不足"),
    ACCESS_TOO_FREQUENTLY(70012, "访问太频繁"),
    CREATE_COMMENT_ERROR(70013, "评论失败"),
    PARENT_COMMENT_NOT_EXISTS(70014, "父评论不存在"),
    ES_INDEX_ERROR(70015, "ES索引异常"),
    BULLET_NOT_EXISTS(70016, "弹幕不存在"),
    DELETE_COMMENT_ERROR(70017, "删除评论失败"),
    FILE_SIZE_ERROR(70018, "文件大小超过限制"),
    PHONE_REGISTRATION_NOT_SUPPORTED(70019, "不支持手机号登录注册"),
    VERIFICATION_CODE_ERROR(70020, "验证码错误"),
    PERSISTENCE_ERROR(70021, "持久化异常");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
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
