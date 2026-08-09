package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.AccountHealthScore;
import com.nammamedmate.crm.domain.AccountHealthSnapshot;
import com.nammamedmate.crm.domain.SavePlay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasAccountHealthStore {

  Optional<AccountHealthScore> findByAccountId(UUID accountId);

  void upsert(AccountHealthScore score);

  void upsertSnapshot(AccountHealthSnapshot snapshot);

  SavePlay insertSavePlay(SavePlay play);

  Instant maxSavePlayAt(UUID accountId);

  long countOpenSavePlayAccounts();

  List<AtRiskRow> listAtRisk(String healthBand, int offset, int limit);

  long countAtRisk(String healthBand);

  long sumMrrAtRiskPaise();

  HealthKpis kpis();

  record AtRiskRow(
      UUID accountId,
      String pharmacyName,
      String plan,
      long mrrPaise,
      double overallScore,
      String healthBand,
      LocalDate renewalDate,
      Instant lastSavePlayAt,
      String assignedCsm) {}

  record HealthKpis(
      double avgHealthScore,
      double healthyPct,
      double moderatePct,
      long atRiskCount,
      long churningCount,
      long mrrAtRiskPaise,
      long accountsWithOpenSavePlays,
      Instant computedAt) {}
}
