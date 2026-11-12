package com.mymicroservice.product.search;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.resilience.DownstreamCallers;
import com.mymicroservice.product.dto.product.getProductList.ProductQueryRequest;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.product.exception.ProductErrorCode;
import com.mymicroservice.product.repository.ProductRepository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.json.JsonData;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

// 前台關鍵字搜尋：Elasticsearch 只負責找出符合的商品 id 與排序，商品內容與主圖仍從 PostgreSQL 取
// （價格、狀態以資料庫為準，索引暫時過期也不會顯示錯的價格或已下架的商品）。
//
// 回傳 Optional.empty() 代表「這次不能用 ES」，呼叫端要改用資料庫查詢：
//   連線失敗、逾時、5xx、429（熔斷計入失敗）、熔斷打開、索引還沒建好（別名不存在、未建完、結構版本不符）。
// ES 回其他 4xx 是我們的查詢寫錯了，不退回（那會把 bug 藏起來），直接丟例外變成 500。
// 不重試：退回資料庫查詢就是重試，使用者不必多等。
@Slf4j
@Component
public class ProductSearchService {
    // ES 預設只能翻到前 10000 筆（from + size）；不調高 max_result_window，超過直接回 400
    static final int MAX_RESULT_WINDOW = 10_000;
    static final String BREAKER_NAME = "elasticsearch-search";

    private final ElasticsearchClient client;
    private final ProductRepository productRepository;
    private final SearchProperties properties;
    private final CircuitBreaker breaker;

    // null = 還沒檢查過
    private volatile Readiness readiness;

