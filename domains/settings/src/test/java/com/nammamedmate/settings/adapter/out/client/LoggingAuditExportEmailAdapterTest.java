package com.nammamedmate.settings.adapter.out.client;

import com.nammamedmate.kernel.id.Ids;
import org.junit.jupiter.api.Test;

class LoggingAuditExportEmailAdapterTest {

  @Test
  void sendExportReadyLogs() {
    LoggingAuditExportEmailAdapter adapter = new LoggingAuditExportEmailAdapter();
    adapter.sendExportReady(Ids.newId(), Ids.newId(), "https://s3.stub/x.csv");
    adapter.sendExportReady(Ids.newId(), Ids.newId(), null);
  }
}
