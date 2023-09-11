package com.example.givemeticon.common.advice;

import com.example.givemeticon.common.response.ErrorResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class CommonExceptionAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResult> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String className = e.getParameter().getMethod().getDeclaringClass().getSimpleName();
        String methodName = e.getParameter().getMethod().getName();
        String defaultMessage = e.getFieldError().getDefaultMessage();

        ErrorResult errorResult = new ErrorResult(defaultMessage);
        log.debug("Exception Occurred in {}.{}(). Error message: {}", className, methodName, defaultMessage);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }

}
