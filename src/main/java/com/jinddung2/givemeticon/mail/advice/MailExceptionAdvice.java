package com.jinddung2.givemeticon.mail.advice;

import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.mail.exception.MailException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class MailExceptionAdvice {

    @ExceptionHandler(MailException.class)
    public ResponseEntity<ErrorResult> handleMailException(MailException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("mail exception!! error msg={}", errorResult);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }
}
