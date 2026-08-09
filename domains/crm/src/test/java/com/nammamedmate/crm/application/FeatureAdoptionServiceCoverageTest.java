package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmModuleNudgeOutboxPort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.application.port.out.SaasPlanStore;
import com.nammamedmate.crm.domain.CrmAccount;
import com.nammamedmate.crm.domain.ModuleMatrixRow;
import com.nammamedmate.crm.domain.PlanNames;
import com.nammamedmate.crm.domain.SaasPlan;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeatureAdoptionServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate MONTH = LocalDate.of(2026, 7, 1);

  @Mock SaasModuleUsageStore usage;
  @Mock SaasPlanStore plans;
  @Mock CrmModuleNudgeOutboxPort nudgeOutbox;
  FeatureAdoptionService service;
  MedmatePrincipal ops;
  MedmatePrincipal finance;

  @BeforeEach
  void setUp() {
    service =
        new FeatureAdoptionService(usage, plans, nudgeOutbox, Clock.fixed(NOW, ZoneOffset.UTC));
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
  }

  @Test
  void listModulesBlankFiltersAscSortAndAuth() {
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_z", "Zeta", "Z", "CORE", List.of(PlanNames.FREE)),
                new ModuleMatrixRow(
                    Ids.newId(), "mod_a", "Alpha", "A", "CORE", List.of(PlanNames.FREE))));
    when(usage.countEligibleAccounts(any())).thenReturn(10L);
    when(usage.countAccountsUsing(any(), eq(MONTH))).thenReturn(3L, 1L);

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "s");
    Map<String, Object> data = service.listModules(superAdmin, "  ", " ", "module", "asc");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> modules = (List<Map<String, Object>>) data.get("modules");
    assertThat(modules.getFirst()).containsEntry("module_name", "Alpha");

    when(usage.countAccountsUsing(any(), eq(MONTH))).thenReturn(3L, 1L);
    Map<String, Object> filteredOut = service.listModules(ops, null, "ENTERPRISE", "  ", null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> none = (List<Map<String, Object>>) filteredOut.get("modules");
    assertThat(none).isEmpty();

    when(usage.countAccountsUsing(any(), eq(MONTH))).thenReturn(3L, 1L);
    Map<String, Object> byPct = service.listModules(ops, null, null, "", "asc");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pctSorted = (List<Map<String, Object>>) byPct.get("modules");
    assertThat(((Number) pctSorted.getFirst().get("adoption_pct")).doubleValue())
        .isLessThanOrEqualTo(((Number) pctSorted.get(1).get("adoption_pct")).doubleValue());

    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, Ids.newId(), TokenScope.FULL, "p");
    assertThatThrownBy(() -> service.listModules(owner, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.toggleModule(null, Ids.newId(), "m", true, "r"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(usage.findModuleById("mod_billing"))
        .thenReturn(
            Optional.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("FREE"))));
    when(plans.findAccountById(any()))
        .thenReturn(
            Optional.of(new CrmAccount(Ids.newId(), Ids.newId(), PlanNames.FREE, "ACTIVE", NOW)));
    when(usage.upsertOverride(any(), any(), eq(true), any(), any(), any()))
        .thenAnswer(
            inv ->
                new com.nammamedmate.crm.domain.AccountModuleOverride(
                    Ids.newId(),
                    inv.getArgument(0),
                    inv.getArgument(1),
                    true,
                    inv.getArgument(3),
                    inv.getArgument(4),
                    inv.getArgument(5)));
    assertThat(service.toggleModule(superAdmin, Ids.newId(), "mod_billing", true, "ok"))
        .containsEntry("override", true);
  }

  @Test
  void toggleNullReasonAndGetModuleNotFound() {
    assertThatThrownBy(() -> service.toggleModule(ops, Ids.newId(), "mod_x", true, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(usage.findModuleById("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.getModule(ops, "missing"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("MODULE_NOT_FOUND");
  }

  @Test
  void nudgeBlankChannelAndUsageSummarySkipsIneligible() {
    when(usage.findModuleById("mod_billing"))
        .thenReturn(
            Optional.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("FREE"))));
    when(usage.listNudgeTargetAccountIds(eq("mod_billing"), any()))
        .thenReturn(List.of(Ids.newId()));
    Map<String, Object> nudged = service.nudgeIneligible(ops, "mod_billing", "  ");
    assertThat(nudged).containsEntry("channel", "EMAIL");

    UUID accountId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    when(plans.findAccountById(accountId))
        .thenReturn(
            Optional.of(new CrmAccount(accountId, pharmacyId, PlanNames.FREE, "ACTIVE", NOW)));
    when(plans.findPlanByName(PlanNames.FREE))
        .thenReturn(
            Optional.of(new SaasPlan(Ids.newId(), PlanNames.FREE, 0, 1, 100, true, false, NOW)));
    when(plans.moduleCodesForPlan(PlanNames.FREE)).thenReturn(List.of("BILLING"));
    when(usage.countModulesUsedSince(eq(accountId), any())).thenReturn(0);
    when(usage.listAccountUsageMonth(eq(accountId), eq(MONTH))).thenReturn(List.of());
    when(usage.listModuleMatrix())
        .thenReturn(
            List.of(
                new ModuleMatrixRow(
                    Ids.newId(), "mod_billing", "Billing", "BILLING", "CORE", List.of("FREE")),
                new ModuleMatrixRow(
                    Ids.newId(),
                    "mod_offers",
                    "Offers",
                    "OFFERS",
                    "ADVANCED",
                    List.of("RETAIL_PRO"))));
    when(usage.findOverride(accountId, "mod_offers")).thenReturn(Optional.empty());
    when(usage.countActiveStaff(pharmacyId)).thenReturn(0L);
    when(usage.listActiveStaffNames(pharmacyId)).thenReturn(List.of());
    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(0L);
    when(usage.pharmacyName(pharmacyId)).thenReturn("P");
    when(usage.maxLastActive(accountId)).thenReturn(null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events =
        (List<Map<String, Object>>) service.usageSummary(ops, accountId).get("module_event_counts");
    assertThat(events).hasSize(1);
    assertThat(events.getFirst()).containsEntry("module", "BILLING");

    assertThat(FeatureAdoptionService.lowestTier(null)).isEqualTo(PlanNames.FREE);
  }
}
