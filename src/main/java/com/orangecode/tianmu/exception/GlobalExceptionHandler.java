package com.orangecode.tianmu.exception;


import java.util.HashMap;
import java.util.Map;

import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.common.ResultUtils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(fieldName, message);
        });
        String message = errors.toString();
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, message);
    }


    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public BaseResponse<?> handlerMissingServletRequestParameterException(Exception e) {
        log.error("缺少必填参数:{}", e.toString());
        return ResultUtils.error(ErrorCode.INVALID_PARAMETER_ERROR, "缺少必填参数");
    }
}