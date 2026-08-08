package com.nammamedmate.order.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FillRateRefreshSchedulerTest {

  @Mock private PharmacyCandidatePort pharmacies;

  @Test
  void delegatesToPort() {
    when(pharmacies.refreshFillRatesFromDirectoryMetrics()).thenReturn(3);
    FillRateRefreshScheduler scheduler = new FillRateRefreshScheduler(pharmacies);
    scheduler.refreshFillRates();
    verify(pharmacies).refreshFillRatesFromDirectoryMetrics();
  }
}
