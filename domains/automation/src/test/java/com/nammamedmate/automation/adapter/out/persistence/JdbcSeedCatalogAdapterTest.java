package com.nammamedmate.automation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.automation.domain.SeedCatalogEntry;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
class JdbcSeedCatalogAdapterTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void mapsAndMutates() throws Exception {
    UUID ruleId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID wfId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    when(rs.getString("seed_rule_key")).thenReturn("AUTO_ASSIGN_UNASSIGNED_ORDERS");
    when(rs.getObject("rule_id")).thenReturn(ruleId);
    when(rs.getObject("workflow_id")).thenReturn(wfId);
    when(rs.getInt("display_order")).thenReturn(1);
    when(rs.getString("expected_impact")).thenReturn("impact");
    when(rs.getString("edge_cases")).thenReturn("edges");
    when(rs.getTimestamp("initialized_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    JdbcSeedCatalogAdapter adapter = new JdbcSeedCatalogAdapter(jdbc);
    assertThat(adapter.findByKey("AUTO_ASSIGN_UNASSIGNED_ORDERS")).isPresent();
    assertThat(adapter.findByKey(" ")).isEmpty();
    assertThat(adapter.findByKey(null)).isEmpty();
    assertThat(adapter.findByRuleId(ruleId)).isPresent();
    assertThat(adapter.findByRuleId(null)).isEmpty();
    assertThat(adapter.listAll()).hasSize(1);

    SeedCatalogEntry entry =
        new SeedCatalogEntry("AUTO_FLAG_SCHEDULE_X", ruleId, null, 6, "i", "e", now);
    adapter.insert(entry);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());

    when(rs.getObject("rule_id")).thenReturn(null);
    when(rs.getObject("workflow_id")).thenReturn(null);
    when(rs.getTimestamp("initialized_at")).thenReturn(null);
    assertThat(adapter.findByKey("AUTO_ASSIGN_UNASSIGNED_ORDERS").orElseThrow().ruleId()).isNull();

    adapter.insert(new SeedCatalogEntry("X", null, wfId, 3, "i", "e", null));
  }
}