    public ProductSearchService(@Qualifier(SearchConfig.QUERY_CLIENT) ElasticsearchClient client,
            ProductRepository productRepository, SearchProperties properties, DownstreamCallers callers) {
        this.client = client;
        this.productRepository = productRepository;
        this.properties = properties;
        this.breaker = callers.circuitBreakers().circuitBreaker(BREAKER_NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.queryResilience().slidingWindowSize())
                .minimumNumberOfCalls(properties.queryResilience().minimumNumberOfCalls())
                .failureRateThreshold(properties.queryResilience().failureRateThreshold())
                .waitDurationInOpenState(properties.queryResilience().waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.queryResilience().permittedCallsInHalfOpenState())
                .recordException(ProductSearchService::isFailure)
                .build());
    }

    // categoryIds：已展開子孫的分類 id；沒有分類條件時為 null
    public Optional<Page<Product>> search(ProductQueryRequest request, Set<UUID> categoryIds) {
        Pageable pageable = request.toPageable();
        long from = pageable.getOffset();
        if (from + pageable.getPageSize() > MAX_RESULT_WINDOW) {
            throw new AppException(ProductErrorCode.SEARCH_RESULT_WINDOW_EXCEEDED);
        }

        Hits hits;
        try {
            hits = Metrics.timer("search.es.duration").recordCallable(
                    () -> breaker.executeCallable(() -> query(request, categoryIds, (int) from)));
        } catch (CallNotPermittedException e) {
            return fallback("breaker_open", e);
        } catch (IndexNotReadyException e) {
            return fallback("index_not_ready", e);
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 快取期間內索引被刪掉：讓下一次請求重新檢查，這次先退回資料庫查詢
                readiness = null;
                return fallback("index_not_ready", e);
            }
            if (isFailure(e)) {
                return fallback(reason(e), e);
            }
            // 4xx：查詢本身有問題，不是 ES 的狀況，不退回
            log.error("Elasticsearch rejected the product search query: status={}", e.status(), e);
            throw new IllegalStateException("Elasticsearch rejected the product search query", e);
        } catch (Exception e) {
            return fallback(reason(e), e);
        }
        return Optional.of(hydrate(hits, pageable));
    }

    private Optional<Page<Product>> fallback(String reason, Exception cause) {
        Metrics.counter("search.fallback", "reason", reason).increment();
        if ("error".equals(reason)) {
            log.warn("Elasticsearch search failed, falling back to the database: {}", cause.toString());
        } else {
            log.debug("Elasticsearch search not used, falling back to the database: reason={}", reason, cause);
        }
        return Optional.empty();
    }

    // 依 ES 的排序取回商品；資料庫找不到或已不是上架中的（索引比資料庫舊）直接丟掉，
    // 所以一頁可能少於 size、總數可能略多，這是索引延遲期間可接受的誤差，不為了補滿一頁再查一次
    private Page<Product> hydrate(Hits hits, Pageable pageable) {
        List<UUID> ids = hits.ids().stream().map(UUID::fromString).toList();
        Map<UUID, Product> productsById = productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Product> products = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Product product = productsById.get(id);
            if (product != null && product.getStatus() == ProductStatus.ACTIVE) {
                products.add(product);
            }
        }
        int stale = ids.size() - products.size();
        if (stale > 0) {
            Metrics.counter("search.stale_hits").increment(stale);
        }
        return new PageImpl<>(products, pageable, hits.total());
    }

    private Hits query(ProductQueryRequest request, Set<UUID> categoryIds, int from) throws IOException {
        requireReady();
        String keyword = request.keyword().trim();
        SearchResponse<Void> response = client.search(s -> s
                .index(ProductIndexManager.ALIAS)
                .from(from)
                .size(request.size())
                .source(source -> source.fetch(false))
                .trackTotalHits(total -> total.enabled(true))
                .query(buildQuery(keyword, request, categoryIds))
                .sort(buildSort(request)), Void.class);
        List<String> ids = response.hits().hits().stream().map(Hit::id).toList();
        long total = response.hits().total() == null ? ids.size() : response.hits().total().value();
        return new Hits(ids, total);
    }

    // 前台只看得到上架中的商品，所以固定過濾 ACTIVE；其餘條件和資料庫查詢的語意一致
    // 關鍵字各子句的權重只是起點，相關性測試用具體案例驗證，不合理就調並記在實作紀錄
    private Query buildQuery(String keyword, ProductQueryRequest request, Set<UUID> categoryIds) {
        return Query.of(q -> q.bool(bool -> {
            bool.must(must -> must.bool(any -> any
                    .should(s -> s.term(t -> t.field("sku").value(keyword).boost(5f)))
                    .should(s -> s.match(m -> m.field("name").query(keyword).boost(3f)))
                    .should(s -> s.matchPhrase(m -> m.field("name.char").query(keyword).boost(2f)))
                    .should(s -> s.match(m -> m.field("brand").query(keyword).boost(2f)))
                    .should(s -> s.match(m -> m.field("description").query(keyword).boost(0.5f)))
                    .minimumShouldMatch("1")));
            bool.filter(f -> f.term(t -> t.field("status").value(ProductStatus.ACTIVE.name())));
            if (categoryIds != null) {
                List<FieldValue> values = categoryIds.stream().map(id -> FieldValue.of(id.toString())).toList();
                bool.filter(f -> f.terms(t -> t.field("categoryId").terms(v -> v.value(values))));
            }
            if (request.minPrice() != null || request.maxPrice() != null) {
                bool.filter(f -> f.range(r -> r.number(n -> {
                    n.field("price");
                    if (request.minPrice() != null) {
                        n.gte(request.minPrice().doubleValue());
                    }
                    if (request.maxPrice() != null) {
                        n.lte(request.maxPrice().doubleValue());
                    }
                    return n;
                })));
            }
            return bool;
        }));
    }

    // 最後一定要有 id 當 tie-break，否則分數或欄位值相同的商品在翻頁時會重複或漏掉
    private List<SortOptions> buildSort(ProductQueryRequest request) {
        SortOrder order = request.direction().isAscending() ? SortOrder.Asc : SortOrder.Desc;
        String sortBy = request.sortBy() == null ? "relevance" : request.sortBy();
        SortOptions idAscending = SortOptions.of(s -> s.field(f -> f.field("id").order(SortOrder.Asc)));
        List<SortOptions> sorts = new ArrayList<>();
        switch (sortBy) {
            case "relevance" -> {
                sorts.add(SortOptions.of(s -> s.score(score -> score.order(SortOrder.Desc))));
                sorts.add(SortOptions.of(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc))));
            }
            // name.raw 依位元組排序，和資料庫依 collation 排序的結果不完全相同，可接受
            case "name" -> sorts.add(SortOptions.of(s -> s.field(f -> f.field("name.raw").order(order))));
            default -> sorts.add(SortOptions.of(s -> s.field(f -> f.field(sortBy).order(order))));
        }
        sorts.add(idAscending);
        return sorts;
    }

    // 別名存在、_meta.built 為 true、結構版本和程式一致才可查；結果快取一小段時間，不是每個請求都多打一次 ES
    private void requireReady() throws IOException {
        long now = System.nanoTime();
        Readiness cached = readiness;
        if (cached != null && now - cached.checkedAtNanos() < properties.readinessCacheTtl().toNanos()) {
            if (!cached.ready()) {
                throw new IndexNotReadyException();
            }
            return;
        }
        boolean ready = isIndexReady();
        readiness = new Readiness(ready, now);
        if (!ready) {
            throw new IndexNotReadyException();
        }
    }

    private boolean isIndexReady() throws IOException {
        GetMappingResponse mappings;
        try {
            mappings = client.indices().getMapping(g -> g.index(ProductIndexManager.ALIAS));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw e;
        }
        return mappings.mappings().values().stream().anyMatch(index -> {
            Map<String, JsonData> meta = index.mappings().meta();
            return meta != null && meta.get("built") != null && meta.get("schemaVersion") != null
                    && Boolean.TRUE.equals(meta.get("built").to(Boolean.class))
                    && Integer.valueOf(ProductIndexManager.SCHEMA_VERSION).equals(meta.get("schemaVersion").to(Integer.class));
        });
    }

    // 連不上、逾時、5xx、429 才算 ES 的失敗（熔斷計數）；索引沒建好是正常的暫時狀態，4xx 是我們自己的問題
    static boolean isFailure(Throwable throwable) {
        if (throwable instanceof IndexNotReadyException) {
            return false;
        }
        if (throwable instanceof ElasticsearchException e) {
            return e.status() >= 500 || e.status() == 429;
        }
        return true;
    }

    static String reason(Throwable throwable) {
        for (int depth = 0; throwable != null && depth < 10; depth++, throwable = throwable.getCause()) {
            if (throwable instanceof TimeoutException || throwable instanceof SocketTimeoutException
                    || throwable.getClass().getSimpleName().contains("Timeout")) {
                return "timeout";
            }
        }
        return "error";
    }

    private record Hits(List<String> ids, long total) {
    }

    private record Readiness(boolean ready, long checkedAtNanos) {
    }

    // 內部用：索引還不能查，呼叫端退回資料庫查詢；不算 ES 失敗
    static final class IndexNotReadyException extends RuntimeException {
        IndexNotReadyException() {
            super("product search index is not ready", null, false, false);
        }
    }
}
