package com.jinddung2.givemeticon.domain.user.advice;

import com.jinddung2.givemeticon.common.response.ApiResponse;
import com.jinddung2.givemeticon.common.response.ErrorResult;
import com.jinddung2.givemeticon.domain.favorite.exception.ItemFavoriteException;
import com.jinddung2.givemeticon.domain.point.exception.CashPointException;
import com.jinddung2.givemeticon.domain.user.exception.UserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class UserExceptionAdvice {

    @ExceptionHandler(UserException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleUserException(UserException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("user exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ItemFavoriteException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleItemFavoriteException(ItemFavoriteException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("itemFavorite exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(CashPointException.class)
    public ResponseEntity<ApiResponse<ErrorResult>> handleCashPointException(CashPointException e) {
        ErrorResult errorResult = new ErrorResult(e.getMessage());
        log.debug("cashPoint exception!! error msg={}", errorResult);
        return new ResponseEntity<>(ApiResponse.fail(errorResult), HttpStatus.BAD_REQUEST);
    }
}
