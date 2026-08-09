package com.nammamedmate.payment.adapter.out.persistence;

import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.payment.application.port.out.TaxFilingObjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * ponytail: local file store for GSTR-8/TDS exports until S3 PutObject + presign is wired in
 * apps/api (ceiling: single-node tmp; upgrade: S3 + PresignedUrlService).
 */
public class LocalTaxFilingObjectStore implements TaxFilingObjectStore {

  private final Path base;
  private final String downloadBaseUrl;

  public LocalTaxFilingObjectStore() {
    this(
        Path.of(System.getProperty("java.io.tmpdir"), "medmate-tax-filings"),
        "file://" + Path.of(System.getProperty("java.io.tmpdir"), "medmate-tax-filings"));
  }

  public LocalTaxFilingObjectStore(Path base, String downloadBaseUrl) {
    this.base = base;
    this.downloadBaseUrl = downloadBaseUrl;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      Files.createDirectories(base);
      Path target = base.resolve(sanitize(key));
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store tax filing: " + key, e);
    }
  }

  @Override
  public String createDownloadUrl(String key, Duration ttl) {
    String object = key.startsWith(StorageObjectKeys.EXPORTS) ? key : StorageObjectKeys.export(key);
    return downloadBaseUrl + "/" + sanitize(object) + "?ttl=" + ttl.getSeconds();
  }

  private static String sanitize(String key) {
    return key.replace('/', '-');
  }
}
