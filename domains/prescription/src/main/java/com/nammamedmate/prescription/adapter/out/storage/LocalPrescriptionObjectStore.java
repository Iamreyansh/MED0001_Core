package com.nammamedmate.prescription.adapter.out.storage;

import com.nammamedmate.prescription.application.port.out.PrescriptionObjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local/stub prescription object store under {@code java.io.tmpdir}/medmate-prescriptions/.
 * Staging/prod use {@link S3PrescriptionObjectStore}. ponytail: story multipart still proxies bytes
 * through the API.
 */
@Component
@Profile("!prod & !staging")
public class LocalPrescriptionObjectStore implements PrescriptionObjectStore {

  private static final Logger log = LoggerFactory.getLogger(LocalPrescriptionObjectStore.class);

  private final Path base;

  public LocalPrescriptionObjectStore() {
    this(Path.of(System.getProperty("java.io.tmpdir"), "medmate-prescriptions"));
  }

  /** Protected for testing with a custom base directory. */
  protected LocalPrescriptionObjectStore(Path base) {
    this.base = base;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      Files.createDirectories(base);
      Path target = base.resolve(key.replace('/', '-'));
      Files.write(target, bytes);
      log.debug("Prescription object stored locally: key={} size={}", key, bytes.length);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store prescription object: " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      Path target = base.resolve(key.replace('/', '-'));
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete prescription object: " + key, e);
    }
  }
}
