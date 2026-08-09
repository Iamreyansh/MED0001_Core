package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.SaasMetricsSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SaasAnalyticsStore {

  record PlanMrrRow(String plan, long mrrPaise, long accountCount) {}

  record CohortRetentionRow(
      LocalDate cohortMonth,
      int monthsSince,
      int startingAccounts,
      int retainedAccounts,
      BigDecimal retentionPct) {}

  Optional<SaasMetricsSnapshot> findMetrics(LocalDate metricMonth);

  void upsertMetrics(SaasMetricsSnapshot snapshot);

  List<SaasMetricsSnapshot> listMetrics(LocalDate fromInclusive, LocalDate toInclusive);

  long sumActiveMrrPaise(String planNameOrNull);

  long countPayingAccounts(String planNameOrNull);

  List<PlanMrrRow> mrrByPlan(String planNameOrNull);

  long sumNewLogoMrrPaise(LocalDate monthStart, LocalDate monthEndExclusive);

  int countNewLogos(LocalDate monthStart, LocalDate monthEndExclusive);

  long sumChurnMrrPaise(Instant periodStart, Instant periodEnd);

  int countChurnedLogos(Instant periodStart, Instant periodEnd);

  long sumExpansionMrrPaise(Instant periodStart, Instant periodEnd);

  int countExpansionAccounts(Instant periodStart, Instant periodEnd);

  long sumContractionMrrPaise(Instant periodStart, Instant periodEnd);

  int countContractionAccounts(Instant periodStart, Instant periodEnd);

  long smSpendPaise(LocalDate monthStart);

  long sumSmSpendPaise(LocalDate fromInclusive, LocalDate toInclusive);

  void replaceCohortRetention(List<CohortRetentionRow> rows);

  List<CohortRetentionRow> listCohortRetention(LocalDate fromInclusive, LocalDate toInclusive);

  /** Live cohort retention grid computed from saas_subscription_cohort. */
  List<CohortRetentionRow> computeLiveCohortRetention(
      LocalDate cohortFrom, LocalDate cohortTo, LocalDate asOfMonth);
}
