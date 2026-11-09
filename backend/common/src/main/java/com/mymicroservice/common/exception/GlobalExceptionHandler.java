package com.mymicroservice.common.exception;

import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import lombok.extern.slf4j.Slf4j;

// 錯誤回應統一為 ProblemDetail（RFC 9457，Content-Type: application/problem+json），並附上 code 欄位
// 繼承 ResponseEntityExceptionHandler：JSON 格式錯誤、路徑不存在、HTTP 方法不支援等 Spring MVC 例外會回正確的 4xx，而非落到 500
// 由 CommonAutoConfiguration 註冊為 bean；@RestControllerAdvice 仍需保留，Spring MVC 靠它辨識
// 優先序最低：服務自己的 @RestControllerAdvice 會先處理，不會被這裡的 Exception 兜底搶走
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(exception = AppException.class)
    public ProblemDetail handleAppException(AppException exception) {
        return toProblemDetail(exception.getErrorCode(), exception.getMessage());
    }

    // 版本比對通過後、flush 前被他人搶先更新時，由 Hibernate 的 @Version 檢查擋下
    @ExceptionHandler(exception = ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailureException(ObjectOptimisticLockingFailureException exception) {
        return toProblemDetail(CommonErrorCode.VERSION_CONFLICT, CommonErrorCode.VERSION_CONFLICT.getMessage());
    }

    // 併發寫入時繞過 service 檢查，最後由 DB 唯一約束擋下
    @ExceptionHandler(exception = DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolationException(DataIntegrityViolationException exception) {
        return toProblemDetail(CommonErrorCode.DATA_CONFLICT, CommonErrorCode.DATA_CONFLICT.getMessage());
    }

    @ExceptionHandler(exception = Exception.class)
    public ProblemDetail handleUnknownException(Exception exception) {
        log.error("unexpected exception", exception);
        return toProblemDetail(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.getMessage());
    }

    // @RequestBody、@ParameterObject 的欄位驗證失敗
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = exception.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                        : error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(toProblemDetail(CommonErrorCode.VALIDATION_FAILED, detail));
    }

    // 方法參數上的約束驗證失敗，例如 @PathVariable、@RequestParam 的 @Size
    // controller 方法有參數約束時，同一個方法的 @Valid body 也改由這裡處理（不會走 handleMethodArgumentNotValid）
    // 格式和 handleMethodArgumentNotValid 一致：「欄位: 訊息」；body 的欄位用欄位名稱，其他參數用參數名稱
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = exception.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors errors
                        ? errors.getAllErrors().stream()
                                .map(error -> error instanceof FieldError fieldError
                                        ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                                        : error.getDefaultMessage())
                        : result.getResolvableErrors().stream()
                                .map(error -> parameterName(result) + ": " + error.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(toProblemDetail(CommonErrorCode.VALIDATION_FAILED, detail));
    }

    // 參數名稱需要編譯時加 -parameters（spring-boot-starter-parent 預設有開）；拿不到時退回參數位置
    private static String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name != null ? name : "arg" + result.getMethodParameter().getParameterIndex();
    }

    private ProblemDetail toProblemDetail(ErrorCode errorCode, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), detail);
        problemDetail.setProperty("code", errorCode.name());
        return problemDetail;
    }
}
