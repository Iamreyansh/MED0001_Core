package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcRxAuditStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListFilter;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcRxAuditStoreCoverageTest {

  private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

  @Test
  @SuppressWarnings("unchecked")
  void crudListStatsAndHelpers() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcRxAuditStore store = new JdbcRxAuditStore(jdbc, om);
    UUID id = Ids.newId();
    UUID rx = Ids.newId();
    UUID pharmacy = Ids.newId();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    RxAuditEntry entry =
        new RxAuditEntry(
            id,
            rx,
            null,
            pharmacy,
            "H1",
            "AWAITING_AUDIT",
            now.plusSeconds(86400),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now);

    store.insert(entry);
    store.update(entry);
    store.appendActivity(
        Ids.newId(), rx, "RX_VERIFIED", Ids.newId(), "admin_compliance", null, now);
    store.appendActivity(
        Ids.newId(), rx, "RX_FLAGGED", Ids.newId(), "admin_compliance", "{\"a\":1}", now);
    verify(jdbc, org.mockito.Mockito.atLeast(2)).update(anyString(), any(Object[].class));

    ResultSet rs = mockEntryRs(id, rx, pharmacy, now);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.findByRxId(rx)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx)))
        .thenAnswer(
            inv -> {
              ResultSet act = mock(ResultSet.class);
              when(act.getObject("id")).thenReturn(Ids.newId());
              when(act.getString("action")).thenReturn("RX_VERIFIED");
              when(act.getObject("actor_id")).thenReturn(Ids.newId());
              when(act.getString("actor_role")).thenReturn("admin_compliance");
              when(act.getString("payload")).thenReturn("{\"ok\":true}");
              when(act.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(act, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet act = mock(ResultSet.class);
              when(act.getObject("id")).thenReturn(Ids.newId());
              when(act.getString("action")).thenReturn("RX_FLAGGED");
              when(act.getObject("actor_id")).thenReturn(Ids.newId());
              when(act.getString("actor_role")).thenReturn("admin_compliance");
              when(act.getString("payload")).thenReturn(null);
              when(act.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(act, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet act = mock(ResultSet.class);
              when(act.getObject("id")).thenReturn(Ids.newId());
              when(act.getString("action")).thenReturn("RX_FLAGGED");
              when(act.getObject("actor_id")).thenReturn(Ids.newId());
              when(act.getString("actor_role")).thenReturn("admin_compliance");
              when(act.getString("payload")).thenReturn("{bad");
              when(act.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(act, 0));
            });
    assertThat(store.listActivity(rx)).hasSize(1);
    assertThat(store.listActivity(rx)).hasSize(1);
    assertThat(store.listActivity(rx)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    // also exercise non-null KPI counts
    when(jdbc.queryForObject(anyString(), eq(Long.class)))
        .thenReturn(null)
        .thenReturn(1L)
        .thenReturn(2L)
        .thenReturn(3L)
        .thenReturn(4L)
        .thenReturn(5L)
        .thenReturn(6L);
    assertThat(
            store
                .list(
                    new ListFilter(
                        "H1",
                        "AWAITING_AUDIT",
                        "DIGITAL",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        "search",
                        pharmacy,
                        1,
                        20),
                    now)
                .total())
        .isZero();
    // KPI a==0 then a>0
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    assertThat(
            store
                .list(new ListFilter(null, null, null, null, null, null, null, 1, 20), now)
                .kpis()
                .complianceRatePct())
        .isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
    assertThat(
            store
                .list(new ListFilter("ALL", "ALL", null, null, null, null, null, 1, 20), now)
                .kpis()
                .complianceRatePct())
        .isEqualTo(100.0);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx)))
        .thenAnswer(
            inv -> {
              ResultSet act = mock(ResultSet.class);
              when(act.getObject("id")).thenReturn(Ids.newId());
              when(act.getString("action")).thenReturn("RX_VERIFIED");
              when(act.getObject("actor_id")).thenReturn(Ids.newId());
              when(act.getString("actor_role")).thenReturn("admin_compliance");
              when(act.getString("payload")).thenReturn("   ");
              when(act.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(act, 0));
            });
    assertThat(store.listActivity(rx)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet listRs = mockListRs(id, rx, pharmacy, now);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(listRs, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet listRs = mockListRs(id, rx, pharmacy, now);
              when(listRs.getString("rx_type")).thenReturn("E_PRESCRIPTION");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(listRs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
    store.list(new ListFilter("ALL", "ALL", "UPLOADED", null, null, null, null, 1, 20), now);
    store.list(new ListFilter("ALL", "ALL", "DIGITAL", null, null, null, null, 1, 20), now);
    store.listAllForExport(new ListFilter("H1", "ALL", null, null, null, "x", pharmacy, 1, 100));
    // ts(null) + ts(non-null)
    store.update(
        new RxAuditEntry(
            id,
            rx,
            null,
            pharmacy,
            "H1",
            "VERIFIED",
            now.plusSeconds(1),
            false,
            null,
            Ids.newId(),
            now,
            null,
            null,
            Ids.newId(),
            now,
            null,
            now));
    store.update(
        new RxAuditEntry(
            id,
            rx,
            null,
            pharmacy,
            "H1",
            "AWAITING_AUDIT",
            now.plusSeconds(1),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now));

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet d = mock(ResultSet.class);
              when(d.getObject("rx_id")).thenReturn(rx);
              when(d.getObject("audit_id")).thenReturn(id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(d, 0));
            });
    assertThat(store.findDuplicate("P", "Drug", 1, now.minusSeconds(10), Ids.newId())).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.findAwaitingPastDeadline(now, 10)).hasSize(1);
    when(jdbc.update(anyString(), eq(id))).thenReturn(1);
    assertThat(store.markOverdue(id, now)).isEqualTo(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
        .thenReturn(null) // complianceRate total null → 0
        .thenReturn(null)
        .thenReturn(0L) // H1 total 0
        .thenReturn(null)
        .thenReturn(5L) // X total
        .thenReturn(null); // X verified null
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any()))
        .thenReturn(null) // total/flagged/overdue nulls for stats body
        .thenReturn(null)
        .thenReturn(null)
        .thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet top = mock(ResultSet.class);
              when(top.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(top.getString("name")).thenReturn("Pharm");
              when(top.getLong("flagged_count")).thenReturn(2L);
              when(top.getString("drug_name")).thenReturn("Alprazolam");
              when(top.getString("schedule")).thenReturn("H1");
              when(top.getLong("flag_count")).thenReturn(3L);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(top, 0));
            });
    assertThat(store.statistics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)).totalAudited())
        .isZero();
    // second pass with non-null totals for flaggedRate > 0 path
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(10L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(10L);
    assertThat(store.statistics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)).totalAudited())
        .isEqualTo(10L);

    when(jdbc.query(anyString(), any(RowMapper.class), eq((UUID) null))).thenReturn(List.of());
    assertThat(store.orderContext(null)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class)))
        .thenAnswer(
            inv -> {
              ResultSet o = mock(ResultSet.class);
              when(o.getString("order_number")).thenReturn("ORD");
              when(o.getString("pharmacy_name")).thenReturn("P");
              when(o.getString("name")).thenReturn("P");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(o, 0));
            });
    assertThat(store.orderContext(Ids.newId())).isPresent();
    assertThat(store.pharmacyName(pharmacy)).contains("P");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              ResultSet d = mock(ResultSet.class);
              when(d.getTimestamp("dispensed_at")).thenReturn(Timestamp.from(now));
              when(d.getString("approved_medicines"))
                  .thenReturn("[{\"name\":\"A\",\"quantity\":1,\"schedule\":\"H1\"}]");
              when(d.getString("patient_name")).thenReturn("P");
              when(d.getString("doctor_name")).thenReturn("D");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(d, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet d = mock(ResultSet.class);
              when(d.getTimestamp("dispensed_at")).thenReturn(Timestamp.from(now));
              when(d.getString("approved_medicines")).thenReturn("not-json");
              when(d.getString("patient_name")).thenReturn("P");
              when(d.getString("doctor_name")).thenReturn("D");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(d, 0));
            });
    assertThat(store.dispenseContext(rx, pharmacy).orElseThrow().medicines()).hasSize(1);
    assertThat(store.dispenseContext(rx, pharmacy).orElseThrow().medicines()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              ResultSet d = mock(ResultSet.class);
              when(d.getTimestamp("dispensed_at")).thenReturn(null);
              when(d.getString("approved_medicines")).thenReturn("   ");
              when(d.getString("patient_name")).thenReturn("P");
              when(d.getString("doctor_name")).thenReturn("D");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(d, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet d = mock(ResultSet.class);
              when(d.getTimestamp("dispensed_at")).thenReturn(null);
              when(d.getString("approved_medicines")).thenReturn(null);
              when(d.getString("patient_name")).thenReturn("P");
              when(d.getString("doctor_name")).thenReturn("D");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(d, 0));
            });
    assertThat(store.dispenseContext(rx, pharmacy).orElseThrow().medicines()).isEmpty();
    assertThat(store.dispenseContext(rx, pharmacy).orElseThrow().medicines()).isEmpty();
  }

  private static ResultSet mockEntryRs(UUID id, UUID rx, UUID pharmacy, Instant now)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rx_id")).thenReturn(rx);
    when(rs.getObject("order_id")).thenReturn(null);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("schedule")).thenReturn("H1");
    when(rs.getString("audit_status")).thenReturn("AWAITING_AUDIT");
    when(rs.getTimestamp("audit_deadline")).thenReturn(Timestamp.from(now.plusSeconds(86400)));
    when(rs.getBoolean("possible_duplicate")).thenReturn(false);
    when(rs.getObject("possible_duplicate_rx_id")).thenReturn(null);
    when(rs.getObject("verified_by")).thenReturn(null);
    when(rs.getTimestamp("verified_at")).thenReturn(null);
    when(rs.getString("flag_reason")).thenReturn(null);
    when(rs.getString("flag_severity")).thenReturn(null);
    when(rs.getObject("flagged_by")).thenReturn(null);
    when(rs.getTimestamp("flagged_at")).thenReturn(null);
    when(rs.getString("notes")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private static ResultSet mockListRs(UUID id, UUID rx, UUID pharmacy, Instant now)
      throws Exception {
    ResultSet rs = mockEntryRs(id, rx, pharmacy, now);
    when(rs.getString("patient_name")).thenReturn("P");
    when(rs.getString("doctor_name")).thenReturn("D");
    when(rs.getString("rx_type")).thenReturn("UPLOADED");
    when(rs.getString("rx_source")).thenReturn("UPLOAD");
    when(rs.getString("pharmacy_name")).thenReturn("Pharm");
    when(rs.getTimestamp("dispensed_at")).thenReturn(null);
    when(rs.getString("drug_summary")).thenReturn("Drug");
    return rs;
  }
}
