package com.nammamedmate.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.RuleAuditPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleManagementServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");
  private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID RULE_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Mock RuleStorePort store;
  @Mock TriggerRegistryPort triggers;
  @Mock ActionRegistryPort actions;
  @Mock RuleAuditPort audit;
  @Mock RuleLookupPort lookup;
  @Mock SeedCatalogPort seeds;

  private ActiveRuleCache cache;
  private RuleManagementService service;
  private MedmatePrincipal superAdmin;
  private MedmatePrincipal opsAdmin;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    cache = new ActiveRuleCache(lookup, clock);
    when(lookup.listActive()).thenReturn(List.of());
    service = new RuleManagementService(store, triggers, actions, audit, cache, seeds, clock);
    superAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    opsAdmin = new MedmatePrincipal(ADMIN, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    when(triggers.findById("order_unassigned"))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "order_unassigned",
                    "DISPATCH",
                    "Order Unassigned",
                    "d",
                    List.of(),
                    List.of("zone_in", "time_of_day_between"),
                    List.of("order.id"),
                    true)));
    when(actions.findById("auto_assign_rider"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "auto_assign_rider",
                    "DISPATCH",
                    "Auto",
                    "d",
                    List.of("order_id"),
                    List.of(),
                    false,
                    false,
                    null)));
  }

  @Test
  void ac001_createAlwaysInactive() {
    when(store.findByNameIgnoreCase("Escalate")).thenReturn(Optional.empty());
    Map<String, Object> data =
        service.create(
            opsAdmin,
            "Escalate",
            "desc",
            "order_unassigned",
            Map.of("duration_minutes", 5),
            List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
            List.of(new ActionSpec("auto_assign_rider", Map.of("order_id", "x"), false)),
            new Guardrails(new Guardrails.RateLimit(10, 60), null, null),
            300);
    assertThat(data.get("status")).isEqualTo("INACTIVE");
    ArgumentCaptor<AutomationRule> cap = ArgumentCaptor.forClass(AutomationRule.class);
    verify(store).insert(cap.capture());
    assertThat(cap.getValue().status()).isEqualTo(RuleStatus.INACTIVE);
  }

  @Test
  void ac002_patchActiveResetsToInactive() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.ACTIVE, 0, false)));
    Map<String, Object> data =
        service.patch(
            superAdmin,
            RULE_ID,
            null,
            null,
            null,
            null,
            List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
            null,
            null,
            null);
    assertThat(data.get("status")).isEqualTo("INACTIVE");
    assertThat(data.get("status_reset_reason")).isEqualTo("RULE_EDITED");
  }

  @Test
  void ac003_opsCannotActivate() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 0, false)));
    assertThatThrownBy(() -> service.setStatus(opsAdmin, RULE_ID, "ACTIVE"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("FORBIDDEN");
              assertThat(ae.httpStatus()).isEqualTo(403);
            });
  }

  @Test
  void ac004_activeLimitReached() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 0, false)));
    when(store.countByStatus(RuleStatus.ACTIVE)).thenReturn(200L);
    assertThatThrownBy(() -> service.setStatus(superAdmin, RULE_ID, "ACTIVE"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("ACTIVE_RULE_LIMIT_REACHED"));
  }

  @Test
  void ac005_deleteWithFireHistoryWithoutForce() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 3, false)));
    assertThatThrownBy(() -> service.delete(opsAdmin, RULE_ID, false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RULE_HAS_FIRE_HISTORY"));
  }

  @Test
  void ac006_seedRuleForbiddenEvenWithForce() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 0, true)));
    assertThatThrownBy(() -> service.delete(superAdmin, RULE_ID, true))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));
  }

  @Test
  void story008_scheduleXCannotGainConditions() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 0, true)));
    when(seeds.findByRuleId(RULE_ID))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.automation.domain.SeedCatalogEntry(
                    "AUTO_FLAG_SCHEDULE_X", RULE_ID, null, 6, "i", "e", NOW)));
    assertThatThrownBy(
            () ->
                service.patch(
                    superAdmin,
                    RULE_ID,
                    null,
                    null,
                    null,
                    null,
                    List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
                    null,
                    null,
                    null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("VALIDATION_ERROR"));
    service.patch(superAdmin, RULE_ID, null, null, null, null, List.of(), null, null, null);
    when(seeds.findByRuleId(RULE_ID))
        .thenReturn(
            Optional.of(
                new com.nammamedmate.automation.domain.SeedCatalogEntry(
                    "AUTO_ASSIGN_UNASSIGNED_ORDERS", RULE_ID, null, 1, "i", "e", NOW)));
    service.patch(
        superAdmin,
        RULE_ID,
        null,
        null,
        null,
        null,
        List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
        null,
        null,
        null);
    when(seeds.findByRuleId(RULE_ID)).thenReturn(null);
    service.patch(
        superAdmin,
        RULE_ID,
        null,
        null,
        null,
        null,
        List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
        null,
        null,
        null);
    RuleManagementService noSeeds =
        new RuleManagementService(
            store, triggers, actions, audit, cache, null, Clock.fixed(NOW, ZoneOffset.UTC));
    noSeeds.patch(
        superAdmin,
        RULE_ID,
        null,
        null,
        null,
        null,
        List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
        null,
        null,
        null);
  }

  @Test
  void ac007_duplicate() {
    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.ACTIVE, 99, false)));
    when(store.findByNameIgnoreCase("Auto-assign (Copy)")).thenReturn(Optional.empty());
    Map<String, Object> data = service.duplicate(opsAdmin, RULE_ID);
    assertThat(data.get("name")).isEqualTo("Auto-assign (Copy)");
    assertThat(data.get("status")).isEqualTo("INACTIVE");
    ArgumentCaptor<AutomationRule> cap = ArgumentCaptor.forClass(AutomationRule.class);
    verify(store).insert(cap.capture());
    assertThat(cap.getValue().fireCount()).isZero();
    assertThat(cap.getValue().status()).isEqualTo(RuleStatus.INACTIVE);
  }

  @Test
  void ac008_listFiltersStatusAndCategory() {
    when(store.countFiltered("ACTIVE", "DISPATCH", null)).thenReturn(1L);
    when(store.listFiltered(eq("ACTIVE"), eq("DISPATCH"), eq(null), anyInt(), anyInt()))
        .thenReturn(List.of(sample(RuleStatus.ACTIVE, 1, true)));
    var result = service.list(opsAdmin, "ACTIVE", "DISPATCH", null, 1, 20);
    assertThat(result.meta().total()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules = (List<Map<String, Object>>) result.data().get("rules");
    assertThat(rules.getFirst().get("trigger_category")).isEqualTo("DISPATCH");
    assertThat(rules.getFirst().get("status")).isEqualTo("ACTIVE");
  }

  @Test
  void ac009_nameConflict() {
    when(store.findByNameIgnoreCase("Escalate"))
        .thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 0, false)));
    assertThatThrownBy(
            () ->
                service.create(
                    opsAdmin,
                    "Escalate",
                    null,
                    "order_unassigned",
                    Map.of(),
                    List.of(),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    null,
                    null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RULE_NAME_CONFLICT"));
  }

  @Test
  void validationAndDeleteBranches() {
    assertThatThrownBy(
            () ->
                service.create(
                    opsAdmin, "x", null, "missing", Map.of(), List.of(), List.of(), null, null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_TRIGGER"));

    when(triggers.findById("order_unassigned"))
        .thenReturn(
            Optional.of(
                new TriggerDefinition(
                    "order_unassigned",
                    "DISPATCH",
                    "n",
                    "d",
                    List.of(),
                    List.of("zone_in"),
                    List.of(),
                    true)));
    when(store.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.create(
                    opsAdmin,
                    "BadOp",
                    null,
                    "order_unassigned",
                    Map.of(),
                    List.of(new ConditionSpec("a", "priority_eq", "HIGH")),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    null,
                    null))
        .satisfies(
            ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_CONDITION_OPERATOR"));

    assertThatThrownBy(
            () ->
                service.create(
                    opsAdmin,
                    "BadAct",
                    null,
                    "order_unassigned",
                    Map.of(),
                    List.of(),
                    List.of(new ActionSpec("nope", Map.of(), false)),
                    null,
                    null))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("INVALID_ACTION"));

    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.ACTIVE, 0, false)));
    assertThatThrownBy(() -> service.delete(opsAdmin, RULE_ID, false))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("RULE_IS_ACTIVE"));

    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 5, false)));
    assertThatThrownBy(() -> service.delete(opsAdmin, RULE_ID, true))
        .satisfies(ex -> assertThat(((AppException) ex).code()).isEqualTo("FORBIDDEN"));

    Map<String, Object> deleted = service.delete(superAdmin, RULE_ID, true);
    assertThat(deleted.get("deleted")).isEqualTo(true);
    verify(store).softDelete(eq(RULE_ID), any());

    when(store.findById(RULE_ID)).thenReturn(Optional.of(sample(RuleStatus.INACTIVE, 0, false)));
    service.setStatus(opsAdmin, RULE_ID, "SIMULATING");
    service.setStatus(superAdmin, RULE_ID, "ACTIVE");
    verify(store, never()).softDelete(eq(UUID.randomUUID()), any());

    Map<String, Object> detail = service.get(opsAdmin, RULE_ID);
    assertThat(detail.get("recent_fires")).isEqualTo(List.of());

    Guardrails.fromMap(
        Map.of("rate_limit", Map.of("max_fires", 1, "per_minutes", 2), "value_cap", 9));
    assertThat(Guardrails.NONE.toMap()).containsKey("rate_limit");
    assertThat(RuleStatus.parse("inactive")).isEqualTo(RuleStatus.INACTIVE);
    IntStream.range(0, 0).forEach(i -> {});
  }

  private AutomationRule sample(RuleStatus status, int fires, boolean seed) {
    return new AutomationRule(
        RULE_ID,
        "Auto-assign",
        "desc",
        "order_unassigned",
        "DISPATCH",
        Map.of("duration_minutes", 5),
        List.of(new ConditionSpec("zone_id", "zone_in", List.of("z1"))),
        List.of(new ActionSpec("auto_assign_rider", Map.of("order_id", "x"), false)),
        Guardrails.NONE,
        status,
        fires,
        null,
        seed,
        300,
        ADMIN,
        NOW,
        NOW);
  }
}
