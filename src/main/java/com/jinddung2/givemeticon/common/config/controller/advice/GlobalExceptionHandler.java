package com.jinddung2.givemeticon.common.config.controller.advice;

import com.jinddung2.givemeticon.common.config.controller.exception.ApiErrorResponse;
import com.jinddung2.givemeticon.common.config.controller.exception.CommonErrorCode;
import com.jinddung2.givemeticon.common.config.controller.exception.ErrorCode;
import com.jinddung2.givemeticon.common.exception.GiveMeTiConException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        log.warn("handle IllegalArgument", ex);
        CommonErrorCode errorCode = CommonErrorCode.INVALID_PARAMETER;
        return handleExceptionInternal(ex, errorCode);
    }

    @ExceptionHandler(GiveMeTiConException.class)
    public ResponseEntity<ApiErrorResponse> handleGiveMeTiConException(GiveMeTiConException e) {
        log.warn("error invoke in our app", e);
        ErrorCode errorCode = e.getErrorCode();
        return handleExceptionInternal(errorCode);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("handle IllegalArgument", e);
        CommonErrorCode errorCode = CommonErrorCode.INVALID_PARAMETER;
        return handleExceptionInternal(errorCode);
    }

    @ExceptionHandler({Exception.class})
    public ResponseEntity<ApiErrorResponse> handleAllException(Exception ex) {
        log.warn("handle AllException", ex);
        CommonErrorCode errorCode = CommonErrorCode.INTERNAL_SERVER_ERROR;
        return handleExceptionInternal(errorCode);
    }

    private ResponseEntity<ApiErrorResponse> handleExceptionInternal(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(makeErrorResponse(errorCode));
    }

    private ApiErrorResponse makeErrorResponse(ErrorCode errorCode) {
        return ApiErrorResponse.builder()
                .code(errorCode.getHttpStatus().value())
                .message(errorCode.getHttpStatus().name())
                .errorDetail(errorCode.getErrorDetail())
                .build();
    }


    private ResponseEntity<Object> handleExceptionInternal(BindException e, ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(makeErrorResponse(e, errorCode));
    }

    private ApiErrorResponse makeErrorResponse(BindException e, ErrorCode errorCode) {
        List<ApiErrorResponse.ValidationError> validationErrorList = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(ApiErrorResponse.ValidationError::of)
                .collect(Collectors.toList());

        return ApiErrorResponse.builder()
                .code(errorCode.getHttpStatus().value())
                .message(errorCode.getHttpStatus().name())
                .errorDetail(e.getMessage())
                .errors(validationErrorList)
                .build();
    }
}
