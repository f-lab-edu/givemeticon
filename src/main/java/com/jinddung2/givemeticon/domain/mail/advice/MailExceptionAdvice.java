package com.jinddung2.givemeticon.domain.mail.advice;

import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.mail.exception.EmailNotFoundException;
import com.jinddung2.givemeticon.domain.mail.exception.InvalidCertificationNumberException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class MailExceptionAdvice {

    @ExceptionHandler(EmailNotFoundException.class)
    public ResponseEntity<ErrorResult> handleEmailNotFoundException(EmailNotFoundException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.error("mail exception!! error msg={}", errorResult);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InvalidCertificationNumberException.class)
    public ResponseEntity<ErrorResult> handleInvalidCertificationNumberException(InvalidCertificationNumberException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.error("mail exception!! error msg={}", errorResult);
        return new ResponseEntity<>(errorResult, HttpStatus.BAD_REQUEST);
    }
}
