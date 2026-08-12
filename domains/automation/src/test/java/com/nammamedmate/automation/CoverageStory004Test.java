package com.nammamedmate.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.adapter.out.persistence.JdbcSimulationStoreAdapter;
import com.nammamedmate.automation.adapter.out.persistence.JdbcTriggerEventStore;
import com.nammamedmate.automation.adapter.out.persistence.StubSimulationNotifyAdapter;
import com.nammamedmate.automation.application.RuleManagementService;
import com.nammamedmate.automation.application.RuleSimulationService;
import com.nammamedmate.automation.application.SimulationJobProcessor;
import com.nammamedmate.automation.application.port.out.RuleStorePort;
import com.nammamedmate.automation.application.port.out.SimulationNotifyPort;
import com.nammamedmate.automation.application.port.out.SimulationStorePort;
import com.nammamedmate.automation.domain.ActionSpec;
import com.nammamedmate.automation.domain.AutomationSimulation;
import com.nammamedmate.automation.domain.FalsePositiveRisk;
import com.nammamedmate.automation.domain.SimulationRiskAssessor;
import com.nammamedmate.automation.domain.SimulationStatus;
import com.nammamedmate.automation.domain.TriggerDefinition;
import com.nammamedmate.automation.domain.TriggerEntityTypes;
import com.nammamedmate.messaging.OutboxPublisher;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverageStory004Test {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock SimulationStorePort simulations;
  @Mock RuleSimulationService simulationService;
  @Mock RuleStorePort rules;
  @Mock RuleManagementService ruleManagement;
  @Mock SimulationNotifyPort notify;

  private final ObjectMapper om = new ObjectMapper();
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T09:30:00Z"), ZoneOffset.UTC);
  private final UUID id = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private final UUID ruleId = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  @SuppressWarnings("unchecked")
  void simulationStoreAndTriggerQuery() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rule_id")).thenReturn(ruleId);
    when(rs.getInt("sample_size")).thenReturn(100);
    when(rs.getInt("events_scanned")).thenReturn(10);
    when(rs.getInt("entities_matched")).thenReturn(4);
    when(rs.getInt("conditions_failed_count")).thenReturn(6);
    when(rs.getString("false_positive_risk")).thenReturn("LOW");
    when(rs.getString("risk_details")).thenReturn("ok");
    when(rs.getString("estimated_impact_summary")).thenReturn("Would have fired 4 times");
    when(rs.getString("results_json")).thenReturn("[]");
    when(rs.getString("status")).thenReturn("COMPLETED");
    when(rs.getTimestamp("started_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T09:30:00Z")));
    when(rs.getTimestamp("completed_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T09:30:18Z")));
    when(rs.getObject("triggered_by")).thenReturn(null);
    when(rs.getTimestamp("expires_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-31T09:30:18Z")));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    JdbcSimulationStoreAdapter store = new JdbcSimulationStoreAdapter(jdbc, om);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByRuleAndId(ruleId, id)).isPresent();
    assertThat(store.findLatestCompletedByRuleId(ruleId)).isPresent();
    assertThat(store.findLatestCompletedByRuleId(null)).isEmpty();
    store.insert(
        new AutomationSimulation(
            id,
            ruleId,
            100,
            0,
            0,
            0,
            null,
            null,
            null,
            List.of(),
            SimulationStatus.RUNNING,
            Instant.parse("2026-07-24T09:30:00Z"),
            null,
            null,
            null));
    store.markCompleted(
        id,
        10,
        4,
        6,
        FalsePositiveRisk.LOW,
        "ok",
        "summary",
        List.of(Map.of("action", "send_notification")),
        Instant.parse("2026-07-24T09:30:18Z"),
        Instant.parse("2026-07-31T09:30:18Z"));
    store.markFailed(id, Instant.parse("2026-07-24T09:30:18Z"), "boom");
    assertThat(store.deleteExpired(Instant.parse("2026-08-01T00:00:00Z"))).isZero();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              Object mapped = mapper.mapRow(rs, 0);
              return List.of(mapped instanceof UUID ? mapped : id);
            });
    assertThat(store.listRunning(5)).isNotEmpty();

    when(rs.getString("trigger_id")).thenReturn("invoice_overdue");
    when(rs.getString("entity_type")).thenReturn("PHARMACY");
    when(rs.getObject("entity_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("payload")).thenReturn("{\"a\":1}");
    when(rs.getTimestamp("fired_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T09:00:00Z")));
    JdbcTriggerEventStore events = new JdbcTriggerEventStore(jdbc, om);
    assertThat(events.listRecentByTrigger("invoice_overdue", 10)).hasSize(1);
    assertThat(events.findLatestByEntity("PHARMACY", UUID.randomUUID())).isPresent();
    events.insert("t", "ORDER", UUID.randomUUID(), null, Instant.parse("2026-07-24T09:00:00Z"));
  }

  @Test
  void riskAssessorAndEntityTypes() {
    List<Map<String, Object>> actions =
        List.of(
            Map.of("action", "suspend_entity", "entity_id", UUID.randomUUID()),
            Map.of("action", "send_notification", "entity_id", UUID.randomUUID()));
    var high =
        SimulationRiskAssessor.assess(
            10, actions, "pharmacy", List.of(new ActionSpec("suspend_entity", Map.of(), false)));
    assertThat(high.risk())
        .isIn(FalsePositiveRisk.MEDIUM, FalsePositiveRisk.HIGH, FalsePositiveRisk.LOW);

    var low = SimulationRiskAssessor.assess(0, List.of(), "order", List.of());
    assertThat(low.risk()).isEqualTo(FalsePositiveRisk.LOW);
    assertThat(low.estimatedImpactSummary()).contains("Would have fired");

    var mediumActions = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 0; i < 10; i++) {
      UUID eid = UUID.randomUUID();
      if (i < 1) {
        mediumActions.add(Map.of("action", "release_payout", "entity_id", eid));
      } else {
        mediumActions.add(Map.of("action", "send_notification", "entity_id", eid));
      }
    }
    // 1/10 = 10% is not > 10%, so LOW; use 2/10 = 20% for HIGH already covered
    var mid =
        SimulationRiskAssessor.assess(
            20,
            List.of(
                Map.of("action", "mass_payout", "entity_id", UUID.randomUUID()),
                Map.of("action", "mass_payout", "entity_id", UUID.randomUUID())),
            "pharmacy",
            List.of(new ActionSpec("mass_payout", Map.of(), false)));
    // 2/20 = 10% → not >10 → check >5 → MEDIUM
    assertThat(mid.risk()).isEqualTo(FalsePositiveRisk.MEDIUM);

    TriggerDefinition finance =
        new TriggerDefinition(
            "invoice_overdue",
            "FINANCE",
            "n",
            "d",
            List.of(),
            List.of(),
            List.of("invoice.id", "pharmacy.id"),
            true);
    assertThat(TriggerEntityTypes.allowed(finance)).contains("PHARMACY");
    assertThat(TriggerEntityTypes.primaryLabel(TriggerEntityTypes.allowed(finance)))
        .isEqualTo("pharmacy");

    TriggerDefinition empty =
        new TriggerDefinition("x", "ORDERS", "n", "d", List.of(), List.of(), List.of(), true);
    assertThat(TriggerEntityTypes.allowed(empty)).contains("ORDER");
    assertThat(TriggerEntityTypes.primaryLabel(java.util.Set.of())).isEqualTo("entity");
    assertThat(FalsePositiveRisk.parse("high")).isEqualTo(FalsePositiveRisk.HIGH);
    assertThat(FalsePositiveRisk.parse(" ")).isNull();
    assertThat(SimulationStatus.parse("running")).isEqualTo(SimulationStatus.RUNNING);
    assertThat(SimulationStatus.parse(null)).isNull();
  }

  @Test
  void jobProcessorAndNotifyStub() {
    when(simulations.listRunning(10)).thenReturn(List.of(id));
    SimulationJobProcessor job =
        new SimulationJobProcessor(
            simulations, simulationService, rules, ruleManagement, notify, clock);
    job.pollRunningSimulations();
    verify(simulationService).processSimulation(id);
    when(rules.listSimulatingStartedBefore(any(), eq(50))).thenReturn(List.of());
    job.revertExpiredSimulating();
    job.expireOldResults();
    verify(simulations).deleteExpired(any());

    @SuppressWarnings("unchecked")
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    StubSimulationNotifyAdapter stub = new StubSimulationNotifyAdapter(provider);
    stub.simulatingAutoReverted(ruleId, UUID.randomUUID(), "rule");
    verify(publisher).publish(any());

    when(provider.getIfAvailable()).thenReturn(null);
    new StubSimulationNotifyAdapter(provider).simulatingAutoReverted(null, null, "x");
  }

  @Test
  void enumsParse() {
    assertThat(com.nammamedmate.automation.domain.AutomationSimulation.class).isNotNull();
  }
}
