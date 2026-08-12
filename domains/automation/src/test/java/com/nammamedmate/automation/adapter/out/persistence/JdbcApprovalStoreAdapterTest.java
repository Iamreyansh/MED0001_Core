package com.nammamedmate.automation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.ApprovalQueueStats;
import com.nammamedmate.automation.application.port.out.ApprovalStorePort.Chips;
import com.nammamedmate.automation.domain.ApprovalCategory;
import com.nammamedmate.automation.domain.ApprovalStatus;
import com.nammamedmate.automation.domain.ApprovalUrgency;
import com.nammamedmate.automation.domain.AutomationApproval;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcApprovalStoreAdapterTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final ObjectMapper om = new ObjectMapper();
  private final UUID id = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private final Instant now = Instant.parse("2026-07-24T09:45:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void crudChipsStatsAndFilters() throws Exception {
    stubRow();
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
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<Chips> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("pending_count")).thenReturn(3L);
              when(rs.getLong("urgent_count")).thenReturn(1L);
              when(rs.getLong("approved_today")).thenReturn(8L);
              when(rs.getLong("rejected_today")).thenReturn(1L);
              return ex.extractData(rs);
            });
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<Map<String, Object>> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true);
              when(rs.getDouble("avg_minutes")).thenReturn(18.44);
              when(rs.getLong("approved")).thenReturn(8L);
              when(rs.getLong("rejected")).thenReturn(1L);
              when(rs.getLong("expired")).thenReturn(1L);
              return ex.extractData(rs);
            });

    JdbcApprovalStoreAdapter adapter = new JdbcApprovalStoreAdapter(jdbc, om);
    AutomationApproval row =
        new AutomationApproval(
            id,
            id,
            "r",
            id,
            "t",
            "release_payout",
            Map.of("a", 1),
            "PHARMACY",
            id,
            "n",
            4800000L,
            ApprovalCategory.FINANCE,
            ApprovalUrgency.URGENT,
            "why",
            Map.of("p", 1),
            List.of(Map.of("f", "x")),
            "impact",
            "open_csm_task",
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            id,
            now,
            now.plusSeconds(3600),
            null);
    adapter.insert(row);
    assertThat(adapter.findById(id)).isPresent();
    assertThat(adapter.findPending(id, id, "release_payout")).isPresent();
    assertThat(adapter.list(ApprovalStatus.PENDING, ApprovalUrgency.URGENT, 0, 20)).hasSize(1);
    assertThat(adapter.list(null, null, 0, 20)).hasSize(1);
    assertThat(adapter.count(ApprovalStatus.PENDING, null)).isEqualTo(3L);
    assertThat(adapter.countPending()).isEqualTo(2L);
    Chips chips = adapter.chips(now);
    assertThat(chips.pendingCount()).isEqualTo(3L);
    ApprovalQueueStats stats = adapter.stats(now);
    assertThat(stats.approvalRatePct()).isEqualTo(88.9);
    assertThat(stats.avgResponseTimeMinutes()).isEqualTo(18.4);
    assertThat(
            adapter.markResolved(
                id, ApprovalStatus.PENDING, ApprovalStatus.APPROVED, id, "n", null, id, now))
        .isEqualTo(1);
    assertThat(
            adapter.markResolved(
                id, ApprovalStatus.PENDING, ApprovalStatus.REJECTED, id, null, "no", id, now))
        .isEqualTo(1);
    assertThat(adapter.listExpired(now, 10)).hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapNullsBadJsonAndStatsFallback() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rule_id")).thenReturn(null);
    when(rs.getString("rule_name")).thenReturn(null);
    when(rs.getObject("trigger_event_id")).thenReturn(null);
    when(rs.getString("trigger_event")).thenReturn(null);
    when(rs.getString("action_type")).thenReturn("x");
    when(rs.getString("action_params")).thenReturn("nope");
    when(rs.getString("entity_type")).thenReturn(null);
    when(rs.getObject("entity_id")).thenReturn(null);
    when(rs.getString("entity_name")).thenReturn(null);
    when(rs.getObject("amount_paise")).thenReturn(null);
    when(rs.getString("category")).thenReturn("NOPE");
    when(rs.getString("urgency")).thenReturn("NOPE");
    when(rs.getString("why_requires_approval")).thenReturn(null);
    when(rs.getString("trigger_context")).thenReturn(" ");
    when(rs.getString("conditions_met")).thenReturn("not-json");
    when(rs.getString("estimated_impact")).thenReturn(null);
    when(rs.getString("on_reject_action")).thenReturn(null);
    when(rs.getString("status")).thenReturn("NOPE");
    when(rs.getObject("approved_by")).thenReturn(null);
    when(rs.getObject("rejected_by")).thenReturn(null);
    when(rs.getString("approval_notes")).thenReturn(null);
    when(rs.getString("rejection_reason")).thenReturn(null);
    when(rs.getObject("activity_log_id")).thenReturn(null);
    when(rs.getTimestamp("triggered_at")).thenReturn(null);
    when(rs.getTimestamp("expires_at")).thenReturn(null);
    when(rs.getTimestamp("resolved_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(2);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any(), any(), any()))
        .thenAnswer(inv -> null);
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

    JdbcApprovalStoreAdapter adapter = new JdbcApprovalStoreAdapter(jdbc, om);
    AutomationApproval mapped = adapter.findById(id).orElseThrow();
    assertThat(mapped.triggeredAt()).isEqualTo(Instant.EPOCH);
    assertThat(mapped.category()).isEqualTo(ApprovalCategory.ADMIN);
    assertThat(mapped.urgency()).isEqualTo(ApprovalUrgency.NORMAL);
    assertThat(mapped.status()).isEqualTo(ApprovalStatus.PENDING);
    assertThat(adapter.count(null, null)).isZero();
    assertThat(adapter.countPending()).isZero();
    ApprovalQueueStats stats = adapter.stats(now);
    assertThat(stats.approvalRatePct()).isZero();
    assertThat(stats.expiryRatePct()).isZero();

    ObjectMapper boom =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) {
            throw new RuntimeException("boom");
          }
        };
    JdbcApprovalStoreAdapter broken = new JdbcApprovalStoreAdapter(jdbc, boom);
    broken.insert(
        new AutomationApproval(
            id,
            null,
            null,
            null,
            null,
            "x",
            Map.of(),
            "E",
            null,
            null,
            null,
            ApprovalCategory.ADMIN,
            ApprovalUrgency.NORMAL,
            null,
            Map.of(),
            List.of(),
            null,
            null,
            ApprovalStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null));

    when(rs.getString("conditions_met")).thenReturn("");
    when(rs.getString("action_params")).thenReturn(null);
    assertThat(adapter.findById(id).orElseThrow().conditionsMet()).isEmpty();
    when(rs.getString("conditions_met")).thenReturn(null);
    assertThat(adapter.findById(id).orElseThrow().conditionsMet()).isEmpty();
  }

  private void stubRow() throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rule_id")).thenReturn(id);
    when(rs.getString("rule_name")).thenReturn("Auto-release");
    when(rs.getObject("trigger_event_id")).thenReturn(id);
    when(rs.getString("trigger_event")).thenReturn("payout_cycle_reached");
    when(rs.getString("action_type")).thenReturn("release_payout");
    when(rs.getString("action_params")).thenReturn("{\"amount_paise\":4800000}");
    when(rs.getString("entity_type")).thenReturn("PHARMACY");
    when(rs.getObject("entity_id")).thenReturn(id);
    when(rs.getString("entity_name")).thenReturn("Apollo");
    when(rs.getObject("amount_paise")).thenReturn(4_800_000L);
    when(rs.getLong("cnt")).thenReturn(2L);
    when(rs.getString("category")).thenReturn("FINANCE");
    when(rs.getString("urgency")).thenReturn("URGENT");
    when(rs.getString("why_requires_approval")).thenReturn("cap");
    when(rs.getString("trigger_context")).thenReturn("{\"payout_amount_paise\":4800000}");
    when(rs.getString("conditions_met")).thenReturn("[{\"field\":\"a\"}]");
    when(rs.getString("estimated_impact")).thenReturn("impact");
    when(rs.getString("on_reject_action")).thenReturn(null);
    when(rs.getString("status")).thenReturn("PENDING");
    when(rs.getObject("approved_by")).thenReturn(null);
    when(rs.getObject("rejected_by")).thenReturn(null);
    when(rs.getString("approval_notes")).thenReturn(null);
    when(rs.getString("rejection_reason")).thenReturn(null);
    when(rs.getObject("activity_log_id")).thenReturn(id);
    when(rs.getTimestamp("triggered_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(14400)));
    when(rs.getTimestamp("resolved_at")).thenReturn(Timestamp.from(now));
  }
}
