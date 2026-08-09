package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.AccountModuleOverride;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.ModuleUsageMonthly;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasModuleUsageStore {

  Optional<ModuleMatrixRow> findModuleById(String moduleId);

  List<ModuleMatrixRow> listModuleMatrix();

  long countEligibleAccounts(String moduleId);

  long countAccountsUsing(String moduleId, LocalDate eventMonth);

  List<EligibleAccountRow> listEligibleNotUsing(String moduleId, LocalDate eventMonth);

  List<AccountUsageRow> listPerAccountUsage(String moduleId, LocalDate eventMonth);

  Optional<AccountModuleOverride> findOverride(UUID accountId, String moduleId);

  AccountModuleOverride upsertOverride(
      UUID accountId,
      String moduleId,
      boolean enabled,
      String reason,
      UUID toggledBy,
      Instant toggledAt);

  void incrementUsage(UUID accountId, String moduleId, LocalDate eventMonth, Instant at);

  List<ModuleUsageMonthly> listAccountUsageMonth(UUID accountId, LocalDate eventMonth);

  Instant maxLastActive(UUID accountId);

  int countModulesUsedSince(UUID accountId, Instant since);

  long countActiveStaff(UUID pharmacyId);

  List<String> listActiveStaffNames(UUID pharmacyId);

  long countInvoicesThisMonth(UUID pharmacyId, Instant monthStart, Instant monthEndExclusive);

  String pharmacyName(UUID pharmacyId);

  List<UUID> listNudgeTargetAccountIds(String moduleId, Instant activeSince);

  record EligibleAccountRow(
      UUID accountId,
      String pharmacyName,
      boolean moduleEnabled,
      int eventCountThisMonth,
      Instant lastActiveAt) {}

  record AccountUsageRow(
      UUID accountId, String pharmacyName, int eventCountThisMonth, Instant lastActiveAt) {}
}
