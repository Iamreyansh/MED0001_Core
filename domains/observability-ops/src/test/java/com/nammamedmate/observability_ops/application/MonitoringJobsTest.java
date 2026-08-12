package com.nammamedmate.observability_ops.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class MonitoringJobsTest {

  @Test
  void jobsDelegate() {
    MetricCollectionService collection = mock(MetricCollectionService.class);
    RemediationService remediation = mock(RemediationService.class);
    IncidentService incidents = mock(IncidentService.class);
    new MetricCollectionJob(collection).run();
    new AlertRetentionJob(collection).run();
    new RemediationJob(remediation).run();
    new IncidentAutoCreateJob(incidents).run();
    new PostmortemReminderJob(incidents).run();
    new SloComplianceSnapshotJob(incidents).run();
    verify(collection).collectAndEvaluate();
    verify(collection).purgeOlderThanDays(90);
    verify(remediation).runAutoCycle();
    verify(incidents).runAutoCreate();
    verify(incidents).runPostmortemReminders();
    verify(incidents).runMonthlySloSnapshot();
  }
}
