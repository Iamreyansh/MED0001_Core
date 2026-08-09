package com.nammamedmate.crm.application.port.out;

import com.nammamedmate.crm.domain.AccountAddon;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.PlanSubscriber;
import com.nammamedmate.crm.domain.SaasAddon;
import com.nammamedmate.crm.domain.SaasPlan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SaasPlanStore {

  List<SaasPlan> listActivePlans();

  Optional<SaasPlan> findPlanById(UUID id);

  Optional<SaasPlan> findPlanByName(String name);

  SaasPlan updatePlan(
      UUID id,
      Long priceMonthlyPaise,
      Integer seatLimit,
      Integer invoiceCapMonthly,
      Instant updatedAt);

  long countActiveSubscribers(String planName);

  List<PlanSubscriber> listSubscribers(String planName, int offset, int limit);

  long countModulesForPlan(String planName);

  List<String> moduleCodesForPlan(String planName);

  List<SaasAddon> listActiveAddons();

  Optional<SaasAddon> findAddonById(UUID id);

  long countActiveAccountsWithAddon(UUID addonId);

  long countTotalActiveAccounts();

  Optional<CrmAccount> findAccountById(UUID accountId);

  Optional<CrmAccount> findAccountByPharmacyId(UUID pharmacyId);

  /** Active (non-deleted) CRM accounts for nightly health recompute. */
  List<CrmAccount> listActiveAccounts();

  CrmAccount createAccount(UUID pharmacyId, String planName, String status, Instant now);

  Optional<AccountAddon> findActiveAccountAddon(UUID accountId, UUID addonId);

  void attachAddon(UUID accountId, UUID addonId, Instant effectiveFrom);

  void detachAddon(UUID accountId, UUID addonId, Instant detachedAt);

  List<ModuleMatrixRow> listModuleMatrix();

  boolean planIncludesModule(String planName, String moduleId);
}
