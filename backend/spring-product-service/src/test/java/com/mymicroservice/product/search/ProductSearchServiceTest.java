package com.mymicroservice.product.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.mymicroservice.product.search.ProductSearchService.IndexNotReadyException;

// 哪些例外算 Elasticsearch 的失敗（熔斷計數）、退回時的原因標籤
class ProductSearchServiceTest {

    @Test
    void connectionProblemsAndTimeoutsCountAsFailures() {
        assertThat(ProductSearchService.isFailure(new ConnectException("refused"))).isTrue();
        assertThat(ProductSearchService.isFailure(new SocketTimeoutException())).isTrue();
        assertThat(ProductSearchService.isFailure(new IOException("broken"))).isTrue();
    }

    @Test
    void anIndexThatIsNotReadyYetIsNotAnElasticsearchFailure() {
        assertThat(ProductSearchService.isFailure(new IndexNotReadyException())).isFalse();
    }

    @Test
    void timeoutsAreLabelledEvenWhenWrapped() {
        assertThat(ProductSearchService.reason(new SocketTimeoutException())).isEqualTo("timeout");
        assertThat(ProductSearchService.reason(new RuntimeException("wrapped", new TimeoutException()))).isEqualTo("timeout");
        assertThat(ProductSearchService.reason(new ConnectException("refused"))).isEqualTo("error");
    }
}
