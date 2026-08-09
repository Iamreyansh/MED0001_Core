package com.nammamedmate.crm.adapter.out.persistence;

import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * ponytail: local file + file:// / data URL until S3 presign is wired (ceiling: single-node tmp;
 * upgrade: S3 PutObject + PresignedUrlService).
 */
@Component
public class LocalSaasInvoicePdfAdapter implements SaasInvoicePdfPort {

  private final Path base;

  public LocalSaasInvoicePdfAdapter() {
    this(Path.of(System.getProperty("java.io.tmpdir"), "medmate-saas-invoices"));
  }

  LocalSaasInvoicePdfAdapter(Path base) {
    this.base = base;
  }

  @Override
  public void put(String objectKey, byte[] pdfBytes) {
    try {
      Files.createDirectories(base);
      Files.write(base.resolve(sanitize(objectKey)), pdfBytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store SaaS invoice PDF: " + objectKey, e);
    }
  }

  @Override
  public SignedUrl signedGet(String objectKey, Duration ttl) {
    Instant expires = Instant.now().plus(ttl);
    Path file = base.resolve(sanitize(objectKey));
    String url = file.toUri().toString() + "?expires=" + expires.getEpochSecond();
    return new SignedUrl(url, expires);
  }

  static String sanitize(String key) {
    return key == null || key.isBlank() ? "unknown.pdf" : key.replace('/', '-');
  }
}
