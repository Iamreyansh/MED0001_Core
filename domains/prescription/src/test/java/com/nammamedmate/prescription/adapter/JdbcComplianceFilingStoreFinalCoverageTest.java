package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcComplianceFilingStore;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ActivityFilter;
import com.nammamedmate.prescription.application.port.out.ComplianceFilingStore.ListFilter;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcComplianceFilingStoreFinalCoverageTest {

  private final Instant now = Instant.parse("2026-07-01T00:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void nullTotalsEmptyJsonAndUnfilteredList() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper om = mock(ObjectMapper.class);
    when(om.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new RuntimeException("bad json"));
    JdbcComplianceFilingStore store = new JdbcComplianceFilingStore(jdbc, om);
    UUID id = Ids.newId();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    assertThat(
            store.existsTypePeriod(
                "ADVERSE_EVENTS", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        .isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    assertThat(
            store.existsTypePeriod(
                "ADVERSE_EVENTS", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        .isFalse();
    store.appendActivity(id, null, null, id, "X", id, "admin_compliance", null, null, now);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    var page = store.list(new ListFilter(null, null, null, true, 1, 20));
    assertThat(page.total()).isZero();
    assertThat(page.pending()).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("action")).thenReturn("RX_VERIFIED");
              when(rs.getObject("actor_id")).thenReturn(id);
              when(rs.getString("actor_name")).thenReturn(null);
              when(rs.getString("actor_role")).thenReturn("admin_compliance");
              when(rs.getObject("rx_id")).thenReturn(null);
              when(rs.getString("payload")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.listActivity(new ActivityFilter(null, null, null, null, 1, 50)).total())
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("action")).thenReturn("RX_VERIFIED");
              when(rs.getObject("actor_id")).thenReturn(id);
              when(rs.getString("actor_name")).thenReturn(null);
              when(rs.getString("actor_role")).thenReturn("admin_compliance");
              when(rs.getObject("rx_id")).thenReturn(null);
              when(rs.getString("payload")).thenReturn("   ");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.listActivity(new ActivityFilter(null, null, null, null, 1, 50)).total())
        .isEqualTo(1L);
    assertThat(
            store
                .listActivity(new ActivityFilter(null, null, null, null, 1, 50))
                .items()
                .get(0)
                .get("payload"))
        .isEqualTo(java.util.Map.of());

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("action")).thenReturn("X");
              when(rs.getObject("actor_id")).thenReturn(id);
              when(rs.getString("actor_name")).thenReturn("A");
              when(rs.getString("actor_role")).thenReturn("admin_compliance");
              when(rs.getObject("rx_id")).thenReturn(null);
              when(rs.getString("payload")).thenReturn("{bad");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(
            store
                .listActivity(
                    new ActivityFilter("RX_VERIFIED", id, now, now.plusSeconds(10), 1, 10))
                .items()
                .get(0)
                .get("payload"))
        .isEqualTo(java.util.Map.of());

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(store.adminName(id)).isEmpty();
    assertThat(store.findById(id)).isEmpty();
    assertThat(store.findGeneratingJob(id)).isEmpty();
    assertThat(store.findGenerateJob(id)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("filing_type")).thenReturn("DRUG_RECALL");
              when(rs.getDate("period_from")).thenReturn(Date.valueOf("2026-06-01"));
              when(rs.getDate("period_to")).thenReturn(Date.valueOf("2026-06-30"));
              when(rs.getDate("due_date")).thenReturn(Date.valueOf("2026-07-15"));
              when(rs.getString("status")).thenReturn("PENDING");
              when(rs.getString("generated_report_s3_key")).thenReturn("k");
              when(rs.getString("generated_report_format")).thenReturn("CSV");
              when(rs.getTimestamp("generated_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("filed_by")).thenReturn(id);
              when(rs.getTimestamp("filed_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("reference_number")).thenReturn("R");
              when(rs.getBoolean("is_archived")).thenReturn(true);
              when(rs.getTimestamp("overdue_alerted_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("overdue_escalation_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(store.list(new ListFilter(null, null, null, true, 1, 5)).filings()).hasSize(1);
  }
}
