package com.mymicroservice.product.search;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

// 搜尋索引連線集中設定，方便本機、測試與叢集環境各自覆寫 endpoint。
@Validated
@ConfigurationProperties(prefix = "search.elasticsearch")
public record SearchProperties(@NotNull URI endpoint) {
}
