package com.nammamedmate.automation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.domain.KillSwitchStatus;
import com.nammamedmate.automation.domain.RuleSnapshot;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PersistenceAdaptersTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;
  @Mock Array sqlArray;

  private final ObjectMapper om = new ObjectMapper();
  private final Clock clock = Clock.fixed(Instant.parse("2026-07-24T08:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void triggerRegistryMapsRows() throws Exception {
    when(sqlArray.getArray()).thenReturn(new Object[] {"zone_in"});
    when(rs.getString("trigger_id")).thenReturn("order_unassigned");
    when(rs.getString("category")).thenReturn("DISPATCH");
    when(rs.getString("name")).thenReturn("Order Unassigned");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("parameters_schema")).thenReturn("[{\"name\":\"duration_minutes\"}]");
    when(rs.getArray("available_conditions")).thenReturn(sqlArray);
    when(rs.getArray("available_context_vars")).thenReturn(sqlArray);
    when(rs.getBoolean("is_active")).thenReturn(true);
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

    JdbcTriggerRegistryAdapter adapter = new JdbcTriggerRegistryAdapter(jdbc, om);
    assertThat(adapter.listActive(null)).hasSize(1);
    assertThat(adapter.listActive("dispatch")).hasSize(1);
    assertThat(adapter.findById("order_unassigned")).isPresent();
    assertThat(adapter.findById(" ")).isEmpty();
    assertThat(adapter.findById(null)).isEmpty();

    when(rs.getString("parameters_schema")).thenReturn("not-json");
    when(rs.getArray("available_conditions")).thenReturn(null);
    assertThat(adapter.listActive(null).getFirst().parameters()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void actionRegistryMapsRows() throws Exception {
    when(rs.getString("action_id")).thenReturn("release_payout");
    when(rs.getString("category")).thenReturn("FINANCE");
    when(rs.getString("name")).thenReturn("Release");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("required_params_schema")).thenReturn("[\"amount_paise\"]");
    when(rs.getString("optional_params_schema")).thenReturn("bad");
    when(rs.getBoolean("is_reversible")).thenReturn(false);
    when(rs.getBoolean("always_require_approval")).thenReturn(false);
    when(rs.getObject("auto_approval_limit_paise")).thenReturn(5_000_000L);
    when(rs.getLong("auto_approval_limit_paise")).thenReturn(5_000_000L);
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

    var adapter = new JdbcActionRegistryAdapter(jdbc, om);
    var rows = adapter.listAll();
    assertThat(rows.getFirst().autoApprovalLimitPaise()).isEqualTo(5_000_000L);
    assertThat(rows.getFirst().optionalParams()).isEmpty();
    assertThat(adapter.findById("release_payout")).isPresent();
    assertThat(adapter.findById("")).isEmpty();
    assertThat(adapter.findById(null)).isEmpty();
  }

  @Test
  void triggerEventStoreInsertAndMark() {
    JdbcTriggerEventStore store = new JdbcTriggerEventStore(jdbc, om);
    UUID id =
        store.insert(
            "order_unassigned",
            "ORDER",
            UUID.randomUUID(),
            Map.of("a", 1),
            Instant.parse("2026-07-24T08:00:00Z"));
    assertThat(id).isNotNull();
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any());
    store.markProcessed(id, Instant.parse("2026-07-24T08:01:00Z"), 1, 1, "RULE_FIRED");
    verify(jdbc).update(anyString(), any(Timestamp.class), eq(1), eq(1), eq("RULE_FIRED"), eq(id));
  }

  @Test
  @SuppressWarnings("unchecked")
  void killSwitchReads() {
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("PAUSED"));
    assertThat(new JdbcKillSwitchAdapter(jdbc).status()).isEqualTo(KillSwitchStatus.PAUSED);
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    assertThat(new JdbcKillSwitchAdapter(jdbc).status()).isEqualTo(KillSwitchStatus.ACTIVE);
  }

  @Test
  void dedupAndRateLimit() {
    InMemoryDedupAdapter dedup = new InMemoryDedupAdapter(clock);
    UUID rule = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    assertThat(dedup.isDuplicate(rule, entity, Duration.ofSeconds(300))).isFalse();
    dedup.recordFire(rule, entity);
    assertThat(dedup.isDuplicate(rule, entity, Duration.ofSeconds(300))).isTrue();

    InMemoryRateLimitAdapter limiter = new InMemoryRateLimitAdapter(clock);
    assertThat(limiter.tryAcquire(rule, 1, 60)).isTrue();
    assertThat(limiter.wouldExceed(rule, 1, 60)).isTrue();
    assertThat(limiter.tryAcquire(rule, 1, 60)).isFalse();
    assertThat(limiter.tryAcquire(null, 1, 60)).isTrue();
    assertThat(limiter.wouldExceed(null, 1, 60)).isFalse();
    assertThat(new RuleSnapshot(rule, "t", List.of(), List.of(), 0).dedupWindowSeconds())
        .isEqualTo(300);

    StubRuleAuditAdapter audit = new StubRuleAuditAdapter();
    audit.log("CREATE", rule, entity, Map.of("k", "v"));
    assertThat(audit.entries()).hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ruleStoreMapsAndMutates() throws Exception {
    UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("Rule");
    when(rs.getString("description")).thenReturn("d");
    when(rs.getString("trigger_id")).thenReturn("order_unassigned");
    when(rs.getString("trigger_category")).thenReturn("DISPATCH");
    when(rs.getString("trigger_params")).thenReturn("{\"duration_minutes\":5}");
    when(rs.getString("conditions"))
        .thenReturn("[{\"field\":\"zone_id\",\"operator\":\"zone_in\",\"value\":[\"z1\"]}]");
    when(rs.getString("actions"))
        .thenReturn(
            "[{\"action_id\":\"auto_assign_rider\",\"params\":{\"order_id\":\"x\"},\"parallel\":false}]");
    when(rs.getString("guardrails"))
        .thenReturn("{\"rate_limit\":{\"max_fires\":1,\"per_minutes\":60}}");
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getInt("fire_count")).thenReturn(2);
    when(rs.getTimestamp("last_fired_at")).thenReturn(null);
    when(rs.getBoolean("is_seed_rule")).thenReturn(false);
    when(rs.getInt("dedup_window_seconds")).thenReturn(300);
    when(rs.getObject("created_by")).thenReturn(null);
    when(rs.getTimestamp("created_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T08:00:00Z")));
    when(rs.getTimestamp("updated_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-07-24T08:00:00Z")));
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
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);

    JdbcRuleStoreAdapter store = new JdbcRuleStoreAdapter(jdbc, om);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByNameIgnoreCase("Rule")).isPresent();
    assertThat(store.listActiveOrSimulating()).hasSize(1);
    assertThat(store.countByStatus(com.nammamedmate.automation.domain.RuleStatus.ACTIVE))
        .isEqualTo(1);
    assertThat(store.countFiltered("ACTIVE", "DISPATCH", "Ru")).isEqualTo(1);
    assertThat(store.listFiltered("ACTIVE", "DISPATCH", "Ru", 0, 20)).hasSize(1);

    var rule = store.findById(id).orElseThrow();
    store.insert(rule);
    store.update(rule);
    store.softDelete(id, Instant.parse("2026-07-24T09:00:00Z"));
    store.recordFire(id, Instant.parse("2026-07-24T09:00:00Z"));
    store.markSimulatingStarted(id, Instant.parse("2026-07-24T09:00:00Z"));
    store.clearSimulatingStarted(id);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(rs.getObject("id")).thenReturn(id);
    assertThat(store.listSimulatingStartedBefore(Instant.parse("2026-07-24T10:00:00Z"), 10))
        .contains(id);

    JdbcRuleLookupAdapter lookup = new JdbcRuleLookupAdapter(store);
    assertThat(lookup.findById(id)).isPresent();
    assertThat(lookup.listActive()).hasSize(1);

    when(rs.getString("conditions")).thenReturn("bad");
    when(rs.getString("actions")).thenReturn("bad");
    when(rs.getString("guardrails")).thenReturn("bad");
    assertThat(store.findById(id).orElseThrow().conditions()).isEmpty();
  }
}
