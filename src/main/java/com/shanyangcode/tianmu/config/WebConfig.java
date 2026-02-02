package com.shanyangcode.tianmu.config;
import com.shanyangcode.tianmu.interceptor.JWTInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Autowired
    private JWTInterceptor jwtInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //自定义的 Spring MVC 拦截器
        // 拦截所有请求
        registry.addInterceptor(jwtInterceptor).addPathPatterns("/**")
                // 可以在这里排除一些不需要拦截的路径
                .excludePathPatterns("/api/user/sendVerificationCode", "/api/user/register", "/api/user/info",
                        "/api/user/loginCode", "/api/user/getCode","/api/user/focus/list",
                        "/api/user/fans/list", "/api/user/loginPassword", "/api/video/list",
                        "/api/video/detail", "/api/video/comment/list", "/api/video/submit/list",
                        "/api/video/coin/list", "/api/video/like/list", "/api/video/favorite/list",
                        "/api/category", "/api/category/list","/api/file/check");
    }
}
