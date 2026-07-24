package com.nammamedmate.kernel.storage;

import java.time.Duration;
import java.util.Objects;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public final class S3PresignedUrlService implements PresignedUrlService {

  public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

  private final S3Presigner presigner;
  private final String bucket;

  public S3PresignedUrlService(S3Presigner presigner, String bucket) {
    this.presigner = Objects.requireNonNull(presigner, "presigner");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
  }

  @Override
  public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
    validateKey(key);
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(ttl, "ttl");
    PutObjectRequest objectRequest =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(objectRequest)
            .build();
    String url = presigner.presignPutObject(presignRequest).url().toString();
    return new PresignedUrl(url, key, ttl);
  }

  @Override
  public PresignedUrl createGetUrl(String key, Duration ttl) {
    validateKey(key);
    Objects.requireNonNull(ttl, "ttl");
    GetObjectRequest objectRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(objectRequest)
            .build();
    String url = presigner.presignGetObject(presignRequest).url().toString();
    return new PresignedUrl(url, key, ttl);
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
  }
}
