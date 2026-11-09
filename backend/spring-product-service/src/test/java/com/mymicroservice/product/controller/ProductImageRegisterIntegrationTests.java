package com.mymicroservice.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

// POST /api/v1/products/{productId}/images：登記已上傳的檔案
// 完整流程都是真的：申請上傳網址 → 用 HttpClient PUT → 登記 → 匿名讀取公開網址
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductImageRegisterIntegrationTests {
    private static final UUID ADMIN = UUID.randomUUID();
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
        productId = createProduct();
    }

    // ---------- 成功 ----------

    @Test
    void register_uploadedFile_movesToProductsAndIsPubliclyReadable() throws Exception {
        byte[] file = randomBytes(4096);
        String tmpKey = upload(productId, "image/webp", file);

        String body = register(productId, tmpKey, "running shoe")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.altText").value("running shoe"))
                .andExpect(jsonPath("$.isPrimary").value(true))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andReturn().getResponse().getContentAsString();

        // 對外網址 = 公開前綴 + products/...；tmp/ 已經刪除
        String objectKey = tmpKey.substring("tmp/".length());
        String url = JsonPath.read(body, "$.url");
        assertThat(url).isEqualTo(storageProperties.publicBaseUrl() + "/" + objectKey);
        assertThat(exists(tmpKey)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT object_key FROM product_service.product_image WHERE product_id = ?",
                String.class, productId)).isEqualTo(objectKey);

        // 不帶任何憑證就能讀到同樣的檔案
        HttpResponse<byte[]> anonymousGet = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(anonymousGet.statusCode()).isEqualTo(200);
        assertThat(anonymousGet.body()).isEqualTo(file);
    }

    @Test
    void register_secondImage_isNotPrimaryAndListedWithUrls() throws Exception {
        register(productId, upload(productId, "image/png", randomBytes(100)), null).andExpect(status().isCreated());
        register(productId, upload(productId, "image/jpeg", randomBytes(200)), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPrimary").value(false))
                .andExpect(jsonPath("$.sortOrder").value(1));

        mockMvc.perform(get("/api/v1/products/{productId}/images", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(2))
                .andExpect(jsonPath("$.images[0].url").value(org.hamcrest.Matchers.endsWith(".png")))
                .andExpect(jsonPath("$.images[1].url").value(org.hamcrest.Matchers.endsWith(".jpg")))
                .andExpect(jsonPath("$.images[0].url").value(
                        org.hamcrest.Matchers.startsWith(storageProperties.publicBaseUrl() + "/products/" + productId + "/")));
    }

    // ---------- key 不合法 ----------

    @Test
    void register_sameFileTwice_secondReturns400() throws Exception {
        String tmpKey = upload(productId, "image/png", randomBytes(100));
        register(productId, tmpKey, null).andExpect(status().isCreated());

        register(productId, tmpKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_NOT_FOUND"));
    }

    @Test
    void register_otherProductsFile_returns400AndLeavesFile() throws Exception {
        // 不能把別的商品的檔案登記到自己名下
        UUID otherProduct = createProduct();
        String otherKey = upload(otherProduct, "image/png", randomBytes(100));

        register(productId, otherKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_OBJECT_KEY_INVALID"));
        assertThat(exists(otherKey)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "products/%s/01a1bfeb-4351-7899-9b58-142decdf484e.png",
            "tmp/products/%s/01a1bfeb-4351-7899-9b58-142decdf484e.gif",
            "tmp/products/%s/../01a1bfeb-4351-7899-9b58-142decdf484e.png",
            "tmp/products/%s/01a1bfeb-4351-7899-9b58-142decdf484e.png/x.png",
            "tmp/products/%s/not-a-uuid.png",
            "https://example.com/a.png" })
    void register_malformedKey_returns400(String template) throws Exception {
        register(productId, template.formatted(productId), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_OBJECT_KEY_INVALID"));
    }

    @Test
    void register_keyNeverUploaded_returns400() throws Exception {
        register(productId, "tmp/products/" + productId + "/" + UUID.randomUUID() + ".png", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_NOT_FOUND"));
    }

    // ---------- 實際檔案不符合限制（繞過上傳網址直接放進物件儲存） ----------

    @Test
    void register_storedContentTypeNotAllowed_returns400() throws Exception {
        String tmpKey = "tmp/products/" + productId + "/" + UUID.randomUUID() + ".png";
        putDirectly(tmpKey, "image/gif", randomBytes(100));

        register(productId, tmpKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_NOT_FOUND"));
        assertThat(countImages()).isZero();
    }

    @Test
    void register_storedFileTooLarge_returns400() throws Exception {
        String tmpKey = "tmp/products/" + productId + "/" + UUID.randomUUID() + ".png";
        putDirectly(tmpKey, "image/png", new byte[5 * 1024 * 1024 + 1]);

        register(productId, tmpKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_UPLOAD_NOT_FOUND"));
    }

    // ---------- 商品、驗證 ----------

    @Test
    void register_archivedProduct_returns400() throws Exception {
        String tmpKey = upload(productId, "image/png", randomBytes(100));
        jdbcTemplate.update("UPDATE product_service.product SET status = 'ARCHIVED' WHERE id = ?", productId);

        register(productId, tmpKey, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));
        assertThat(exists(tmpKey)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"objectKey\": \" \"}", "{\"url\": \"https://example.com/a.png\"}" })
    void register_invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(post("/api/v1/products/{productId}/images", productId)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- helpers ----------

    private UUID createProduct() {
        return productService.addOneNewProduct(new AddOneNewProductReq(
                "SKU-" + UUID.randomUUID(), "Shoe", null, null, null, new BigDecimal("100.00"), null)).id();
    }

    // 申請上傳網址並實際 PUT，回傳 tmp 的 object key
    private String upload(UUID product, String contentType, byte[] file) throws Exception {
        String body = mockMvc.perform(post("/api/v1/products/{productId}/images/upload-url", product)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\": \"%s\", \"contentLength\": %d}".formatted(contentType, file.length)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> res = JsonPath.read(body, "$");
        HttpResponse<String> put = HTTP.send(HttpRequest.newBuilder(URI.create((String) res.get("uploadUrl")))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(file))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);
        return (String) res.get("objectKey");
    }

    private void putDirectly(String objectKey, String contentType, byte[] file) {
        s3Client.putObject(request -> request.bucket(storageProperties.bucket()).key(objectKey).contentType(contentType),
                RequestBody.fromBytes(file));
    }

    private ResultActions register(UUID product, String objectKey, String altText) throws Exception {
        String body = altText == null ? "{\"objectKey\": \"%s\"}".formatted(objectKey)
                : "{\"objectKey\": \"%s\", \"altText\": \"%s\"}".formatted(objectKey, altText);
        return mockMvc.perform(post("/api/v1/products/{productId}/images", product)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private boolean exists(String objectKey) {
        return ObjectStorageTestSupport.exists(s3Client, storageProperties.bucket(), objectKey);
    }

    private int countImages() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product_service.product_image", Integer.class);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(size).nextBytes(bytes);
        return bytes;
    }
}
