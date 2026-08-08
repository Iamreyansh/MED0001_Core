package com.nammamedmate.settings.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoggingAuditArchiveAdapterTest {

  @Test
  void archiveLogs() {
    new LoggingAuditArchiveAdapter().archive(Ids.newId(), Instant.parse("2024-01-01T00:00:00Z"));
  }
}
