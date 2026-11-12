package com.mymicroservice.order.clients;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.mymicroservice.common.resilience.DownstreamCaller;
import com.mymicroservice.common.resilience.DownstreamCallers;
import com.mymicroservice.order.properties.DownstreamServicesProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

// 呼叫 inventory-service 的內部 API；所有呼叫都以 orderId 為冪等鍵，重複呼叫沒關係
@Slf4j
@Component
public class InventoryClient {
    private static final String RESERVATIONS = "/internal/v1/inventory/reservations";

    private final RestClient restClient;
    private final DownstreamCaller caller;

    public InventoryClient(@Qualifier("inventoryRestClient") RestClient restClient, DownstreamCallers callers,
            DownstreamServicesProperties properties) {
        this.restClient = restClient;
        this.caller = callers.create("inventory-service", properties.resilience());
    }

    // 預扣；結果分三種，呼叫方各自處理：
    //   RESERVED      預扣成功
    //   OUT_OF_STOCK  庫存不足或沒有庫存紀錄，確定沒有扣
    //   UNKNOWN       逾時、連不上、5xx：不知道有沒有扣成功，呼叫方要呼叫釋放
    //   UNAVAILABLE   熔斷打開，請求根本沒有送出：確定沒有扣，呼叫方不必釋放
    // 其他 4xx（例如 RESERVATION_MISMATCH）代表這邊送錯或 orderId 重複，丟出例外交給全域的 500
    // 預扣以 orderId 為冪等鍵，重複送出不會多扣：連線失敗與 502／503／504 會重試
    public ReserveResult reserve(UUID orderId, List<Item> items) {
        try {
            caller.callIdempotent(() -> restClient.post()
                    .uri(RESERVATIONS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReserveRequest(orderId, items))
                    .retrieve()
                    .toBodilessEntity());
            return ReserveResult.reserved();
        } catch (CallNotPermittedException exception) {
            log.warn("inventory-service circuit breaker is open, reservation for order {} not sent", orderId);
            return ReserveResult.unavailable();
        } catch (HttpClientErrorException exception) {
            ProblemDetail problem = readProblem(exception);
            String code = problem == null || problem.getProperties() == null ? null
                    : (String) problem.getProperties().get("code");
            if ("INSUFFICIENT_STOCK".equals(code) || "STOCK_NOT_FOUND".equals(code)) {
                return ReserveResult.outOfStock(problem.getDetail());
            }
            throw new IllegalStateException("unexpected reservation response for order " + orderId + ": "
                    + exception.getStatusCode() + " " + code, exception);
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            log.warn("reservation for order {} has unknown result: {}", orderId, exception.getMessage());
            return ReserveResult.unknown();
        }
    }

    // 釋放預扣；回傳是否成功。失敗只記 log，由呼叫方決定之後要不要重試
    // orderId 不存在時 inventory 也回 200，並擋下之後才送達的預扣
    public boolean release(UUID orderId) {
        return post(RESERVATIONS + "/{orderId}/release", orderId);
    }

    // 確認扣除（付款成功）；回傳是否成功
    public boolean confirm(UUID orderId) {
        return post(RESERVATIONS + "/{orderId}/confirm", orderId);
    }

    private boolean post(String path, UUID orderId) {
        try {
            // 釋放、確認都以 orderId 為冪等鍵，可以重試
            caller.callIdempotent(() -> restClient.post().uri(path, orderId).retrieve().toBodilessEntity());
            return true;
        } catch (CallNotPermittedException exception) {
            log.warn("inventory-service circuit breaker is open, call {} for order {} not sent", path, orderId);
            return false;
        } catch (RestClientException exception) {
            log.warn("call {} for order {} failed: {}", path, orderId, exception.getMessage());
            return false;
        }
    }

    private static ProblemDetail readProblem(HttpClientErrorException exception) {
        try {
            return exception.getResponseBodyAs(ProblemDetail.class);
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    public record Item(String sku, int qty) {
    }

    private record ReserveRequest(UUID orderId, List<Item> items) {
    }

    public enum ReserveOutcome {
        RESERVED,
        OUT_OF_STOCK,
        UNKNOWN,
        UNAVAILABLE
    }

    // detail 是 inventory 的錯誤訊息，例如 "insufficient stock: SKU-A, SKU-B."
    public record ReserveResult(ReserveOutcome outcome, String detail) {
        static ReserveResult reserved() {
            return new ReserveResult(ReserveOutcome.RESERVED, null);
        }

        static ReserveResult outOfStock(String detail) {
            return new ReserveResult(ReserveOutcome.OUT_OF_STOCK, detail);
        }

        static ReserveResult unknown() {
            return new ReserveResult(ReserveOutcome.UNKNOWN, null);
        }

        static ReserveResult unavailable() {
            return new ReserveResult(ReserveOutcome.UNAVAILABLE, null);
        }
    }
}
