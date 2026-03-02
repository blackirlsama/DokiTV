package com.orangecode.tianmu.interceptor;

import java.util.Arrays;
import java.util.List;

import com.orangecode.tianmu.utils.JwtUtil;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JWTInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate;


    List<String> excludePaths = Arrays.asList(
            "/api/user/sendVerificationCode",
            "/api/user/register",
            "/api/user/info",
            "/api/user/loginCode",
            "/api/user/focus/list",
            "/api/user/fans/list",
            "/api/user/loginPassword",
            "/api/video/list",
            "/api/video/detail",
            "/api/video/comment/list",
            "/api/video/submit/list",
            "/api/video/coin/list",
            "/api/video/like/list",
            "/api/video/favorite/list",
            "/api/category",
            "/api/category/list",
            "/api/search/video",
            "/api/search/user"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头中获取token
        String token = request.getHeader("Authorization");

        if (excludePaths.contains(request.getRequestURI())) {
            return true;
        }

        // 如果请求带有token，则过滤掉（直接放行）
        if (token != null && !token.isEmpty()) {
            Claims claims = JwtUtil.parse(token);
            if (claims != null) {
                String id = claims.getSubject();
                String userToken = redisTemplate.opsForValue().get(id);
                boolean isToken = userToken != null && token.equals(userToken);
                if (isToken) {
                    return true;
                } else {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("{\"code\": 401, \"message\": \"未授权的访问，请提供有效的Token\"}");
                    return false;
                }
            }
        }

        // 如果没有token，可以根据需求进行处理
        // 例如返回错误响应
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write("{\"code\": 401, \"message\": \"未授权的访问，请提供有效的Token\"}");

        return false;
    }
}