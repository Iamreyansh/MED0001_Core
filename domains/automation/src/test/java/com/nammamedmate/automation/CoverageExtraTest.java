package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.in.web.InternalRulesEvaluateController;
import com.nammamedmate.automation.adapter.out.executor.StubActionExecutor;
import com.nammamedmate.automation.adapter.out.persistence.InMemoryDedupAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcActionRegistryAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcKillSwitchAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcTriggerEventStore;
import com.nammamedmate.automation.adapter.out.persistence.JdbcTriggerRegistryAdapter;
import com.nammamedmate.automation.application.ActiveRuleCache;
import com.nammamedmate.automation.application.InternalAutomationAuth;
import com.nammamedmate.automation.application.RulesEngineService;
import com.nammamedmate.automation.application.RulesEngineService.EvaluateCommand;
import com.nammamedmate.automation.application.RulesEngineService.EventPayload;
import com.nammamedmate.automation.application.WorkflowEngineService;
import com.nammamedmate.automation.application.port.out.ActionExecutorPort;
import com.nammamedmate.automation.application.port.out.ActivityLogPort;
import com.nammamedmate.automation.application.port.out.DedupPort;
import com.nammamedmate.automation.application.port.out.KillSwitchPort;
import com.nammamedmate.automation.application.port.out.RuleLookupPort;
import com.nammamedmate.automation.application.port.out.TriggerEventStorePort;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.ConditionEvaluator;
import com.nammamedmate.automation.domain.ConditionSpec;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
import com.nammamedmate.kernel.error.AppException;
import java.sql.Array;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverageExtraTest {

  @Mock KillSwitchPort killSwitch;
  @Mock TriggerEventStorePort events;
  @Mock RuleLookupPort rules;
  @Mock DedupPort dedup;
  @Mock ActionExecutorPort actions;
  @Mock ActivityLogPort activityLog;
  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock Array sqlArray;

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T08:30:00Z"), ZoneOffset.UTC);
  private final ObjectMapper om = new ObjectMapper();

  @Test
  void engineResolveBranches() {
    when(events.insert(anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(UUID.randomUUID());
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    UUID ruleId = UUID.randomUUID();
    RuleSnapshot snap = new RuleSnapshot(ruleId, "order_placed", List.of(), List.of(), 120);
    when(rules.findById(ruleId)).thenReturn(Optional.of(snap));
    when(rules.listActive()).thenReturn(List.of());
    when(dedup.isDuplicate(any(), any(), any())).thenReturn(false);

    ActiveRuleCache cache = new ActiveRuleCache(rules, clock);
    RulesEngineService svc =
        new RulesEngineService(
            killSwitch,
            events,
            rules,
            cache,
            dedup,
            new ConditionEvaluator(clock),
            actions,
            activityLog,
            (rid, max, per) -> true,
            null,
            clock);

    // null event
    assertThatThrownBy(
            () -> svc.evaluate(new EvaluateCommand(ruleId, null, false, null, null, null)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                svc.evaluate(
                    new EvaluateCommand(
                        ruleId,
                        new EventPayload(null, "ORDER", UUID.randomUUID(), Map.of(), null),
                        false,
                        null,
                        null,
                        null)))
        .isInstanceOf(AppException.class);

    // findById path (not cache)
    Map<String, Object> data =
        svc.evaluate(
            new EvaluateCommand(
                ruleId,
                new EventPayload(
                    "order_placed", "ORDER", UUID.randomUUID(), Map.of(), Instant.now()),
                true,
                null,
                null,
                120));
    assertThat(data.get("conditions_met")).isEqualTo(true);

    // conditions null + actions present; ruleId null
    data =
        svc.evaluate(
            new EvaluateCommand(
                null,
                new EventPayload("order_placed", "ORDER", UUID.randomUUID(), Map.of(), null),
                true,
                null,
                List.of(new ActionSpec("send_notification", Map.of(), false)),
                null));
    assertThat(data.get("outcome")).isEqualTo("RULE_FIRED");

    // actions null + conditions present
    data =
        svc.evaluate(
            new EvaluateCommand(
                UUID.randomUUID(),
                new EventPayload("order_placed", "ORDER", UUID.randomUUID(), Map.of("a", 1), null),
                true,
                List.of(new ConditionSpec("a", "eq", 1)),
                null,
                10));
    assertThat(data.get("conditions_met")).isEqualTo(true);
  }

  @Test
  void conditionEvaluatorRemainingBranches() {
    ConditionEvaluator ev = new ConditionEvaluator(clock);
    assertThat(ev.evaluate(java.util.Collections.singletonList(null), Map.of()).met()).isFalse();
    assertThat(ev.evaluate(List.of(new ConditionSpec("x", "  ", 1)), Map.of()).met()).isFalse();
    assertThat(ev.evaluate(List.of(new ConditionSpec("x", "eq", null)), Map.of()).met()).isTrue();
    assertThat(
            ev.evaluate(List.of(new ConditionSpec("amount", "amount_lt", 5)), Map.of("amount", 1))
                .met())
        .isTrue();
    assertThat(
            ev.evaluate(
                    List.of(new ConditionSpec("z", "zone_in", new LinkedHashSet<>(List.of("A")))),
                    Map.of("z", "A"))
                .met())
        .isTrue();
    assertThat(
            ev.evaluate(
                    List.of(new ConditionSpec("z", "zone_in", new Object[] {"A", "B"})),
                    Map.of("z", "B"))
                .met())
        .isTrue();
    assertThat(ev.evaluate(List.of(new ConditionSpec("z", "zone_in", "A")), Map.of("z", "A")).met())
        .isTrue();
    assertThat(
            ev.evaluate(List.of(new ConditionSpec("z", "zone_in", null)), Map.of("z", "A")).met())
        .isFalse();
    assertThat(
            ev.evaluate(
                    List.of(
                        new ConditionSpec("t", "time_of_day_between", List.of("08:00", "09:00"))),
                    Map.of("fired_at", Instant.parse("2026-07-24T08:30:00Z")))
                .met())
        .isTrue();
    assertThat(
            ev.evaluate(
                    List.of(
                        new ConditionSpec("t", "time_of_day_between", List.of("08:00", "09:00"))),
                    Map.of("fired_at", "not-an-instant"))
                .met())
        .isTrue(); // falls back to clock 08:30
    assertThat(ConditionEvaluator.resolve(Map.of("x", "1"), " ")).isNull();
    assertThat(ConditionEvaluator.resolve(Map.of("b", "L", "a", "notmap"), "a.b")).isEqualTo("L");
    assertThat(ConditionEvaluator.resolve(Map.of("order", Map.of()), "order.zone_id")).isNull();
    assertThat(
            ev.evaluate(List.of(new ConditionSpec("x", "amount_gt", null)), Map.of("x", 1)).met())
        .isFalse();
    assertThat(
            ev.evaluate(List.of(new ConditionSpec("p", "not_eq", "OK")), Map.of("p", "OK")).met())
        .isFalse();
    assertThat(ev.evaluate(List.of(new ConditionSpec("n", "amount_lt", 5)), Map.of("n", 5)).met())
        .isFalse();
    assertThat(
            ev.evaluate(List.of(new ConditionSpec("n", "amount_gt", "5")), Map.of("n", "10")).met())
        .isTrue();
    assertThat(
            ev.evaluate(
                    List.of(new ConditionSpec("dow", "day_of_week_in", List.of("FRIDAY"))),
                    Map.of("dow", "FRIDAY"))
                .met())
        .isTrue();
    assertThat(
            ev.evaluate(
                    List.of(
                        new ConditionSpec("t", "time_of_day_between", List.of("09:00", "10:00"))),
                    Map.of())
                .met())
        .isFalse(); // clock 08:30 before window
    assertThat(
            ev.evaluate(
                    List.of(
                        new ConditionSpec("t", "time_of_day_between", List.of("07:00", "08:00"))),
                    Map.of())
                .met())
        .isFalse(); // clock 08:30 after window
    assertThat(
            ev.evaluate(
                    List.of(
                        new ConditionSpec("t", "time_of_day_between", List.of("22:00", "06:00"))),
                    Map.of("fired_at", "2026-07-24T05:00:00Z"))
                .met())
        .isTrue(); // overnight morning side
  }

  @Test
  void jdbcMappersInvoked() throws Exception {
    when(sqlArray.getArray()).thenReturn(new Object[] {"zone_in"});
    when(rs.getString(1)).thenReturn("ACTIVE");
    when(rs.getString("trigger_id")).thenReturn("t1");
    when(rs.getString("category")).thenReturn("ORDERS");
    when(rs.getString("name")).thenReturn("n");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("parameters_schema")).thenReturn("");
    when(rs.getArray("available_conditions")).thenReturn(sqlArray);
    when(rs.getArray("available_context_vars")).thenReturn(sqlArray);
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getString("action_id")).thenReturn("a1");
    when(rs.getString("required_params_schema")).thenReturn("");
    when(rs.getString("optional_params_schema")).thenReturn("[]");
    when(rs.getBoolean("is_reversible")).thenReturn(false);
    when(rs.getBoolean("always_require_approval")).thenReturn(false);
    when(rs.getObject("auto_approval_limit_paise")).thenReturn(null);

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(new JdbcKillSwitchAdapter(jdbc).status()).isEqualTo(KillSwitchStatus.ACTIVE);
    assertThat(new JdbcTriggerRegistryAdapter(jdbc, om).listActive(" ")).hasSize(1);
    when(rs.getString("parameters_schema")).thenReturn(null);
    when(rs.getString("required_params_schema")).thenReturn(null);
    when(rs.getString("optional_params_schema")).thenReturn("   ");
    assertThat(new JdbcTriggerRegistryAdapter(jdbc, om).listActive(null)).hasSize(1);
    assertThat(new JdbcActionRegistryAdapter(jdbc, om).listAll()).hasSize(1);

    ObjectMapper boom =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("boom");
          }
        };
    assertThat(
            new JdbcTriggerEventStore(jdbc, boom)
                .insert("t1", "ORDER", UUID.randomUUID(), null, Instant.now()))
        .isNotNull();
    assertThat(
            new JdbcTriggerEventStore(jdbc, boom)
                .insert("t1", "ORDER", UUID.randomUUID(), Map.of("a", 1), Instant.now()))
        .isNotNull();
  }

  @Test
  void stubsDedupRuleLookupController() {
    when(activityLog.append(anyString(), anyString(), anyString(), anyMap()))
        .thenReturn(UUID.randomUUID());
    assertThat(new StubActionExecutor(activityLog).execute("x", null, Map.of())).isNotNull();

    java.util.concurrent.atomic.AtomicReference<Instant> now =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-07-24T08:00:00Z"));
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
    InMemoryDedupAdapter dedupAdapter = new InMemoryDedupAdapter(moving);
    UUID r = UUID.randomUUID();
    UUID e = UUID.randomUUID();
    dedupAdapter.recordFire(r, e);
    assertThat(dedupAdapter.isDuplicate(r, e, Duration.ofSeconds(300))).isTrue();
    now.set(Instant.parse("2026-07-24T08:10:00Z"));
    assertThat(dedupAdapter.isDuplicate(r, e, Duration.ofSeconds(300))).isFalse();

    assertThat(new RuleSnapshot(r, "t", null, null, 1).conditions()).isEmpty();
    assertThat(new RuleSnapshot(r, "t", null, null, 1).status().name()).isEqualTo("ACTIVE");

    WorkflowEngineService workflowEngine = org.mockito.Mockito.mock(WorkflowEngineService.class);
    InternalRulesEvaluateController c =
        new InternalRulesEvaluateController(
            new RulesEngineService(
                killSwitch,
                events,
                rules,
                new ActiveRuleCache(rules, clock),
                dedup,
                new ConditionEvaluator(clock),
                actions,
                activityLog,
                (rid, max, per) -> true,
                null,
                clock),
            workflowEngine,
            new InternalAutomationAuth("tok"));
    when(events.insert(anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(UUID.randomUUID());
    when(killSwitch.status()).thenReturn(KillSwitchStatus.ACTIVE);
    when(rules.listActive()).thenReturn(List.of());
    var body =
        new InternalRulesEvaluateController.EvaluateRequest(
            UUID.randomUUID(),
            new InternalRulesEvaluateController.EventDto(
                "order_placed", "ORDER", UUID.randomUUID(), Map.of(), Instant.now()),
            false,
            List.of(),
            List.of(new InternalRulesEvaluateController.ActionDto("auto_assign_rider", null, true)),
            300);
    assertThat(c.evaluate("tok", body).success()).isTrue();
  }
}
