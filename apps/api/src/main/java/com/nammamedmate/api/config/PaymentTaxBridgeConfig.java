package com.nammamedmate.api.config;

import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Composition-root bridge: tax pharmacy GSTIN/PAN + optional S3 filing object store. */
@Configuration
public class PaymentTaxBridgeConfig {

  @Bean
  @Primary
  TaxPharmacyProfilePort jdbcTaxPharmacyProfilePort(JdbcTemplate jdbc) {
    return pharmacyId -> {
      List<TaxPharmacyProfilePort.PharmacyTaxProfile> rows =
          jdbc.query(
              """
              SELECT id, COALESCE(business_name, '') AS business_name,
                     COALESCE(gstin, '') AS gstin, COALESCE(pan_number, '') AS pan
              FROM pharmacies
              WHERE id = ? AND deleted_at IS NULL
              """,
              (rs, i) ->
                  new TaxPharmacyProfilePort.PharmacyTaxProfile(
                      (UUID) rs.getObject("id"),
                      rs.getString("business_name"),
                      rs.getString("gstin"),
                      rs.getString("pan")),
              pharmacyId);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    };
  }

  @Bean
  @Primary
  @ConditionalOnProperty(name = "medmate.s3.tax-filings-enabled", havingValue = "true")
  TaxFilingObjectStore s3TaxFilingObjectStore(
      S3Client s3, PresignedUrlService presigner, @Value("${medmate.s3.bucket}") String bucket) {
    return new S3TaxFilingObjectStore(s3, presigner, bucket);
  }

  static final class S3TaxFilingObjectStore implements TaxFilingObjectStore {
    private final S3Client s3;
    private final PresignedUrlService presigner;
    private final String bucket;

    S3TaxFilingObjectStore(S3Client s3, PresignedUrlService presigner, String bucket) {
      this.s3 = s3;
      this.presigner = presigner;
      this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
      String object =
          key.startsWith(StorageObjectKeys.EXPORTS) ? key : StorageObjectKeys.export(key);
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(object)
              .contentType(contentType == null ? "application/octet-stream" : contentType)
              .build(),
          RequestBody.fromBytes(bytes));
    }

    @Override
    public String createDownloadUrl(String key, Duration ttl) {
      String object =
          key.startsWith(StorageObjectKeys.EXPORTS) ? key : StorageObjectKeys.export(key);
      return presigner.createGetUrl(object, ttl).url();
    }
  }
}
