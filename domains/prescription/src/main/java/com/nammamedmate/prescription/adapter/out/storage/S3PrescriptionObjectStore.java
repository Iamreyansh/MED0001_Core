package com.nammamedmate.prescription.adapter.out.storage;

import com.nammamedmate.prescription.application.port.out.PrescriptionObjectStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@Profile({"prod", "staging"})
public class S3PrescriptionObjectStore implements PrescriptionObjectStore {

  private final S3Client s3;
  private final String bucket;

  public S3PrescriptionObjectStore(S3Client s3, @Value("${medmate.s3.bucket}") String bucket) {
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
  public void delete(String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
