package com.mymicroservice.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.config.StorageProperties;
import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductReq;
import com.mymicroservice.product.service.ProductService;
import com.mymicroservice.product.storage.ImageStorage;
import com.mymicroservice.product.storage.ObjectStorageTestSupport;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

// DELETE /api/v1/products/{productId}/images/{imageId}：資料列刪除後，物件在 commit 之後一併刪除
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductImageDeleteIntegrationTests {
    private static final UUID ADMIN = UUID.randomUUID();

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
    @MockitoSpyBean
    private ImageStorage imageStorage;

    private UUID productId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
        ObjectStorageTestSupport.ensureBucket(s3Client, storageProperties.bucket());
        productId = productService.addOneNewProduct(new AddOneNewProductReq(
                "SKU-" + UUID.randomUUID(), "Shoe", null, null, null, new BigDecimal("100.00"), null)).id();
    }

    @Test
    void delete_removesRowAndObject() throws Exception {
        Registered image = registerImage();
        assertThat(exists(image.objectKey())).isTrue();

        deleteImage(image.id()).andExpect(status().isNoContent());

        assertThat(countImages()).isZero();
        assertThat(exists(image.objectKey())).isFalse();
    }

    @Test
    void delete_rejected_keepsObject() throws Exception {
        // 上架中的商品不能刪主圖：請求失敗，資料列和物件都還在
        Registered image = registerImage();
        jdbcTemplate.update("UPDATE product_service.product SET status = 'ACTIVE' WHERE id = ?", productId);

        deleteImage(image.id())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_PRIMARY_UNDELETABLE"));

        assertThat(countImages()).isEqualTo(1);
        assertThat(exists(image.objectKey())).isTrue();
    }

    @Test
    void delete_storageFailure_stillReturns204() throws Exception {
        // 資料列已經刪了，物件刪除失敗只記 log，不能讓請求回 500
        Registered image = registerImage();
        doThrow(new RuntimeException("storage down")).when(imageStorage).delete(anyString());

        deleteImage(image.id()).andExpect(status().isNoContent());

        assertThat(countImages()).isZero();
        assertThat(exists(image.objectKey())).isTrue();
    }

    @Test
    void delete_otherImagesObjectsAreKept() throws Exception {
        Registered first = registerImage();
        Registered second = registerImage();

        deleteImage(second.id()).andExpect(status().isNoContent());

        assertThat(exists(first.objectKey())).isTrue();
        assertThat(exists(second.objectKey())).isFalse();
    }

    // ---------- helpers ----------

    private record Registered(UUID id, String objectKey) {
    }

    // 直接放一個檔案到 tmp/ 再透過 API 登記（上傳網址的流程在 ProductImageRegisterIntegrationTests 測過）
    private Registered registerImage() throws Exception {
        String tmpKey = "tmp/products/" + productId + "/" + UUID.randomUUID() + ".png";
        s3Client.putObject(request -> request.bucket(storageProperties.bucket()).key(tmpKey).contentType("image/png"),
                RequestBody.fromBytes(new byte[100]));
        String body = mockMvc.perform(post("/api/v1/products/{productId}/images", productId)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\": \"%s\"}".formatted(tmpKey)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Registered(UUID.fromString(JsonPath.read(body, "$.id")), tmpKey.substring("tmp/".length()));
    }

    private ResultActions deleteImage(UUID imageId) throws Exception {
        return mockMvc.perform(delete("/api/v1/products/{productId}/images/{imageId}", productId, imageId)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN"));
    }

    private boolean exists(String objectKey) {
        return ObjectStorageTestSupport.exists(s3Client, storageProperties.bucket(), objectKey);
    }

    private int countImages() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product_service.product_image", Integer.class);
    }
}
