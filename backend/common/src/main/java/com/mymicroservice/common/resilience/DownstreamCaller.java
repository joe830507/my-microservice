package com.mymicroservice.common.resilience;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.function.Supplier;

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

// 對一個下游服務的呼叫加上熔斷（與冪等呼叫的重試）。
//
// 失敗的定義：連不上、逾時（ResourceAccessException）與 5xx（HttpServerErrorException）。4xx 是業務結果（例如庫存不足、
// 訂單不存在），不算失敗、不重試，原樣丟給呼叫方處理。
//
// 熔斷打開時丟出 CallNotPermittedException：呼叫方要把它當成「下游暫時無法使用，而且這次沒有送出請求」處理。
//
// 重試只用在冪等的呼叫（callIdempotent），而且只重試「很快就知道失敗」的情況：連線被拒／連線逾時，或 502／503／504。
// 讀取逾時不重試：已經等了整個 read-timeout，再來兩次會讓使用者等三倍時間，下游多半也沒恢復。
public final class DownstreamCaller {
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    DownstreamCaller(CircuitBreaker circuitBreaker, Retry retry) {
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    // 只有熔斷：不冪等的呼叫用這個
    public <T> T call(Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(circuitBreaker, call).get();
    }

    // 熔斷 + 重試：每一次嘗試都會經過熔斷，所以熔斷打開之後不會再重試
    public <T> T callIdempotent(Supplier<T> call) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    static boolean isFailure(Throwable throwable) {
        return throwable instanceof ResourceAccessException || throwable instanceof HttpServerErrorException;
    }

    static boolean isRetryable(Throwable throwable) {
        if (throwable instanceof CallNotPermittedException) {
            return false;
        }
        if (throwable instanceof HttpServerErrorException serverError) {
            int status = serverError.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        if (throwable instanceof ResourceAccessException) {
            Throwable cause = throwable.getCause();
            for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
                if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                    return true;
                }
            }
        }
        return false;
    }
}
