package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.ChurnSurvey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface SaasRenewalChurnStore {

  void ensureCohort(UUID accountId, LocalDate cohortMonth, Instant now);

  ChurnSurvey insertSurvey(ChurnSurvey survey);

  List<UpcomingRow> listUpcoming(
      Instant now, Instant windowEnd, String riskLevel, UUID csmId, int offset, int limit);

  long countUpcoming(Instant now, Instant windowEnd, String riskLevel, UUID csmId);

  long countRenewing(Instant now, Instant windowEnd);

  long sumMrrAtRiskPaise(Instant now, Instant windowEnd);

  long countChurnedLogos(Instant periodStart, Instant periodEnd);

  long countStartOfPeriodLogos(Instant periodStart, Instant periodEnd);

  long sumMrrChurnedPaise(Instant periodStart, Instant periodEnd);

  long countSavePlaysSince(Instant since);

  List<ReasonCount> churnReasons(Instant periodStart, Instant periodEnd);

  List<ChurnLogRow> churnLog(Instant periodStart, Instant periodEnd, int limit);

  List<CohortRate> cohortChurnRates(LocalDate asOf);

  long countChurnedWithLowAdoption(Instant periodStart, Instant periodEnd);

  long countChurnedWithMissedPayments(Instant periodStart, Instant periodEnd);

  List<UUID> findWinbackDue(Instant dayStart, Instant dayEnd);

  List<AtRiskAlertRow> findAtRiskRenewals(Instant now, Instant windowEnd);

  record UpcomingRow(
      UUID accountId,
      String pharmacyName,
      String plan,
      long mrrPaise,
      LocalDate renewalDate,
      boolean autoRenew,
      double healthScore,
      Instant lastSavePlayAt,
      String assignedCsm) {}

  record ReasonCount(String reason, long count) {}

  record ChurnLogRow(
      UUID accountId,
      String pharmacyName,
      String plan,
      long mrrPaise,
      Instant churnedAt,
      String reason) {}

  record CohortRate(
      String cohortMonth,
      BigDecimal month1ChurnPct,
      BigDecimal month3ChurnPct,
      BigDecimal month6ChurnPct) {}

  record AtRiskAlertRow(UUID accountId, UUID subscriptionId, double healthScore) {}
}
