package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.prescription.adapter.out.client.StubOcrClient;
import com.nammamedmate.prescription.adapter.out.client.SyncOcrJobAdapter;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcCustomerNameAdapter;
import com.nammamedmate.prescription.adapter.out.storage.LocalPrescriptionObjectStore;
import com.nammamedmate.prescription.adapter.out.storage.S3PrescriptionObjectStore;
import com.nammamedmate.prescription.application.PrescriptionService;
import com.nammamedmate.prescription.application.port.out.OcrPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class PrescriptionAdaptersCoverageTest {

  @TempDir Path dir;

  @Test
  void localObjectStore_putAndDelete() throws Exception {
    LocalPrescriptionObjectStore store = new LocalPrescriptionObjectStore(dir) {};
    store.put("prescriptions/a/b.jpg", new byte[] {1, 2, 3}, "image/jpeg");
    assertThat(Files.list(dir).findFirst()).isPresent();
    store.delete("prescriptions/a/b.jpg");
    store.delete("missing");
  }

  @Test
  void localObjectStore_defaultCtor() {
    LocalPrescriptionObjectStore store = new LocalPrescriptionObjectStore();
    store.put("k", new byte[] {9}, "image/png");
    store.delete("k");
  }

  @Test
  void localObjectStore_putFailure() throws Exception {
    Path blockingFile = dir.resolve("not-a-dir");
    Files.writeString(blockingFile, "x");
    LocalPrescriptionObjectStore store =
        new LocalPrescriptionObjectStore(blockingFile.resolve("sub")) {};
    assertThatThrownBy(() -> store.put("k", new byte[] {1}, "image/jpeg"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void localObjectStore_deleteNonEmptyDir() throws Exception {
    Path base = dir.resolve("rx-base");
    Files.createDirectories(base);
    LocalPrescriptionObjectStore store = new LocalPrescriptionObjectStore(base) {};
    Path nested = base.resolve("nested-as-dir");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve("child"), "x");
    assertThatThrownBy(() -> store.delete("nested/as/dir")).isInstanceOf(RuntimeException.class);
  }

  @Test
  void s3ObjectStore_putDelete() {
    S3Client s3 = mock(S3Client.class);
    S3PrescriptionObjectStore store = new S3PrescriptionObjectStore(s3, "bucket");
    store.put("key", new byte[] {1, 2}, "application/pdf");
    verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    store.delete("key");
    verify(s3).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void stubOcr_andNull() {
    StubOcrClient client = new StubOcrClient();
    assertThat(client.extract(new byte[] {1}, "image/jpeg").doctorName()).contains("OCR");
    assertThat(client.extract(null, "image/jpeg")).isNull();
    assertThat(client.extract(new byte[0], "image/jpeg")).isNull();
  }

  @Test
  void syncOcrJob_delegates() {
    PrescriptionService service = mock(PrescriptionService.class);
    SyncOcrJobAdapter job = new SyncOcrJobAdapter(service);
    UUID id = UUID.randomUUID();
    byte[] bytes = {1};
    job.schedule(id, bytes, "image/jpeg");
    verify(service).applyOcr(id, bytes, "image/jpeg");
  }

  @Test
  void jdbcCustomerName() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID id = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of("Ravi"));
    assertThat(new JdbcCustomerNameAdapter(jdbc).findName(id)).contains("Ravi");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(new JdbcCustomerNameAdapter(jdbc).findName(id)).isEmpty();
  }

  @Test
  void ocrResult_nullMeds() {
    OcrPort.OcrResult result = new OcrPort.OcrResult("D", LocalDate.now(), null);
    assertThat(result.medicines()).isEmpty();
  }
}
