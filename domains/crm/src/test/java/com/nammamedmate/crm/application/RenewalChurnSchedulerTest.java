package com.nammamedmate.crm.application;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RenewalChurnSchedulerTest {

  @Mock RenewalChurnService renewalChurn;

  @Test
  void delegatesAllJobs() {
    RenewalChurnScheduler scheduler = new RenewalChurnScheduler(renewalChurn);
    scheduler.winback();
    scheduler.atRiskCsm();
    scheduler.monthlyReport();
    verify(renewalChurn).processWinbacks();
    verify(renewalChurn).processAtRiskCsmAlerts();
    verify(renewalChurn).processMonthlyChurnReport();
  }
}
