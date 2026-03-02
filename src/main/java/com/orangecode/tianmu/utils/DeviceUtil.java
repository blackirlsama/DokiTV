package com.orangecode.tianmu.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.Header;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.ThrowUtils;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 设备工具类
 */
public class DeviceUtil {

    /**
     * 根据请求获取设备信息
     * @param request
     * @return String
     */

    public static String getHttpRequestDevice(HttpServletRequest request) {
        String userAgentStr = request.getHeader(Header.USER_AGENT.toString());
        return getDevice(userAgentStr);
    }

    public static String getDevice(String userAgentStr) {
        UserAgent userAgent = UserAgentUtil.parse(userAgentStr);
        ThrowUtils.throwIf(userAgent == null, ErrorCode.OPERATION_ERROR, "非法请求");
        String device = "pc"; // 默认值是 PC
        if (isMiniProgram(userAgentStr)) {
            device = "miniProgram";    // 是否为小程序
        } else if (isPad(userAgentStr)) {
            device = "pad"; // 是否为 Pad
        } else if (userAgent.isMobile()) {
            device = "mobile"; // 是否为手机
        }
        return device;
    }

    /**
     * 判断是否是小程序
     * 一般通过 User-Agent 字符串中的 "MicroMessenger" 来判断是否是微信小程序
     **/
    private static boolean isMiniProgram(String userAgentStr) {
        // 判断 User-Agent 是否包含 "MicroMessenger" 表示是微信环境
        return StrUtil.containsIgnoreCase(userAgentStr, "MicroMessenger")
                && StrUtil.containsIgnoreCase(userAgentStr, "MiniProgram");
    }


    /**
     * 判断是否为平板设备
     * 支持 iOS（如 iPad）和 Android 平板的检测
     **/
    private static boolean isPad(String userAgentStr) {
        boolean isIpad = StrUtil.containsIgnoreCase(userAgentStr, "iPad"); // 检查 iPad 的 User-Agent 标志
        boolean isAndroidTablet = StrUtil.containsIgnoreCase(userAgentStr, "Android")       // 检查 Android 平板（包含 "Android" 且不包含 "Mobile"）
                && !StrUtil.containsIgnoreCase(userAgentStr, "Mobile");
        return isIpad || isAndroidTablet;         // 如果是 iPad 或 Android 平板，则返回 true
    }
}
