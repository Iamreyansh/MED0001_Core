package com.nammamedmate.prescription.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalComplianceExportStoreTest {

  @TempDir Path temp;

  @Test
  void putAndDownloadUrl() throws Exception {
    LocalComplianceExportStore store = new LocalComplianceExportStore(temp, "file://" + temp);
    store.put("exports/a.csv", "a,b\n".getBytes(), "text/csv");
    assertThat(Files.exists(temp.resolve("exports-a.csv"))).isTrue();
    assertThat(store.createDownloadUrl("exports/a.csv", Duration.ofMinutes(5)))
        .contains("file://")
        .contains("ttl=300");
    assertThat(store.createDownloadUrl("a.csv", Duration.ofSeconds(10))).contains("exports-");
    assertThat(new LocalComplianceExportStore()).isNotNull();
  }

  @Test
  void putFailsOnBlockedPath() throws Exception {
    Path blocker = temp.resolve("blocker");
    Files.writeString(blocker, "x");
    LocalComplianceExportStore bad = new LocalComplianceExportStore(blocker, "file://x");
    assertThatThrownBy(() -> bad.put("k.csv", new byte[] {1}, "text/csv"))
        .isInstanceOf(UncheckedIOException.class);
  }
}
