package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.in.web.AdminAutomationRulesController;
import com.nammamedmate.automation.adapter.out.persistence.InMemoryRateLimitAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcRuleStoreAdapter;
import com.nammamedmate.automation.adapter.out.persistence.StubRuleAuditAdapter;
import com.nammamedmate.automation.application.ActiveRuleCache;
import com.nammamedmate.automation.application.RuleManagementService;
import com.nammamedmate.automation.application.RuleSimulationService;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EvaluateCommand;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActionRegistryPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleAuditPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SeedCatalogPort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.application.port.out.TriggerRegistryPort;
import com.nammamedmate.automation.domain.ActionDefinition;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationRule;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.Guardrails;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.automation.domain.RuleStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CoverageStory002Test {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC);
  private final UUID adminId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal finance =
      new MedmatePrincipal(adminId, AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");

  @Test
  @SuppressWarnings("unchecked")
  void ruleManagementRemainingBranches() {
    RuleStorePort store = mock(RuleStorePort.class);
    TriggerRegistryPort triggers = mock(TriggerRegistryPort.class);
    ActionRegistryPort actions = mock(ActionRegistryPort.class);
    RuleAuditPort audit = mock(RuleAuditPort.class);
    RuleLookupPort lookup = mock(RuleLookupPort.class);
    when(lookup.listActive()).thenReturn(List.of());
    RuleManagementService svc =
        new RuleManagementService(
            store,
            triggers,
            actions,
            audit,
            new ActiveRuleCache(lookup, clock),
            mock(SeedCatalogPort.class),
            clock);

    assertThatThrownBy(() -> svc.list(null, null, null, null, null, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> svc.list(finance, null, null, null, null, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> svc.list(superAdmin, "NOPE", null, null, 0, 0))
        .isInstanceOf(AppException.class);

    when(store.countFiltered(null, null, null)).thenReturn(0L);
    when(store.listFiltered(null, null, null, 0, 20)).thenReturn(List.of());
    assertThat(svc.list(superAdmin, null, null, null, null, null).meta().page()).isEqualTo(1);
    assertThat(svc.list(superAdmin, "  ", null, null, 2, 150).meta().limit()).isEqualTo(100);
    assertThat(svc.list(superAdmin, null, null, null, 0, 0).meta().page()).isEqualTo(1);

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
    when(actions.findById("auto_assign_rider"))
        .thenReturn(
            Optional.of(
                new ActionDefinition(
                    "auto_assign_rider",
                    "DISPATCH",
                    "n",
                    "d",
                    List.of(),
                    List.of(),
                    false,
                    false,
                    null)));
    when(store.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
    svc.create(superAdmin, "  N1  ", null, "order_unassigned", null, null, null, null, null);

    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    " ",
                    null,
                    "order_unassigned",
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    "N2",
                    null,
                    "order_unassigned",
                    null,
                    List.of(new ConditionSpec("a", " ", "x")),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    null,
                    null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    "N3",
                    null,
                    "order_unassigned",
                    null,
                    List.of(),
                    List.of(new ActionSpec(" ", Map.of(), false)),
                    null,
                    null))
        .isInstanceOf(AppException.class);

    UUID id = UUID.randomUUID();
    Instant fired = Instant.parse("2026-07-24T09:00:00Z");
    AutomationRule active =
        new AutomationRule(
            id,
            "Old",
            "d",
            "order_unassigned",
            "DISPATCH",
            Map.of(),
            List.of(),
            List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
            Guardrails.NONE,
            RuleStatus.ACTIVE,
            1,
            fired,
            false,
            0,
            null,
            clock.instant(),
            clock.instant());
    when(store.findById(id)).thenReturn(Optional.of(active));
    when(store.findByNameIgnoreCase("Taken")).thenReturn(Optional.of(active));
    assertThatThrownBy(
            () -> svc.patch(superAdmin, id, "Taken", null, null, null, null, null, null, null))
        .isInstanceOf(AppException.class);
    Map<String, Object> patched =
        svc.patch(
            superAdmin, id, null, "nd", null, Map.of("a", 1), null, null, Guardrails.NONE, 60);
    assertThat(patched.get("status_reset_reason")).isEqualTo("RULE_EDITED");

    assertThatThrownBy(() -> svc.setStatus(superAdmin, id, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> svc.setStatus(superAdmin, id, "NOPE"))
        .isInstanceOf(AppException.class);

    when(store.findByNameIgnoreCase("Old (Copy)")).thenReturn(Optional.of(active));
    assertThatThrownBy(() -> svc.duplicate(superAdmin, id)).isInstanceOf(AppException.class);

    Map<String, Object> detail = svc.get(superAdmin, id);
    assertThat(detail.get("created_by")).isEqualTo("SYSTEM");
    assertThat(detail.get("last_fired_at")).isEqualTo(fired.toString());

    AutomationRule inactive =
        new AutomationRule(
            id,
            "Old",
            "d",
            "order_unassigned",
            "DISPATCH",
            Map.of(),
            List.of(),
            List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
            Guardrails.NONE,
            RuleStatus.INACTIVE,
            0,
            null,
            false,
            300,
            adminId,
            clock.instant(),
            clock.instant());
    when(store.findById(id)).thenReturn(Optional.of(inactive));
    Map<String, Object> noReset =
        svc.patch(
            superAdmin,
            id,
            null,
            null,
            "order_unassigned",
            null,
            List.of(new ConditionSpec("z", "eq", "1")),
            List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
            null,
            null);
    assertThat(noReset.containsKey("status_reset_reason")).isFalse();

    when(store.findById(UUID.fromString("99999999-9999-4999-8999-999999999999")))
        .thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> svc.get(superAdmin, UUID.fromString("99999999-9999-4999-8999-999999999999")))
        .isInstanceOf(AppException.class);

    when(store.countFiltered(null, null, null)).thenReturn(1L);
    when(store.listFiltered(null, null, null, 0, 20)).thenReturn(List.of(inactive, active));
    assertThat(svc.list(superAdmin, null, null, null, 1, 20).data().get("rules")).isNotNull();
    when(store.countFiltered(" ", " ", " ")).thenReturn(0L);
    when(store.listFiltered(" ", " ", " ", 0, 20)).thenReturn(List.of());
    svc.list(superAdmin, " ", " ", " ", 1, 20);

    when(store.findByNameIgnoreCase("Unique")).thenReturn(Optional.empty());
    svc.patch(superAdmin, id, "Unique", null, null, null, null, null, null, null);

    when(store.findById(id)).thenReturn(Optional.of(active));
    when(store.countByStatus(RuleStatus.ACTIVE)).thenReturn(1L);
    svc.setStatus(superAdmin, id, "ACTIVE");

    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    "N4",
                    null,
                    "order_unassigned",
                    null,
                    java.util.Arrays.asList((ConditionSpec) null),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    null,
                    null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    "N5",
                    null,
                    "order_unassigned",
                    null,
                    List.of(),
                    java.util.Arrays.asList((ActionSpec) null),
                    null,
                    null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    null,
                    null,
                    "order_unassigned",
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    "N4b",
                    null,
                    "order_unassigned",
                    null,
                    List.of(new ConditionSpec("a", null, 1)),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    null,
                    null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.create(
                    superAdmin,
                    "N5b",
                    null,
                    "order_unassigned",
                    null,
                    List.of(),
                    List.of(new ActionSpec(null, Map.of(), false)),
                    null,
                    null))
        .isInstanceOf(AppException.class);

    when(store.findById(id)).thenReturn(Optional.of(inactive));
    svc.delete(superAdmin, id, false);

    RuleManagementService mocked = mock(RuleManagementService.class);
    when(mocked.setStatus(any(), any(), any())).thenReturn(Map.of("new_status", "INACTIVE"));
    AdminAutomationRulesController ctrl =
        new AdminAutomationRulesController(mocked, mock(RuleSimulationService.class));
    assertThat(ctrl.setStatus(superAdmin, id, null).success()).isTrue();
  }

  @Test
  void domainAndRateLimitAndAudit() {
    assertThat(RuleStatus.parse(null)).isNull();
    assertThat(RuleStatus.parse("  ")).isNull();
    assertThat(
            new AutomationRule(
                    UUID.randomUUID(),
                    "n",
                    null,
                    "t",
                    "ORDERS",
                    null,
                    null,
                    null,
                    null,
                    RuleStatus.INACTIVE,
                    0,
                    null,
                    false,
                    0,
                    null,
                    Instant.EPOCH,
                    Instant.EPOCH)
                .dedupWindowSeconds())
        .isEqualTo(300);
    assertThat(new RuleSnapshot(UUID.randomUUID(), "t", null, null, 10, null, null).status())
        .isEqualTo(RuleStatus.ACTIVE);
    assertThat(Guardrails.fromMap(null)).isEqualTo(Guardrails.NONE);
    assertThat(Guardrails.fromMap(Map.of())).isEqualTo(Guardrails.NONE);
    assertThat(
            Guardrails.fromMap(
                    Map.of(
                        "rate_limit",
                        Map.of("max_fires", "2", "per_minutes", "3"),
                        "value_cap",
                        "9",
                        "require_approval_above",
                        "null"))
                .rateLimit()
                .maxFires())
        .isEqualTo(2);
    assertThat(
            Guardrails.fromMap(Map.of("value_cap", "  ", "require_approval_above", "10"))
                .requireApprovalAbove())
        .isEqualTo(10L);
    assertThat(Guardrails.fromMap(Map.of("rate_limit", Map.of("max_fires", 1))).rateLimit())
        .isNull();
    assertThat(Guardrails.fromMap(Map.of("rate_limit", Map.of("per_minutes", 1))).rateLimit())
        .isNull();

    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-24T10:00:00Z"));
    Clock moving =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    InMemoryRateLimitAdapter lim = new InMemoryRateLimitAdapter(moving);
    UUID rid = UUID.randomUUID();
    assertThat(lim.tryAcquire(rid, 0, 60)).isTrue();
    assertThat(lim.tryAcquire(rid, 1, 0)).isTrue();
    assertThat(lim.tryAcquire(rid, 1, 1)).isTrue();
    now.set(now.get().plus(Duration.ofMinutes(2)));
    assertThat(lim.tryAcquire(rid, 1, 1)).isTrue();

    StubRuleAuditAdapter audit = new StubRuleAuditAdapter();
    audit.log(null, null, null, null);
    assertThat(audit.entries()).hasSize(1);
  }

  @Test
  void engineNullRateLimitAndRecordFire() {
    KillSwitchPort kill = mock(KillSwitchPort.class);
    TriggerEventStorePort events = mock(TriggerEventStorePort.class);
    RuleLookupPort rules = mock(RuleLookupPort.class);
    DedupPort dedup = mock(DedupPort.class);
    ActionExecutorPort actions = mock(ActionExecutorPort.class);
    ActivityLogPort activity = mock(ActivityLogPort.class);
    RuleStorePort store = mock(RuleStorePort.class);
    when(kill.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(events.insert(anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(UUID.randomUUID());
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);
    when(actions.execute(anyString(), anyMap(), anyMap())).thenReturn(UUID.randomUUID());

    UUID ruleId = UUID.randomUUID();
    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ruleId,
                    "order_unassigned",
                    List.of(),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    300,
                    RuleStatus.ACTIVE,
                    null)));

    RulesEngineService svc =
        new RulesEngineService(
            kill,
            events,
            rules,
            new ActiveRuleCache(rules, clock),
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activity,
            (a, b, c) -> true,
            store,
            clock);
    Map<String, Object> out =
        svc.evaluate(
            new EvaluateCommand(
                ruleId,
                new EventPayload("order_unassigned", "ORDER", UUID.randomUUID(), Map.of(), null),
                false,
                null,
                null,
                null));
    assertThat(out.get("outcome")).isEqualTo("RULE_FIRED");
    verify(store).recordFire(eq(ruleId), any());

    when(rules.findById(ruleId))
        .thenReturn(
            Optional.of(
                new RuleSnapshot(
                    ruleId,
                    "order_unassigned",
                    List.of(),
                    List.of(new ActionSpec("auto_assign_rider", Map.of(), false)),
                    300,
                    RuleStatus.ACTIVE,
                    new Guardrails(new Guardrails.RateLimit(5, 60), null, null))));
    Map<String, Object> dry =
        svc.evaluate(
            new EvaluateCommand(
                ruleId,
                new EventPayload("order_unassigned", "ORDER", UUID.randomUUID(), Map.of(), null),
                true,
                null,
                null,
                null));
    assertThat(dry.get("outcome")).isEqualTo("RULE_FIRED");
    Map<String, Object> okLimit =
        svc.evaluate(
            new EvaluateCommand(
                ruleId,
                new EventPayload("order_unassigned", "ORDER", UUID.randomUUID(), Map.of(), null),
                false,
                null,
                null,
                null));
    assertThat(okLimit.get("outcome")).isEqualTo("RULE_FIRED");
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcStoreEdgeBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper boom =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("boom");
          }
        };
    ResultSet rs = mock(ResultSet.class);
    UUID id = UUID.randomUUID();
    UUID createdBy = UUID.randomUUID();
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("n");
    when(rs.getString("description")).thenReturn(null);
    when(rs.getString("trigger_id")).thenReturn("order_unassigned");
    when(rs.getString("trigger_category")).thenReturn("DISPATCH");
    when(rs.getString("trigger_params")).thenReturn(null);
    when(rs.getString("conditions"))
        .thenReturn("[{\"field\":\"a\",\"operator\":\"eq\",\"value\":1}]");
    when(rs.getString("actions"))
        .thenReturn("[{\"action_id\":\"x\",\"params\":{\"k\":1},\"parallel\":\"true\"}]");
    when(rs.getString("guardrails")).thenReturn("{}");
    when(rs.getString("status")).thenReturn("INACTIVE");
    when(rs.getInt("fire_count")).thenReturn(0);
    when(rs.getTimestamp("last_fired_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T09:00:00Z")));
    when(rs.getBoolean("is_seed_rule")).thenReturn(false);
    when(rs.getInt("dedup_window_seconds")).thenReturn(300);
    when(rs.getObject("created_by")).thenReturn(createdBy);
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(rs.getTimestamp("updated_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);

    JdbcRuleStoreAdapter store = new JdbcRuleStoreAdapter(jdbc, boom);
    AutomationRule rule = store.findById(id).orElseThrow();
    assertThat(rule.createdBy()).isEqualTo(createdBy);
    assertThat(rule.actions().getFirst().parallel()).isTrue();
    store.insert(rule);
    store.update(rule);
    assertThat(store.countByStatus(RuleStatus.ACTIVE)).isZero();
    assertThat(store.countFiltered(null, null, null)).isZero();

    when(rs.getString("conditions")).thenReturn("[{\"field\":null,\"operator\":null,\"value\":1}]");
    when(rs.getString("actions"))
        .thenReturn("[{\"action_id\":null,\"params\":\"x\",\"parallel\":false}]");
    assertThat(store.findById(id).orElseThrow().conditions().getFirst().field()).isNull();
    when(rs.getString("conditions")).thenReturn("");
    when(rs.getString("actions")).thenReturn("   ");
    assertThat(store.findById(id).orElseThrow().conditions()).isEmpty();
    when(rs.getString("conditions")).thenReturn(null);
    assertThat(store.findById(id).orElseThrow().conditions()).isEmpty();

    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(2)).mapRow(rs, 0)));
    assertThat(store.countFiltered("ACTIVE", "DISPATCH", "n")).isZero();
    assertThat(store.listFiltered("ACTIVE", "DISPATCH", "n", 0, 10)).hasSize(1);
    assertThat(store.countFiltered(" ", " ", " ")).isZero();

    when(rs.getString("actions"))
        .thenReturn("[{\"action_id\":\"x\",\"params\":{\"k\":1},\"parallel\":true}]");
    assertThat(store.findById(id).orElseThrow().actions().getFirst().parallel()).isTrue();
    when(rs.getString("trigger_params")).thenReturn("  ");
    when(rs.getString("guardrails")).thenReturn("  ");
    store.findById(id);

    RuleManagementService rules = mock(RuleManagementService.class);
    var patch =
        new AdminAutomationRulesController.PatchRuleRequest(
            "n",
            "d",
            "order_unassigned",
            Map.of(),
            List.of(new AdminAutomationRulesController.ConditionDto("a", "eq", 1)),
            List.of(new AdminAutomationRulesController.ActionDto("auto_assign_rider", null, null)),
            new AdminAutomationRulesController.GuardrailsDto(
                new AdminAutomationRulesController.RateLimitDto(null, 1), null, null),
            300);
    when(rules.patch(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "INACTIVE"));
    when(rules.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("status", "INACTIVE"));
    AdminAutomationRulesController ctrl =
        new AdminAutomationRulesController(rules, mock(RuleSimulationService.class));
    assertThat(ctrl.patch(superAdmin, id, patch).success()).isTrue();
    assertThat(
            ctrl.create(
                    superAdmin,
                    new AdminAutomationRulesController.CreateRuleRequest(
                        "n",
                        null,
                        "t",
                        null,
                        null,
                        null,
                        new AdminAutomationRulesController.GuardrailsDto(
                            new AdminAutomationRulesController.RateLimitDto(1, 60), 1L, 2L),
                        null))
                .getStatusCode()
                .is2xxSuccessful())
        .isTrue();
    assertThat(
            ctrl.create(
                    superAdmin,
                    new AdminAutomationRulesController.CreateRuleRequest(
                        "n2",
                        null,
                        "t",
                        null,
                        null,
                        null,
                        new AdminAutomationRulesController.GuardrailsDto(null, 1L, 2L),
                        null))
                .getStatusCode()
                .is2xxSuccessful())
        .isTrue();
    assertThat(
            ctrl.create(
                    superAdmin,
                    new AdminAutomationRulesController.CreateRuleRequest(
                        "n3",
                        null,
                        "t",
                        null,
                        null,
                        null,
                        new AdminAutomationRulesController.GuardrailsDto(
                            new AdminAutomationRulesController.RateLimitDto(1, null), null, null),
                        null))
                .getStatusCode()
                .is2xxSuccessful())
        .isTrue();
  }
}
