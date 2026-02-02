package com.shanyangcode.tianmu.interceptor;

import com.shanyangcode.tianmu.utils.DeviceUtil;
import com.shanyangcode.tianmu.utils.JwtUtil;
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
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头中获取token
        String token = request.getHeader("Authorization");

        // 如果请求带有token，则过滤掉（直接放行）
        if (token != null && !token.isEmpty()) {
            Claims claims = JwtUtil.parse(token);
            if (claims != null) {
                String id = claims.getSubject();
                String requestDevice = DeviceUtil.getHttpRequestDevice(request);
                String userToken = redisTemplate.opsForValue().get(requestDevice + ":" + id);
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