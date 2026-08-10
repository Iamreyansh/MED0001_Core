package com.nammamedmate.prescription.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class RxComplianceOverdueSchedulerTest {

  @Test
  void scanDelegates() {
    RxComplianceAuditService service = mock(RxComplianceAuditService.class);
    when(service.markOverdueAudits()).thenReturn(2);
    new RxComplianceOverdueScheduler(service).scanOverdueAudits();
    verify(service).markOverdueAudits();
  }

  @Test
  void scanSilentWhenZero() {
    RxComplianceAuditService service = mock(RxComplianceAuditService.class);
    when(service.markOverdueAudits()).thenReturn(0);
    new RxComplianceOverdueScheduler(service).scanOverdueAudits();
    verify(service).markOverdueAudits();
  }
}
