package com.mymicroservice.product.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

// 測試用：bucket 由測試自己建立（compose 是 object-storage-init 容器），以及檢查物件是否存在
public final class ObjectStorageTestSupport {

    private ObjectStorageTestSupport() {
    }

    public static void ensureBucket(S3Client s3Client, String bucket) {
        try {
            s3Client.createBucket(request -> request.bucket(bucket));
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyExists) {
            // 其他測試已經建立過
        }
    }

    public static boolean exists(S3Client s3Client, String bucket, String objectKey) {
        try {
            s3Client.headObject(request -> request.bucket(bucket).key(objectKey));
            return true;
        } catch (NoSuchKeyException notFound) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw exception;
        }
    }
}
