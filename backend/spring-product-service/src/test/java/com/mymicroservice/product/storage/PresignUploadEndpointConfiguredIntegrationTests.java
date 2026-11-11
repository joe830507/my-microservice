package com.mymicroservice.product.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.config.StorageProperties;

// 上傳簽章網址是給瀏覽器用的：K8s 上 product-service 連物件儲存用叢集內部的位址，瀏覽器連不到，
// 所以簽章網址要用另外設定的對外位址（storage.upload-endpoint）；product-service 自己操作物件仍然用 endpoint
@SpringBootTest(properties = "storage.upload-endpoint=http://storage.example.test:9443")
@Import(TestcontainersConfiguration.class)
class PresignUploadEndpointConfiguredIntegrationTests {
    @Autowired
    private ImageStorage imageStorage;
    @Autowired
    private StorageProperties properties;

    @Test
    void presignedUrl_usesUploadEndpoint_andIsSignedForThatHost() {
        URI url = URI.create(imageStorage.presignUpload(UUID.randomUUID(), "image/png", 100).uploadUrl());

        assertThat(url.getScheme()).isEqualTo("http");
        assertThat(url.getHost()).isEqualTo("storage.example.test");
        assertThat(url.getPort()).isEqualTo(9443);
        assertThat(url.getPath()).startsWith("/" + properties.bucket() + "/tmp/");
        // 簽章包含 Host（SigV4 的 SignedHeaders 有 host），所以瀏覽器不能改連別的主機
        assertThat(url.getQuery()).contains("X-Amz-Signature=").contains("X-Amz-SignedHeaders=").contains("host");
    }

    @Test
    void clientEndpoint_isUnchanged() {
        assertThat(properties.endpoint().getHost()).isNotEqualTo("storage.example.test");
        assertThat(properties.presignEndpoint().getHost()).isEqualTo("storage.example.test");
    }
}
