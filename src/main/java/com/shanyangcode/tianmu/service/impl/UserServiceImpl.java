package com.shanyangcode.tianmu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shanyangcode.tianmu.entity.User;
import com.shanyangcode.tianmu.mapper.UserMapper;
import com.shanyangcode.tianmu.service.UserService;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private static final ExecutorService executor = Executors.newFixedThreadPool(5);

    @Override
    public void sendVerificationCode(String account) {

        String vCode = "130829"; // needs to be replaced by random numbers
        System.out.println("The function operates in " + Thread.currentThread().getName());

        executor.submit(() -> {
            try {
                // 设置TLS协议
                System.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");
                // 创建邮箱对象
                SimpleEmail mail = new SimpleEmail();
                // 设置发送邮件的服务器
                mail.setHostName("smtp.qq.com");
                // "你的邮箱号"+ "上文开启SMTP获得的授权码"
                mail.setAuthentication("799243133@qq.com", "opltwhrxmeinbdaf");
                // 发送邮件 "你的邮箱号"+"发送时用的昵称"
                mail.setFrom("799243133@qq.com", "orange");
                // 使用安全链接
                mail.setSSLOnConnect(true);
                // 接收用户的邮箱
                mail.addTo(account);
                // 邮件的主题(标题)
                mail.setSubject("注册验证码");
                // 邮件的内容
                mail.setMsg("主人你好喵，您的验证码为:" + vCode + "(五分钟内有效)");
                // 发送
                mail.send();
                System.out.println("message sent in " + Thread.currentThread().getName());
            } catch (EmailException e) {
                throw new RuntimeException(e);
            }
        });
    }
}