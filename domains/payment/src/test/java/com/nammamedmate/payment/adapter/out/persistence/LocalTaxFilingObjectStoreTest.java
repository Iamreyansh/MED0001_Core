package com.nammamedmate.payment.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalTaxFilingObjectStoreTest {

  @TempDir Path temp;

  @Test
  void putAndDownloadUrl() throws Exception {
    LocalTaxFilingObjectStore store = new LocalTaxFilingObjectStore(temp, "file://" + temp);
    store.put("exports/tax/a.json", "hi".getBytes(StandardCharsets.UTF_8), "application/json");
    assertThat(Files.list(temp).count()).isEqualTo(1);
    assertThat(store.createDownloadUrl("tax/a.json", Duration.ofMinutes(5)))
        .contains("file://")
        .contains("ttl=300");
    assertThat(new LocalTaxFilingObjectStore()).isNotNull();
  }

  @Test
  void putThrowsWhenBaseIsFile() throws Exception {
    Path blocker = temp.resolve("blocker");
    Files.writeString(blocker, "x");
    LocalTaxFilingObjectStore bad = new LocalTaxFilingObjectStore(blocker, "file://x");
    assertThatThrownBy(() -> bad.put("k", new byte[] {1}, "text/plain"))
        .isInstanceOf(UncheckedIOException.class);
  }
}
