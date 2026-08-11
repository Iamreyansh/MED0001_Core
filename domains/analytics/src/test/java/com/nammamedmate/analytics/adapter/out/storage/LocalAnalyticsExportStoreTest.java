package com.nammamedmate.analytics.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAnalyticsExportStoreTest {

  @TempDir Path temp;

  @Test
  void putAndSignedGet() throws Exception {
    LocalAnalyticsExportStore store = new LocalAnalyticsExportStore(temp, "file://" + temp);
    store.put("exports/leaderboards.csv", "a,b\n".getBytes(StandardCharsets.UTF_8), "text/csv");
    assertThat(Files.readString(temp.resolve("exports-leaderboards.csv"))).contains("a,b");
    var signed = store.signedGet("leaderboards.csv", Duration.ofHours(1));
    assertThat(signed.url()).contains("ttl=3600");
    assertThat(signed.expiresAt()).isAfter(java.time.Instant.now());
    var reportSigned = store.signedGet("reports/gmv.csv", Duration.ofDays(7));
    assertThat(reportSigned.url()).contains("reports-gmv.csv");
    assertThat(new LocalAnalyticsExportStore()).isNotNull();
  }

  @Test
  void putFailureWraps() throws Exception {
    Path blocker = temp.resolve("file-not-dir");
    Files.writeString(blocker, "x");
    LocalAnalyticsExportStore bad = new LocalAnalyticsExportStore(blocker, "file://x");
    assertThatThrownBy(() -> bad.put("k", new byte[] {1}, "text/csv"))
        .isInstanceOf(RuntimeException.class);
  }
}
