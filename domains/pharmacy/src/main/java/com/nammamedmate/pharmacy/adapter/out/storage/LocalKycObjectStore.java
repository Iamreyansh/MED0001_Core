package com.nammamedmate.pharmacy.adapter.out.storage;

import com.nammamedmate.pharmacy.application.port.out.KycObjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/stub KYC object store for dev/test. Writes under {@code java.io.tmpdir}/medmate-kyc/.
 * Staging/prod use {@link S3KycObjectStore}. ponytail: story multipart still proxies bytes through
 * the API; migrate to client-side S3 presign PUT later.
 */
@Component
@Profile("!prod & !staging")
public class LocalKycObjectStore implements KycObjectStore {

  private static final Logger log = LoggerFactory.getLogger(LocalKycObjectStore.class);

  private final Path base;

  public LocalKycObjectStore() {
    this(Path.of(System.getProperty("java.io.tmpdir"), "medmate-kyc"));
  }

  /** Protected for testing with a custom base directory. */
  protected LocalKycObjectStore(Path base) {
    this.base = base;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      // All files land directly in `base`; '/' replaced with '-' to keep a flat layout.
      Files.createDirectories(base);
      Path target = base.resolve(key.replace('/', '-'));
      Files.write(target, bytes);
      log.debug("KYC object stored locally: key={} size={}", key, bytes.length);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store KYC object: " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      Path target = base.resolve(key.replace('/', '-'));
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete KYC object: " + key, e);
    }
  }
}
