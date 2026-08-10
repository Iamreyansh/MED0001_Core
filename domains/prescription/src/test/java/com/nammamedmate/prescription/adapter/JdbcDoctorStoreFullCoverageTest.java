package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcDoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore.ListFilter;
import com.nammamedmate.prescription.domain.DoctorRecord;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcDoctorStoreFullCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void readsListCategoriesAndSchedules() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcDoctorStore store = new JdbcDoctorStore(jdbc);
    UUID id = Ids.newId();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");

    ResultSet rs = mockDoctorRs(id, now, false);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), eq("MH1")))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByRegistrationNo("MH1")).isPresent();

    ResultSet linkRs = mock(ResultSet.class);
    when(linkRs.getObject("rx_id")).thenReturn(Ids.newId());
    when(linkRs.getObject("doctor_id")).thenReturn(id);
    when(linkRs.getBoolean("unrecognized_qualification")).thenReturn(true);
    when(linkRs.getBoolean("pending_blacklist_flag")).thenReturn(true);
    when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(linkRs, 0)));
    assertThat(store.findLink(Ids.newId())).isPresent();
    assertThat(store.listRxIdsForDoctor(id)).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id)))
        .thenReturn(null)
        .thenReturn(4);
    assertThat(store.countRxForDoctor(id)).isZero();
    assertThat(store.countRxForDoctor(id)).isEqualTo(4);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(id), any()))
        .thenReturn(null)
        .thenReturn(9L);
    assertThat(store.countScheduleEventsSince(id, now)).isZero();
    assertThat(store.countScheduleEventsSince(id, now)).isEqualTo(9L);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(id))).thenReturn(null).thenReturn(2L);
    assertThat(store.associatedOrdersCount(id)).isZero();
    assertThat(store.associatedOrdersCount(id)).isEqualTo(2L);

    when(jdbc.queryForList(anyString(), eq(id)))
        .thenReturn(
            List.of(
                Map.of("drug_name", "Metformin"),
                Map.of("drug_name", "Amoxicillin"),
                Map.of("drug_name", "Alprazolam"),
                Map.of("drug_name", "Vitamin"),
                Map.of("drug_name", "")))
        .thenReturn(
            List.of(
                Map.of("sch", "H"),
                Map.of("sch", "H1"),
                Map.of("sch", "X"),
                Map.of("sch", "NONE"),
                Map.of()));
    assertThat(store.prescriptionCategoryCounts(id))
        .containsKeys("Antidiabetics", "Antibiotics", "Anxiolytics", "Other");
    assertThat(store.scheduleCounts(id)).isEqualTo(new DoctorStore.ScheduleCounts(1, 1, 1));

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(new ListFilter(null, null, null, 1, 20, "bogus", "asc")).total())
        .isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSet verified = mockDoctorRs(id, now, true);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(verified, 0));
            });
    assertThat(
            store
                .list(new ListFilter("Dr", "GP", "VERIFIED", 1, 20, "verified_at", "desc"))
                .items())
        .hasSize(1);
    assertThat(store.listUnverified(1, 20).items()).hasSize(1);

    DoctorRecord d =
        new DoctorRecord(
            id,
            "MH1",
            "Dr",
            null,
            null,
            "UNVERIFIED",
            "OCR",
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            null);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    store.insert(d);
    store.update(d);
    // exercise ts(null) via verified/blacklist nulls already; also deleted_at set
    DoctorRecord withDeleted =
        new DoctorRecord(
            id,
            "MH1",
            "Dr",
            null,
            null,
            "UNVERIFIED",
            "OCR",
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            now);
    store.update(withDeleted);
  }

  private static ResultSet mockDoctorRs(UUID id, Instant now, boolean withVerified)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("registration_no")).thenReturn("MH1");
    when(rs.getString("name")).thenReturn("Dr X");
    when(rs.getString("qualification")).thenReturn("MBBS");
    when(rs.getString("specialty")).thenReturn("GP");
    when(rs.getString("status")).thenReturn(withVerified ? "VERIFIED" : "UNVERIFIED");
    when(rs.getString("source")).thenReturn("OCR");
    when(rs.getInt("prescription_count")).thenReturn(1);
    when(rs.getInt("scheduled_drug_count")).thenReturn(0);
    when(rs.getString("verification_method")).thenReturn(withVerified ? "MANUAL" : null);
    when(rs.getObject("verified_by")).thenReturn(withVerified ? Ids.newId() : null);
    when(rs.getTimestamp("verified_at")).thenReturn(withVerified ? Timestamp.from(now) : null);
    when(rs.getString("verification_notes")).thenReturn(null);
    when(rs.getString("blacklist_reason")).thenReturn(null);
    when(rs.getObject("blacklisted_by")).thenReturn(null);
    when(rs.getTimestamp("blacklisted_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }
}
