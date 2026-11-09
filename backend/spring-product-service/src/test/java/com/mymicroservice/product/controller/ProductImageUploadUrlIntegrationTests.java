package com.mymicroservice.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.config.StorageProperties;
import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductReq;
import com.mymicroservice.product.service.ProductService;
import com.mymicroservice.product.storage.ObjectStorageTestSupport;

import software.amazon.awssdk.services.s3.S3Client;

// POST /api/v1/products/{productId}/images/upload-url：用回傳的網址實際 PUT 檔案到測試容器的物件儲存
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductImageUploadUrlIntegrationTests {
    private static final UUID ADMIN = UUID.randomUUID();
    // JDK HttpClient 預設會嘗試 h2c 升級；物件儲存是 HTTP/1.1，固定版本避免干擾
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProductService productService;
    @Autowired
    private S3Client s3Client;
    @Autowired
    private StorageProperties storageProperties;

    private UUID productId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
        ObjectStorageTestSupport.ensureBucket(s3Client, storageProperties.bucket());
        productId = productService.addOneNewProduct(new AddOneNewProductReq(
                "SKU-" + UUID.randomUUID(), "Shoe", null, null, null, new BigDecimal("100.00"), null)).id();
    }

    // ---------- 成功：實際上傳 ----------

    @Test
    void uploadUrl_returnsSignedUrlUnderTmpPrefix() throws Exception {
        byte[] file = new byte[1024];

        uploadUrl("image/webp", file.length)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey", startsWith("tmp/products/" + productId + "/")))
                .andExpect(jsonPath("$.objectKey").value(org.hamcrest.Matchers.endsWith(".webp")))
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.requiredHeaders.Content-Type").value("image/webp"))
                .andExpect(jsonPath("$.requiredHeaders.Content-Length").value("1024"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void uploadUrl_putWithRequiredHeaders_storesFileInTmp() throws Exception {
        byte[] file = randomBytes(2048);
        Map<String, Object> res = requestUpload("image/png", file.length);

        HttpResponse<String> put = put((String) res.get("uploadUrl"), "image/png", file);

        assertThat(put.statusCode()).isEqualTo(200);
        String objectKey = (String) res.get("objectKey");
        assertThat(objectKey).endsWith(".png");
        var head = s3Client.headObject(request -> request.bucket(storageProperties.bucket()).key(objectKey));
        assertThat(head.contentLength()).isEqualTo(2048L);
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(s3Client.getObjectAsBytes(request -> request.bucket(storageProperties.bucket()).key(objectKey))
                .asByteArray()).isEqualTo(file);
    }

    @Test
    void uploadUrl_putWithDifferentSize_isRejectedByStorage() throws Exception {
        // 大小簽進網址：申請 1 KB 卻上傳 2 KB，簽章不符
        Map<String, Object> res = requestUpload("image/png", 1024);

        HttpResponse<String> put = put((String) res.get("uploadUrl"), "image/png", randomBytes(2048));

        assertThat(put.statusCode()).isEqualTo(403);
        assertThat(exists((String) res.get("objectKey"))).isFalse();
    }

    @Test
    void uploadUrl_putWithDifferentContentType_isRejectedByStorage() throws Exception {
        Map<String, Object> res = requestUpload("image/png", 1024);

        HttpResponse<String> put = put((String) res.get("uploadUrl"), "text/html", randomBytes(1024));

        assertThat(put.statusCode()).isEqualTo(403);
        assertThat(exists((String) res.get("objectKey"))).isFalse();
    }

    @Test
    void uploadedTmpFile_isNotPubliclyReadable() throws Exception {
        // 還沒登記的檔案（tmp/）不能被外部讀到；登記後搬到 products/ 才開放
        byte[] file = randomBytes(512);
        Map<String, Object> res = requestUpload("image/jpeg", file.length);
        assertThat(put((String) res.get("uploadUrl"), "image/jpeg", file).statusCode()).isEqualTo(200);

        String publicUrl = storageProperties.publicBaseUrl() + "/" + res.get("objectKey");
        HttpResponse<String> anonymousGet = HTTP.send(HttpRequest.newBuilder(URI.create(publicUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(anonymousGet.statusCode()).isEqualTo(403);
    }

    @Test
    void uploadUrl_expiresAfterConfiguredTtl() throws Exception {
        Instant before = Instant.now();
        Map<String, Object> res = requestUpload("image/webp", 100);

        Instant expiresAt = Instant.parse((String) res.get("expiresAt"));
        assertThat(expiresAt).isBetween(before.plus(Duration.ofMinutes(9)), before.plus(Duration.ofMinutes(11)));
    }

    // ---------- 驗證 ----------

    @ParameterizedTest
    @ValueSource(strings = { "image/gif", "image/svg+xml", "text/html", "application/octet-stream" })
    void uploadUrl_contentTypeNotAllowed_returns400(String contentType) throws Exception {
        uploadUrl(contentType, 1024)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_CONTENT_TYPE_NOT_ALLOWED"));
    }

    @Test
    void uploadUrl_contentTypeIsCaseInsensitive() throws Exception {
        uploadUrl("IMAGE/PNG", 1024)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredHeaders.Content-Type").value("image/png"));
    }

    @Test
    void uploadUrl_exactlyMaxSize_isAllowed_oneMoreByte_isRejected() throws Exception {
        long max = 5L * 1024 * 1024;
        uploadUrl("image/png", max).andExpect(status().isOk());
        uploadUrl("image/png", max + 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"contentType\": \"image/png\"}", "{\"contentType\": \"image/png\", \"contentLength\": 0}",
            "{\"contentType\": \"image/png\", \"contentLength\": -1}", "{\"contentType\": \" \", \"contentLength\": 10}" })
    void uploadUrl_invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(post("/api/v1/products/{productId}/images/upload-url", productId)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void uploadUrl_unknownProduct_returns404() throws Exception {
        productId = UUID.randomUUID();

        uploadUrl("image/png", 100)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void uploadUrl_archivedProduct_returns400() throws Exception {
        jdbcTemplate.update("UPDATE product_service.product SET status = 'ARCHIVED' WHERE id = ?", productId);

        uploadUrl("image/png", 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
    }

    // ---------- helpers ----------

    private ResultActions uploadUrl(String contentType, long contentLength) throws Exception {
        return mockMvc.perform(post("/api/v1/products/{productId}/images/upload-url", productId)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\": \"%s\", \"contentLength\": %d}".formatted(contentType, contentLength)));
    }

    private Map<String, Object> requestUpload(String contentType, long contentLength) throws Exception {
        String body = uploadUrl(contentType, contentLength).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$");
    }

    // Content-Length 由 HttpClient 依 body 大小自動帶上（不能手動設定）
    private static HttpResponse<String> put(String url, String contentType, byte[] body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean exists(String objectKey) {
        return ObjectStorageTestSupport.exists(s3Client, storageProperties.bucket(), objectKey);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(size).nextBytes(bytes);
        return bytes;
    }
}
