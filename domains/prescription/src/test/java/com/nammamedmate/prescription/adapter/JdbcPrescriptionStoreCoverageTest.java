package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcCustomerNameAdapter;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcPrescriptionStore;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
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

class JdbcPrescriptionStoreCoverageTest {

  private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

  @Test
  void page_nullItems() {
    assertThat(new PrescriptionStore.Page(null, 0).items()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapRow_andListFilters() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPrescriptionStore store = new JdbcPrescriptionStore(jdbc, om);
    UUID id = UUID.randomUUID();
    UUID cust = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T07:30:00Z");

    ResultSet rs = mockRs(id, cust, now, true);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id), eq(cust)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    PrescriptionRecord loaded = store.findByIdForCustomer(id, cust).orElseThrow();
    assertThat(loaded.doctorName()).isEqualTo("Dr X");
    assertThat(loaded.medicinesExtracted()).hasSize(1);
    assertThat(loaded.deletedAt()).isNotNull();

    ResultSet rsNulls = mockRs(id, cust, now, false);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rsNulls, 0)));
    assertThat(store.findById(id).orElseThrow().prescriptionDate()).isNull();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.listForCustomer(cust, null, null, 1, 10, null, "desc").total()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    store.listForCustomer(cust, "UPLOADED", "UPLOADED", 1, 10, "created_at", "asc");

    PrescriptionRecord insert =
        new PrescriptionRecord(
            id,
            cust,
            "UPLOADED",
            "UPLOADED",
            "k",
            1,
            "image/jpeg",
            null,
            null,
            null,
            LocalDate.of(2026, 1, 1),
            "UPLOAD",
            null,
            null,
            null,
            now,
            null,
            now,
            now,
            now);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    store.insert(insert);
    store.insert(
        new PrescriptionRecord(
            UUID.randomUUID(),
            cust,
            "UPLOADED",
            "UPLOADED",
            "k2",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            now,
            null,
            now,
            now,
            null));
    store.updateOcr(id, "D", null, null, now);
    store.updateOcr(id, "D", LocalDate.of(2026, 1, 2), List.of(), now);
    store.updateStatus(id, "VERIFIED", now);
    store.softDelete(id, now, now);
    assertThat(store.markExpiredDue(now, now)).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void parseMeds_invalidAndBlank() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPrescriptionStore store = new JdbcPrescriptionStore(jdbc, om);
    UUID id = UUID.randomUUID();
    UUID cust = UUID.randomUUID();
    Instant now = Instant.now();
    ResultSet rs = mockRs(id, cust, now, false);
    when(rs.getString("medicines_extracted")).thenReturn(" ");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.findById(id).orElseThrow().medicinesExtracted()).isNull();

    when(rs.getString("medicines_extracted")).thenReturn("{bad");
    assertThat(store.findById(id).orElseThrow().medicinesExtracted()).isNull();
  }

  @Test
  void toJson_failure() throws Exception {
    ObjectMapper bad = mock(ObjectMapper.class);
    when(bad.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    JdbcPrescriptionStore store = new JdbcPrescriptionStore(mock(JdbcTemplate.class), bad);
    Instant now = Instant.now();
    PrescriptionRecord r =
        new PrescriptionRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "UPLOADED",
            "UPLOADED",
            "k",
            1,
            "image/jpeg",
            null,
            null,
            null,
            null,
            "UPLOAD",
            List.of(new MedicineExtracted("a", "1", "1", null)),
            null,
            null,
            now,
            null,
            now,
            now,
            null);
    assertThatThrownBy(() -> store.insert(r)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void customerName_blankFiltered() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    UUID id = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn("  ");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(new JdbcCustomerNameAdapter(jdbc).findName(id)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn(null);
              return java.util.Collections.singletonList(mapper.mapRow(rs, 0));
            });
    assertThat(new JdbcCustomerNameAdapter(jdbc).findName(id)).isEmpty();
  }

  private static ResultSet mockRs(UUID id, UUID cust, Instant now, boolean withOptionals)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("customer_id")).thenReturn(cust);
    when(rs.getString("type")).thenReturn("UPLOADED");
    when(rs.getString("status")).thenReturn("UPLOADED");
    when(rs.getString("s3_key")).thenReturn("prescriptions/x.jpg");
    when(rs.getLong("file_size_bytes")).thenReturn(10L);
    when(rs.getString("mime_type")).thenReturn("image/jpeg");
    when(rs.getString("patient_name")).thenReturn("Ravi");
    when(rs.getString("notes")).thenReturn("n");
    when(rs.getString("doctor_name")).thenReturn(withOptionals ? "Dr X" : null);
    when(rs.getDate("prescription_date"))
        .thenReturn(withOptionals ? Date.valueOf(LocalDate.of(2026, 7, 20)) : null);
    when(rs.getString("source")).thenReturn("UPLOAD");
    when(rs.getString("medicines_extracted"))
        .thenReturn(
            withOptionals
                ? "[{\"name\":\"M\",\"quantity\":\"1\",\"dosage\":\"1-0-0\",\"schedule\":null}]"
                : null);
    when(rs.getObject("associated_order_id")).thenReturn(withOptionals ? UUID.randomUUID() : null);
    when(rs.getObject("teleconsult_id")).thenReturn(null);
    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(100)));
    when(rs.getString("rejection_reason")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(withOptionals ? Timestamp.from(now) : null);
    return rs;
  }
}
