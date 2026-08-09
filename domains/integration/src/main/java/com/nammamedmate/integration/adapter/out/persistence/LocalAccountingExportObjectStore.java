package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.AccountingExportObjectStore;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * ponytail: local file store for Tally XML until S3 PutObject + presign is wired (ceiling:
 * single-node tmp; upgrade: S3 + PresignedUrlService).
 */
public class LocalAccountingExportObjectStore implements AccountingExportObjectStore {

  private final Path base;
  private final String downloadBaseUrl;

  public LocalAccountingExportObjectStore() {
    this(
        Path.of(System.getProperty("java.io.tmpdir"), "medmate-accounting-exports"),
        "file://" + Path.of(System.getProperty("java.io.tmpdir"), "medmate-accounting-exports"));
  }

  public LocalAccountingExportObjectStore(Path base, String downloadBaseUrl) {
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
      throw new UncheckedIOException("Failed to store accounting export: " + key, e);
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
