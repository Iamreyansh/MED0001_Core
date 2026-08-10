package com.nammamedmate.prescription.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ScheduleDrugRegisterArchivalSchedulerTest {

  @Test
  void archivesWhenPositive() {
    ScheduleDrugRegisterService service = mock(ScheduleDrugRegisterService.class);
    when(service.archiveExpired()).thenReturn(3);
    new ScheduleDrugRegisterArchivalScheduler(service).archiveExpiredEntries();
    verify(service).archiveExpired();
  }

  @Test
  void silentWhenZero() {
    ScheduleDrugRegisterService service = mock(ScheduleDrugRegisterService.class);
    when(service.archiveExpired()).thenReturn(0);
    new ScheduleDrugRegisterArchivalScheduler(service).archiveExpiredEntries();
    verify(service).archiveExpired();
  }
}
