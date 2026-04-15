package com.chao.user.handler;

import com.chao.common.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public Result<String> handleBadRequest(Exception e) {
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return Result.fail(500, "服务异常");
    }
}
