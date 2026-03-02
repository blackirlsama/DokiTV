package com.orangecode.tianmu.utils;

import java.util.Random;

public class RandomCodeUtil {

    /**
     * @MethodName generateSixDigitRandomNumber
     * @Description 随机生成6位验证码
     * @return: String
     * @Date 2025/4/10 15:34
     */
    public static String generateSixDigitRandomNumber() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(6);

        for (int i = 0; i < 6; i++) {
            // 生成0-9的随机数
            int digit = random.nextInt(10);
            sb.append(digit);
        }

        return sb.toString();
    }
}
