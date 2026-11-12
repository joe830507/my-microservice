package com.mymicroservice.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.logs.LogRecordBuilder;

// Gateway 的 Boot（4.0.x）管理的 OpenTelemetry 比 observability 模組的 logback appender 需要的舊：
// 版本沒對齊時，記錄含例外的 log 會 NoSuchMethodError，連帶讓指標匯出中斷。pom 的 opentelemetry.version 要維持 1.62.0 以上
class OpenTelemetryVersionAlignmentTest {

    @Test
    void logRecordBuilder_hasSetException() throws Exception {
        assertThat(LogRecordBuilder.class.getMethod("setException", Throwable.class)).isNotNull();
    }
}
