package com.nammamedmate.integration.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;

class GspTokenRefreshSchedulerTest {

  @Test
  void delegatesAndSwallowsFailures() {
    EinvoiceService service = mock(EinvoiceService.class);
    new GspTokenRefreshScheduler(service).refresh();
    verify(service).refreshTokenIfNeeded();

    doThrow(new AppException("NIC_PORTAL_UNAVAILABLE", "down", 503))
        .when(service)
        .refreshTokenIfNeeded();
    new GspTokenRefreshScheduler(service).refresh();
  }
}
