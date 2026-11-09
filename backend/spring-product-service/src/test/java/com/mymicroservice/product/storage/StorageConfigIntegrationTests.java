package com.mymicroservice.product.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.config.StorageProperties;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

// 物件儲存的連線設定：S3Client 用 path-style 連到測試容器，能建 bucket、上傳、讀回
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StorageConfigIntegrationTests {

    @Autowired
    private S3Client s3Client;
    @Autowired
    private StorageProperties storageProperties;

    @Test
    void s3Client_canCreateBucketAndReadBackObject() {
        String bucket = storageProperties.bucket();
        try {
            s3Client.createBucket(request -> request.bucket(bucket));
        } catch (BucketAlreadyOwnedByYouException alreadyExists) {
            // 其他測試已經建立過
        }

        assertThat(s3Client.listBuckets().buckets()).extracting(Bucket::name).contains(bucket);

        s3Client.putObject(request -> request.bucket(bucket).key("tmp/probe.txt"), RequestBody.fromString("hello"));
        assertThat(s3Client.getObjectAsBytes(request -> request.bucket(bucket).key("tmp/probe.txt")).asUtf8String())
                .isEqualTo("hello");
    }

    @Test
    void storageProperties_haveDesignedLimits() {
        assertThat(storageProperties.maxSize().toMegabytes()).isEqualTo(5);
        assertThat(storageProperties.allowedContentTypes()).containsExactly("image/jpeg", "image/png", "image/webp");
        assertThat(storageProperties.uploadUrlTtl().toMinutes()).isEqualTo(10);
    }
}
