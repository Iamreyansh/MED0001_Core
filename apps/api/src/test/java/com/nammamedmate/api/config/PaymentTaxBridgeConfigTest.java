package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import software.amazon.awssdk.services.s3.S3Client;

class PaymentTaxBridgeConfigTest {

  @Test
  void pharmacyProfileBridge() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TaxPharmacyProfilePort port = new PaymentTaxBridgeConfig().jdbcTaxPharmacyProfilePort(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(port.find(UUID.randomUUID())).isEmpty();

    UUID id = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenReturn(List.of(new TaxPharmacyProfilePort.PharmacyTaxProfile(id, "P", "G", "PAN")));
    assertThat(port.find(id)).isPresent();
  }

  @Test
  void s3ObjectStorePutAndUrl() {
    S3Client s3 = mock(S3Client.class);
    PresignedUrlService presigner = mock(PresignedUrlService.class);
    when(presigner.createGetUrl(anyString(), any()))
        .thenReturn(
            new PresignedUrlService.PresignedUrl("https://s3/x", "exports/x", Duration.ofHours(1)));
    TaxFilingObjectStore store =
        new PaymentTaxBridgeConfig().s3TaxFilingObjectStore(s3, presigner, "bucket");
    store.put("tax/a.json", new byte[] {1}, "application/json");
    verify(s3)
        .putObject(
            any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
            any(software.amazon.awssdk.core.sync.RequestBody.class));
    assertThat(store.createDownloadUrl("tax/a.json", Duration.ofMinutes(10)))
        .isEqualTo("https://s3/x");
    store.put("exports/tax/b.json", new byte[] {2}, null);
    assertThat(store.createDownloadUrl("exports/tax/b.json", Duration.ofMinutes(1)))
        .isEqualTo("https://s3/x");
  }
}
