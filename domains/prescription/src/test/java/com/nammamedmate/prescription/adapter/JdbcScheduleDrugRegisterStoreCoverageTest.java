package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcScheduleDrugRegisterStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ExportJob;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ListFilter;
import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
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

class JdbcScheduleDrugRegisterStoreCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void coversStorePaths() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcScheduleDrugRegisterStore store = new JdbcScheduleDrugRegisterStore(jdbc);
    UUID id = Ids.newId();
    UUID pharmacy = Ids.newId();
    UUID rx = Ids.newId();
    UUID staff = Ids.newId();
    Instant now = Instant.parse("2026-07-24T08:30:00Z");
    ScheduleDrugRegisterEntry entry =
        new ScheduleDrugRegisterEntry(
            id,
            1,
            pharmacy,
            "H1",
            rx,
            "RX-2026-00001",
            null,
            "P",
            52,
            "D",
            "R",
            "Drug",
            "B1",
            30,
            "TABLETS",
            470,
            "LIC",
            "Staff",
            staff,
            now,
            now,
            false,
            now);

    store.insert(entry);
    verify(jdbc).update(anyString(), any(Object[].class));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq("H1"), eq("Drug")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getInt("running_balance")).thenReturn(470);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.latestRunningBalance(pharmacy, "H1", "Drug")).contains(470);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(pharmacy), eq("H1")))
        .thenReturn(2)
        .thenReturn(null);
    assertThat(store.nextSno(pharmacy, "H1")).isEqualTo(3);
    assertThat(store.nextSno(pharmacy, "H1")).isEqualTo(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(pharmacy), anyString()))
        .thenReturn(5)
        .thenReturn(null);
    assertThat(store.nextRxSeq(pharmacy, 2026)).isEqualTo(6);
    assertThat(store.nextRxSeq(pharmacy, 2026)).isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("display_name")).thenReturn("P");
              when(rs.getString("drug_licence_number")).thenReturn(null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("display_name")).thenReturn("P2");
              when(rs.getString("drug_licence_number")).thenReturn("LIC");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.pharmacy(pharmacy)).isPresent();
    assertThat(store.pharmacy(pharmacy).get().licenseNo()).isEqualTo("LIC");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(staff)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("name")).thenReturn("S");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.staffName(staff)).contains("S");

    UUID order = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("order_id")).thenReturn(order);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            })
        .thenReturn(List.of());
    assertThat(store.orderIdForRx(rx, pharmacy)).contains(order);
    assertThat(store.orderIdForRx(rx, pharmacy)).isEmpty();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(1L)
        .thenReturn(1L)
        .thenReturn(null)
        .thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockEntryRs(id, pharmacy, rx, staff, now, true);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet rs = mockEntryRs(id, pharmacy, rx, staff, now, false);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            })
        .thenReturn(List.of());
    var page =
        store.list(
            new ListFilter(
                "H1", pharmacy, "Drug", now.minusSeconds(10), now.plusSeconds(10), 1, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.entries()).hasSize(1);
    var emptyTotals = store.list(new ListFilter("H1", null, "  ", null, null, 0, 10));
    assertThat(emptyTotals.total()).isZero();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    var allSchedules = store.list(new ListFilter("ALL", pharmacy, null, null, null, 1, 10));
    assertThat(allSchedules.total()).isZero();
    assertThat(store.list(new ListFilter(null, pharmacy, null, null, null, 1, 10)).total())
        .isZero();
    assertThat(store.list(new ListFilter("  ", pharmacy, null, null, null, 1, 10)).total())
        .isZero();
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mockEntryRs(id, pharmacy, rx, staff, now, false);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.listAll(new ListFilter("H1", pharmacy, null, null, null, 1, 10))).hasSize(1);
    assertThat(new ScheduleDrugRegisterStore.ListPage(null, 0, 0).entries()).isEmpty();

    when(jdbc.update(anyString(), any(Timestamp.class))).thenReturn(2);
    assertThat(store.markArchivedPastRetention(now)).isEqualTo(2);

    ExportJob job =
        new ExportJob(
            id,
            pharmacy,
            "H1",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            "GENERATING",
            null,
            null,
            staff,
            null,
            null,
            null,
            now);
    store.insertExportJob(job);
    store.updateExportJob(
        new ExportJob(
            id,
            pharmacy,
            "H1",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            "READY",
            "k",
            1,
            staff,
            now,
            now,
            null,
            now));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("schedule")).thenReturn("H1");
              when(rs.getDate("from_date")).thenReturn(Date.valueOf("2026-01-01"));
              when(rs.getDate("to_date")).thenReturn(Date.valueOf("2026-03-31"));
              when(rs.getString("status")).thenReturn("READY");
              when(rs.getString("storage_key")).thenReturn("k");
              when(rs.getObject("row_count")).thenReturn(1);
              when(rs.getInt("row_count")).thenReturn(1);
              when(rs.getObject("requested_by")).thenReturn(staff);
              when(rs.getTimestamp("generated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("error_message")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            })
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("schedule")).thenReturn("H1");
              when(rs.getDate("from_date")).thenReturn(Date.valueOf("2026-01-01"));
              when(rs.getDate("to_date")).thenReturn(Date.valueOf("2026-03-31"));
              when(rs.getString("status")).thenReturn("FAILED");
              when(rs.getString("storage_key")).thenReturn(null);
              when(rs.getObject("row_count")).thenReturn(null);
              when(rs.getObject("requested_by")).thenReturn(staff);
              when(rs.getTimestamp("generated_at")).thenReturn(null);
              when(rs.getTimestamp("expires_at")).thenReturn(null);
              when(rs.getString("error_message")).thenReturn("x");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findExportJob(id)).isPresent();
    assertThat(store.findExportJob(id).get().rowCount()).isNull();

    store.insertExportJob(
        new ExportJob(
            id,
            pharmacy,
            "X",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 2),
            "GENERATING",
            null,
            null,
            staff,
            null,
            null,
            null,
            now));

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(pharmacy)))
        .thenReturn(1L)
        .thenReturn(0L)
        .thenReturn(null);
    assertThat(store.pharmacyExists(pharmacy)).isTrue();
    assertThat(store.pharmacyExists(pharmacy)).isFalse();
    assertThat(store.pharmacyExists(pharmacy)).isFalse();
  }

  private static ResultSet mockEntryRs(
      UUID id, UUID pharmacy, UUID rx, UUID staff, Instant now, boolean withAge) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getInt("sno")).thenReturn(1);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("schedule")).thenReturn("H1");
    when(rs.getObject("rx_id")).thenReturn(rx);
    when(rs.getString("rx_reference_no")).thenReturn("RX-2026-00001");
    when(rs.getObject("order_id")).thenReturn(null);
    when(rs.getString("patient_name")).thenReturn("P");
    when(rs.getObject("patient_age")).thenReturn(withAge ? 52 : null);
    when(rs.getInt("patient_age")).thenReturn(52);
    when(rs.getString("prescriber_name")).thenReturn("D");
    when(rs.getString("prescriber_reg_no")).thenReturn("R");
    when(rs.getString("drug_name")).thenReturn("Drug");
    when(rs.getString("batch_no")).thenReturn("B1");
    when(rs.getInt("quantity_issued")).thenReturn(30);
    when(rs.getString("unit")).thenReturn("TABLETS");
    when(rs.getInt("running_balance")).thenReturn(470);
    when(rs.getString("pharmacy_license_no")).thenReturn("LIC");
    when(rs.getString("dispensed_by_name")).thenReturn("Staff");
    when(rs.getObject("dispensed_by_user_id")).thenReturn(staff);
    when(rs.getTimestamp("dispensed_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("retention_expires_at")).thenReturn(Timestamp.from(now));
    when(rs.getBoolean("is_archived")).thenReturn(false);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
