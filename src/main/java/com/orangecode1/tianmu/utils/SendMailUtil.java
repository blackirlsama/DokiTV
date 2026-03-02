package com.orangecode.tianmu.utils;

import com.orangecode.tianmu.constants.SMSConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class SendMailUtil {
    private static final ExecutorService executor = Executors.newFixedThreadPool(5);
    public static void sendEmailCode(String targetEmail, String authCode) {
        executor.submit(() -> {
            try {
                // 设置TLS协议
                System.setProperty(SMSConstant.EMAIL_PROTOCOL, SMSConstant.TLS_VERSION);
                // 创建邮箱对象
                SimpleEmail mail = new SimpleEmail();
                // 设置发送邮件的服务器
                mail.setHostName(SMSConstant.EMAIL_HOST_NAME);
                // "你的邮箱号"+ "上文开启SMTP获得的授权码"
                mail.setAuthentication(SMSConstant.EMAIL_USER_NAME, SMSConstant.EMAIL_PASSWORD);
                // 发送邮件 "你的邮箱号"+"发送时用的昵称"
                mail.setFrom(SMSConstant.EMAIL_USER_NAME, SMSConstant.EMAIL_NAME);
                // 使用安全链接
                mail.setSSLOnConnect(true);
                // 接收用户的邮箱
                mail.addTo(targetEmail);
                // 邮件的主题(标题)
                mail.setSubject(SMSConstant.EMAIL_SUBJECT);
                // 邮件的内容
                mail.setMsg(String.format(SMSConstant.VERIFICATION_CODE_TEMPLATE, authCode));
                // 发送
                mail.send();
            } catch (EmailException e) {
                e.printStackTrace();
                log.error(SMSConstant.EMAIL_EXCEPTION_LOG_TEMPLATE, targetEmail, e);
            }
        });
    }
}