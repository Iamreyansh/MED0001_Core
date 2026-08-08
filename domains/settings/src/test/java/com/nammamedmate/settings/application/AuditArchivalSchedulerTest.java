package com.nammamedmate.settings.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class AuditArchivalSchedulerTest {

  @Test
  void delegates() {
    AuditLogService service = mock(AuditLogService.class);
    new AuditArchivalScheduler(service).archiveOldEntries();
    verify(service).archiveOlderThanTwoYears();
  }
}
