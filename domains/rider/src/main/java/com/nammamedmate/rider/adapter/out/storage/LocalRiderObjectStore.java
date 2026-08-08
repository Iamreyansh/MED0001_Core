package com.nammamedmate.rider.adapter.out.storage;

import com.nammamedmate.rider.application.port.out.RiderObjectStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !staging")
public class LocalRiderObjectStore implements RiderObjectStore {

  private final Path base;

  public LocalRiderObjectStore() {
    this(Path.of(System.getProperty("java.io.tmpdir"), "medmate-rider-kyc"));
  }

  public LocalRiderObjectStore(Path base) {
    this.base = base;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    try {
      Files.createDirectories(base);
      Files.write(base.resolve(key.replace('/', '-')), bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to store rider KYC object: " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      Files.deleteIfExists(base.resolve(key.replace('/', '-')));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete rider KYC object: " + key, e);
    }
  }
}
