package com.mymicroservice.payment.clients;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.payment.exception.PaymentErrorCode;

import lombok.extern.slf4j.Slf4j;

// 呼叫 order-service 的內部 API；回應類別由這邊自己定義，不依賴 order-service 的程式碼
@Slf4j
@Component
public class OrderClient {
    private final RestClient restClient;

    public OrderClient(@Qualifier("orderRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // 訂單不存在回 empty；連不上、逾時、5xx → 503 ORDER_SERVICE_UNAVAILABLE
    public Optional<Order> findOrder(UUID orderId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/internal/v1/orders/{id}", orderId)
                    .retrieve()
                    .body(Order.class));
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw exception;
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            log.warn("order-service unavailable: {}", exception.getMessage());
            throw new AppException(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE);
        }
    }

    // 通知付款成功（order-service 那邊是冪等的，重複呼叫沒關係）
    //   NOTIFIED  order-service 已改成 PAID（或本來就是）
    //   REJECTED  訂單已取消（409 ORDER_ALREADY_CANCELLED）或不存在：錢收了但訂單不成立，要退款
    //   FAILED    連不上、逾時、5xx 或其他非預期回應：之後重試
    public NotifyResult notifyPaid(UUID orderId) {
        try {
            restClient.post().uri("/internal/v1/orders/{id}/paid", orderId).retrieve().toBodilessEntity();
            return NotifyResult.NOTIFIED;
        } catch (HttpClientErrorException exception) {
            String code = errorCode(exception);
            if ("ORDER_ALREADY_CANCELLED".equals(code) || exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return NotifyResult.REJECTED;
            }
            log.warn("unexpected response when notifying payment of order {}: {} {}", orderId,
                    exception.getStatusCode(), code);
            return NotifyResult.FAILED;
        } catch (RestClientException exception) {
            log.warn("failed to notify payment of order {}: {}", orderId, exception.getMessage());
            return NotifyResult.FAILED;
        }
    }

    private static String errorCode(HttpClientErrorException exception) {
        try {
            ProblemDetail problem = exception.getResponseBodyAs(ProblemDetail.class);
            return problem == null || problem.getProperties() == null ? null
                    : (String) problem.getProperties().get("code");
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    public enum NotifyResult {
        NOTIFIED,
        REJECTED,
        FAILED
    }

    // status 用字串：order-service 新增狀態時這邊不會無法解析
    public record Order(UUID id, UUID userId, String status, BigDecimal totalAmount) {
        public boolean isPendingPayment() {
            return "PENDING_PAYMENT".equals(status);
        }
    }
}
