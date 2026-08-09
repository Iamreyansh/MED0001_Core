package com.nammamedmate.crm.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.domain.CrmLead;
import com.nammamedmate.crm.domain.CrmLeadActivity;
import com.nammamedmate.crm.domain.LeadSource;
import com.nammamedmate.crm.domain.LeadStage;
import com.nammamedmate.kernel.id.Ids;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcSaasLeadStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  @Test
  @SuppressWarnings("unchecked")
  void coversQueriesAndMutations() throws Exception {
    JdbcSaasLeadStore store = new JdbcSaasLeadStore(jdbc);
    UUID id = Ids.newId();
    UUID repId = Ids.newId();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    CrmLead lead =
        new CrmLead(
            id,
            "P",
            "C",
            "+91",
            "e@x.com",
            LeadSource.ORGANIC,
            LeadStage.NEW,
            0,
            69900L,
            "STARTER",
            repId,
            "n",
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);

    lenient().when(jdbc.query(anyString(), any(RowMapper.class), any())).thenAnswer(this::map);
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(this::map);
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(this::map);
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(this::map);
    lenient()
        .when(
            jdbc.query(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
        .thenAnswer(this::map);
    lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              stubRs(id, repId, now);
              when(rs.getObject("id")).thenReturn(repId);
              return map(inv);
            });
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
        .thenReturn(1);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(2L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Double.class), any(), any()))
        .thenReturn(14.0);
    when(jdbc.queryForObject(anyString(), eq(UUID.class)))
        .thenThrow(new EmptyResultDataAccessException(1))
        .thenReturn(repId);
    when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("stage", "NEW", "cnt", 3L)));

    stubRs(id, repId, now);
    store.insert(lead);
    store.update(lead);
    assertThat(store.findById(id)).isPresent();
    // null estimated_mrr / sales_cycle mapping
    when(rs.getObject("estimated_mrr_paise")).thenReturn(null);
    when(rs.getObject("sales_cycle_days")).thenReturn(null);
    assertThat(store.findById(id)).isPresent();
    stubRs(id, repId, now);
    assertThat(store.existsOpenByPhone("+91", null)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    assertThat(store.existsOpenByPhone("+91", id)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    assertThat(store.existsOpenByPhone("+91", null)).isFalse();
    assertThat(store.existsOpenByPharmacyId(Ids.newId(), null)).isFalse();
    assertThat(store.existsOpenByPharmacyId(null, null)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
    assertThat(store.existsOpenByPharmacyId(Ids.newId(), id)).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    assertThat(store.existsOpenByPharmacyId(Ids.newId(), id)).isFalse();
    assertThat(store.list(LeadStage.NEW, repId, LeadSource.ORGANIC, "P", 0, 10)).hasSize(1);
    assertThat(store.list(null, null, null, null, 0, 10)).hasSize(1);
    store.count(null, null, null, null);
    store.count(LeadStage.NEW, repId, LeadSource.ORGANIC, "P");
    assertThat(store.openStageFunnel()).containsEntry("NEW", 3L);
    var chips = store.chips(now.minusSeconds(100), now);
    assertThat(chips.openLeads()).isGreaterThanOrEqualTo(0L);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    lenient()
        .when(jdbc.queryForObject(anyString(), eq(Double.class), any(), any()))
        .thenReturn(null);
    assertThat(store.chips(now, now).avgSalesCycleDays()).isEqualTo(0.0);
    when(jdbc.queryForList(anyString())).thenReturn(Collections.emptyList());
    assertThat(store.openStageFunnel()).containsEntry(LeadStage.NEW, 0L);
    store.insertActivity(
        new CrmLeadActivity(Ids.newId(), id, "CREATED", null, "NEW", null, null, "SYSTEM", now));
    assertThat(store.listActivities(id)).hasSize(1);
    assertThat(store.findActiveRep(repId)).isPresent();
    assertThat(store.findRepName(repId)).isPresent();
    assertThat(store.findRepName(null)).isEmpty();
    assertThat(store.listActiveRepIds()).contains(repId);
    // first RR: empty cursor
    assertThat(store.nextRoundRobinRepId()).contains(repId);
    // second RR: last == repId → advance
    when(jdbc.queryForObject(anyString(), eq(UUID.class))).thenReturn(repId);
    assertThat(store.nextRoundRobinRepId()).contains(repId);
    // last rep unknown → idx 0
    when(jdbc.queryForObject(anyString(), eq(UUID.class))).thenReturn(Ids.newId());
    assertThat(store.nextRoundRobinRepId()).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
    assertThat(store.nextRoundRobinRepId()).isEmpty();
    verify(jdbc, atLeastOnce())
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @SuppressWarnings("unchecked")
  private List<Object> map(org.mockito.invocation.InvocationOnMock inv) throws Exception {
    RowMapper<Object> mapper = inv.getArgument(1);
    return List.of(mapper.mapRow(rs, 0));
  }

  private void stubRs(UUID id, UUID repId, Instant now) throws Exception {
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("lead_id")).thenReturn(id);
    when(rs.getObject("assigned_rep_id")).thenReturn(repId);
    when(rs.getObject("linked_account_id")).thenReturn(null);
    when(rs.getObject("pharmacy_id")).thenReturn(null);
    when(rs.getObject("actor_id")).thenReturn(null);
    when(rs.getObject("estimated_mrr_paise")).thenReturn(69900L);
    when(rs.getObject("sales_cycle_days")).thenReturn(14);
    when(rs.getLong("estimated_mrr_paise")).thenReturn(69900L);
    when(rs.getInt("sales_cycle_days")).thenReturn(14);
    when(rs.getInt("win_probability")).thenReturn(0);
    when(rs.getString("pharmacy_name")).thenReturn("P");
    when(rs.getString("contact_name")).thenReturn("C");
    when(rs.getString("phone")).thenReturn("+91");
    when(rs.getString("email")).thenReturn("e@x.com");
    when(rs.getString("source")).thenReturn("ORGANIC");
    when(rs.getString("stage")).thenReturn("NEW");
    when(rs.getString("target_plan")).thenReturn("STARTER");
    when(rs.getString("notes")).thenReturn("n");
    when(rs.getString("lost_reason")).thenReturn(null);
    when(rs.getString("event")).thenReturn("CREATED");
    when(rs.getString("stage_from")).thenReturn(null);
    when(rs.getString("stage_to")).thenReturn("NEW");
    when(rs.getString("actor_name")).thenReturn("SYSTEM");
    when(rs.getString("name")).thenReturn("Rep");
    Timestamp ts = Timestamp.from(now);
    when(rs.getTimestamp("won_at")).thenReturn(null);
    when(rs.getTimestamp("lost_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(ts);
    when(rs.getTimestamp("updated_at")).thenReturn(ts);
  }
}
