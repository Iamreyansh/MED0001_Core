package com.nammamedmate.order.adapter.out.persistence;

import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.order.application.port.out.ExportObjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ponytail: local file store for CSV exports until dedicated S3 export worker + presign path is
 * wired in staging/prod (ceiling: single-node tmp; upgrade: S3 PutObject + PresignedUrlService).
 */
public class LocalExportObjectStore implements ExportObjectStore {

  private final Path base;
  private final String downloadBaseUrl;

  public LocalExportObjectStore() {
    this(
        Path.of(System.getProperty("java.io.tmpdir"), "medmate-order-exports"),
        "file://" + Path.of(System.getProperty("java.io.tmpdir"), "medmate-order-exports"));
  }

  public LocalExportObjectStore(Path base, String downloadBaseUrl) {
    this.base = base;
    this.downloadBaseUrl = downloadBaseUrl;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      Files.createDirectories(base);
      Path target = base.resolve(key.replace('/', '-'));
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store export: " + key, e);
    }
  }

  @Override
  public String createDownloadUrl(String key) {
    String object = key.startsWith(StorageObjectKeys.EXPORTS) ? key : StorageObjectKeys.export(key);
    return downloadBaseUrl + "/" + object.replace('/', '-');
  }
}
