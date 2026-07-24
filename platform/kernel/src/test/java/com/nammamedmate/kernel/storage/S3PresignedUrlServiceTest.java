package com.nammamedmate.kernel.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class S3PresignedUrlServiceTest {

  @Test
  void createsPutAndGetUrls() throws Exception {
    S3Presigner presigner = mock(S3Presigner.class);
    PresignedPutObjectRequest put = mock(PresignedPutObjectRequest.class);
    PresignedGetObjectRequest get = mock(PresignedGetObjectRequest.class);
    when(put.url()).thenReturn(new URL("https://example.com/put"));
    when(get.url()).thenReturn(new URL("https://example.com/get"));
    when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(put);
    when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(get);

    S3PresignedUrlService service = new S3PresignedUrlService(presigner, "bucket");
    assertThat(service.createPutUrl("k", "application/pdf", Duration.ofHours(1)).url())
        .contains("put");
    assertThat(service.createGetUrl("k", Duration.ofHours(1)).url()).contains("get");
    assertThat(S3PresignedUrlService.MAX_UPLOAD_BYTES).isEqualTo(10L * 1024 * 1024);
  }

  @Test
  void validatesArgs() {
    S3Presigner presigner = mock(S3Presigner.class);
    S3PresignedUrlService service = new S3PresignedUrlService(presigner, "b");
    assertThatThrownBy(() -> service.createPutUrl(" ", "t", Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.createGetUrl(null, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new S3PresignedUrlService(null, "b"))
        .isInstanceOf(NullPointerException.class);
  }
}
