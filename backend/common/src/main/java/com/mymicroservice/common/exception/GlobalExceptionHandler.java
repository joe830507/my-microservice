package com.mymicroservice.common.exception;

import java.util.stream.Collectors;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
                        ? describe(fieldError)
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
                                        ? describe(fieldError)
                                        : error.getDefaultMessage())
                        : result.getResolvableErrors().stream()
                                .map(error -> parameterName(result) + ": " + error.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(toProblemDetail(CommonErrorCode.VALIDATION_FAILED, detail));
    }

    // 欄位的驗證錯誤：「欄位: 訊息」。型別轉換失敗（bindingFailure，例如列舉欄位傳了不認識的值）的預設訊息是
    // Spring 的原始例外文字，會帶出內部的類別名稱（例如 com.mymicroservice.order.enums.OrderStatus），改成不含內部資訊的固定訊息
    private static String describe(FieldError fieldError) {
        return fieldError.getField() + ": " + (fieldError.isBindingFailure() ? INVALID_VALUE : fieldError.getDefaultMessage());
    }

    private static final String INVALID_VALUE = "invalid value";

    // 路徑變數、查詢參數的型別轉換失敗（例如 /orders/not-a-uuid）：Spring 預設的 detail 是
    // 「Failed to convert 'id' with value: ...」，而且沒有 code 欄位。統一成 VALIDATION_FAILED，不回原始訊息
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = exception.getPropertyName() != null ? exception.getPropertyName() : "parameter";
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getStatus())
                .body(toProblemDetail(CommonErrorCode.VALIDATION_FAILED, name + ": " + INVALID_VALUE));
    }

    // Spring MVC 自己產生的錯誤（路徑不存在、方法不支援、JSON 格式錯誤等）沒有 code 欄位，和其他錯誤格式不一致：
    // 補上以 HTTP 狀態命名的 code（NOT_FOUND、METHOD_NOT_ALLOWED...）
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // ProblemDetail 是 super 內部才建立的（body 傳進來常常是 null），所以要在呼叫 super 之後再補 code
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problemDetail
                && (problemDetail.getProperties() == null || !problemDetail.getProperties().containsKey("code"))) {
            HttpStatus httpStatus = HttpStatus.resolve(statusCode.value());
            problemDetail.setProperty("code", httpStatus != null ? httpStatus.name() : "ERROR");
        }
        return response;
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
