package com.nammamedmate.pharmacy.adapter.out.storage;

import com.nammamedmate.pharmacy.application.port.out.KycObjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Private-bucket KYC object store for staging/prod. Server-side PutObject while story still uses
 * multipart; replace with client-side {@code PresignedUrlService.createPutUrl} when the contract
 * migrates.
 */
@Component
@Profile({"prod", "staging"})
public class S3KycObjectStore implements KycObjectStore {

  private final S3Client s3;
  private final String bucket;

  public S3KycObjectStore(S3Client s3, @Value("${medmate.s3.bucket}") String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    PutObjectRequest request =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength((long) bytes.length)
            .build();
    s3.putObject(request, RequestBody.fromBytes(bytes));
  }

  @Override
  public byte[] get(String key) {
    try {
      return s3.getObjectAsBytes(
              software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                  .bucket(bucket)
                  .key(key)
                  .build())
          .asByteArray();
    } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
      return null;
    } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
      if (e.statusCode() == 404) {
        return null;
      }
      throw e;
    }
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(
        software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build());
  }
}
