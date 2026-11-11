package com.mymicroservice.order.clients;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.resilience.DownstreamCaller;
import com.mymicroservice.common.resilience.DownstreamCallers;
import com.mymicroservice.order.exception.OrderErrorCode;
import com.mymicroservice.order.properties.DownstreamServicesProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;

// 呼叫 product-service 的批次查詢，取下單需要的商品資料（名稱、價格快照與上架狀態）
// 回應、請求的類別由呼叫方自己定義，不依賴 product-service 的程式碼
@Slf4j
@Component
public class ProductClient {
    private final RestClient restClient;
    private final DownstreamCaller caller;

    public ProductClient(@Qualifier("productRestClient") RestClient restClient, DownstreamCallers callers,
            DownstreamServicesProperties properties) {
        this.restClient = restClient;
        this.caller = callers.create("product-service", properties.resilience());
    }

    // 不存在的 id 不會出現在結果中，由呼叫方比對
    // 連不上、逾時、5xx、熔斷打開 → 503 PRODUCT_SERVICE_UNAVAILABLE；4xx 代表這邊送錯，不處理，交給全域的 500
    // 查詢是冪等的：連線失敗與 502／503／504 會重試；熔斷打開時不呼叫（直接失敗，不等逾時）
    public List<Product> findByIds(Collection<UUID> ids) {
        try {
            BatchResponse response = caller.callIdempotent(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/products/batch").queryParam("ids", ids.toArray())
                            .build())
                    .retrieve()
                    .body(BatchResponse.class));
            return response == null || response.products() == null ? List.of() : response.products();
        } catch (CallNotPermittedException exception) {
            log.warn("product-service circuit breaker is open, request not sent");
            throw new AppException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            log.warn("product-service unavailable: {}", exception.getMessage());
            throw new AppException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    // status 用字串：product-service 新增狀態時這邊不會無法解析
    public record Product(UUID id, String sku, String name, BigDecimal price, String status) {
        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }

    private record BatchResponse(List<Product> products) {
    }
}
