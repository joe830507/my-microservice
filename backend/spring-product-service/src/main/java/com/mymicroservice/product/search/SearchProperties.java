package com.mymicroservice.product.search;

import java.net.URI;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.mymicroservice.common.resilience.ResilienceProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// 搜尋索引連線集中設定，方便本機、測試與叢集環境各自覆寫 endpoint。
// query 開頭的設定只套用在使用者請求的搜尋查詢：使用者在等結果，逾時要短，失敗就退回資料庫查詢；
// 索引寫入與重建（批次、背景）維持 client 的預設逾時，不能被這裡的短逾時打斷
@Validated
@ConfigurationProperties(prefix = "search.elasticsearch")
public record SearchProperties(
        @NotNull URI endpoint,

        @DefaultValue("1s") Duration queryConnectTimeout,

        @DefaultValue("3s") Duration queryReadTimeout,

        // 索引是否可查（別名存在、已建完、結構版本相符）的檢查結果快取多久，避免每個搜尋請求都多打一次 ES
        @DefaultValue("5s") Duration readinessCacheTtl,

        // 搜尋查詢的熔斷；搜尋不重試（退回資料庫查詢就是重試），所以不使用 retry 欄位
        @Valid @DefaultValue ResilienceProperties queryResilience) {
}
