package com.nammamedmate.prescription.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.prescription.domain.EPrescriptionRecord;
import com.nammamedmate.prescription.domain.EPrescriptionSignature.MedicinePrescribed;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEPrescriptionStoreTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final JdbcEPrescriptionStore store = new JdbcEPrescriptionStore(jdbc, new ObjectMapper());

  @Test
  void nextRxSequenceAndInsertAndUpdatePdf() {
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
    assertThat(store.nextRxSequence()).isEqualTo(42L);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(store.nextRxSequence()).isEqualTo(1L);

    Instant now = Instant.parse("2026-07-24T10:40:00Z");
    EPrescriptionRecord record =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-20260724-NMM-000042",
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Dr",
            "Pat",
            List.of(new MedicinePrescribed("M", "1", "od", 1, "ml", 3, "n")),
            false,
            null,
            "clinical",
            "hash",
            true,
            "VERIFIED",
            "VERIFIED",
            "eprescriptions/RX-20260724-NMM-000042.pdf",
            null,
            null,
            0,
            null,
            now,
            now.plusSeconds(100),
            now,
            now,
            null);
    store.insert(record);
    verify(jdbc).update(anyString(), any(Object[].class));

    store.updatePdf(record.id(), "eprescriptions/x.pdf", 10, now, now);
    verify(jdbc)
        .update(
            anyString(),
            eq("eprescriptions/x.pdf"),
            eq("eprescriptions/x.pdf"),
            eq(10L),
            any(Timestamp.class),
            any(Timestamp.class),
            eq(record.id()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void findMethodsMapRowAndParseBranches() throws Exception {
    Instant now = Instant.parse("2026-07-24T10:40:00Z");
    ResultSet rs = mock(ResultSet.class);
    UUID id = Ids.newId();
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("rx_id")).thenReturn("RX-1");
    when(rs.getObject("customer_id")).thenReturn(Ids.newId());
    when(rs.getObject("teleconsult_id")).thenReturn(Ids.newId());
    when(rs.getObject("doctor_id")).thenReturn(Ids.newId());
    when(rs.getString("doctor_name")).thenReturn("Dr");
    when(rs.getString("patient_name")).thenReturn("Pat");
    when(rs.getString("medicines"))
        .thenReturn(
            "[{\"name\":\"A\",\"dosage\":\"1\",\"frequency\":\"od\",\"quantity\":2,\"unit\":\"ml\",\"duration_days\":3,\"notes\":\"n\"}]");
    when(rs.getBoolean("is_advice_only")).thenReturn(false);
    when(rs.getString("advice_text")).thenReturn(null);
    when(rs.getString("clinical_notes")).thenReturn(null);
    when(rs.getString("digital_signature_hash")).thenReturn("h");
    when(rs.getBoolean("is_verified")).thenReturn(true);
    when(rs.getString("seal")).thenReturn("VERIFIED");
    when(rs.getString("status")).thenReturn("VERIFIED");
    when(rs.getString("s3_key")).thenReturn("k");
    when(rs.getString("pdf_s3_key")).thenReturn(null);
    when(rs.getTimestamp("pdf_generated_at")).thenReturn(null);
    when(rs.getLong("file_size_bytes")).thenReturn(0L);
    when(rs.getObject("associated_order_id")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(10)));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EPrescriptionRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<EPrescriptionRecord> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });

    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByIdForCustomer(id, Ids.newId())).isPresent();
    assertThat(store.findByTeleconsultId(Ids.newId())).isPresent();

    when(rs.getString("medicines")).thenReturn("not-json");
    assertThat(store.findById(id).orElseThrow().medicines()).isEmpty();
    when(rs.getString("medicines")).thenReturn("");
    assertThat(store.findById(id).orElseThrow().medicines()).isEmpty();
    when(rs.getString("medicines")).thenReturn(null);
    assertThat(store.findById(id).orElseThrow().medicines()).isEmpty();
    when(rs.getString("medicines"))
        .thenReturn(
            "[{\"name\":null,\"dosage\":null,\"frequency\":null,\"quantity\":null,\"unit\":null},"
                + "{\"name\":\"B\",\"dosage\":\"1\",\"frequency\":\"od\",\"quantity\":\"x\",\"unit\":\"ml\"},"
                + "{\"name\":\"C\",\"dosage\":\"1\",\"frequency\":\"od\",\"quantity\":2.5,\"unit\":\"ml\",\"notes\":null}]");
    assertThat(store.findById(id).orElseThrow().medicines()).hasSize(3);

    when(rs.getTimestamp("pdf_generated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
    assertThat(store.findById(id).orElseThrow().pdfGeneratedAt()).isEqualTo(now);
  }

  @Test
  void insertFailsWhenObjectMapperCannotSerialize() throws Exception {
    ObjectMapper boom = mock(ObjectMapper.class);
    when(boom.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    JdbcEPrescriptionStore broken = new JdbcEPrescriptionStore(jdbc, boom);
    Instant now = Instant.now();
    EPrescriptionRecord withMeds =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX",
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Dr",
            "Pat",
            List.of(new MedicinePrescribed("M", "1", "od", 1, "ml", null, null)),
            false,
            null,
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            null,
            null,
            0,
            null,
            now,
            now,
            now,
            now,
            null);
    assertThatThrownBy(() -> broken.insert(withMeds)).isInstanceOf(IllegalStateException.class);

    EPrescriptionRecord adviceOnly =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-A",
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Dr",
            "Pat",
            List.of(),
            true,
            "advice",
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            null,
            null,
            0,
            null,
            now,
            now,
            now,
            now,
            null);
    // empty medicines → toJsonMedicines still called; boom mapper still throws
    assertThatThrownBy(() -> broken.insert(adviceOnly)).isInstanceOf(IllegalStateException.class);

    JdbcEPrescriptionStore okStore = new JdbcEPrescriptionStore(jdbc, new ObjectMapper());
    okStore.insert(adviceOnly);
    EPrescriptionRecord withPdfMeta =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-P",
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Dr",
            "Pat",
            List.of(new MedicinePrescribed("M", "1", "od", 1, "", null, null)),
            false,
            null,
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            "k",
            now,
            1,
            null,
            now,
            now,
            now,
            now,
            null);
    okStore.insert(withPdfMeta);
    EPrescriptionRecord nullUnit =
        new EPrescriptionRecord(
            Ids.newId(),
            "RX-U",
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "Dr",
            "Pat",
            List.of(new MedicinePrescribed("M", "1", "od", 1, null, null, null)),
            false,
            null,
            null,
            "h",
            true,
            "VERIFIED",
            "VERIFIED",
            "k",
            null,
            null,
            0,
            null,
            now,
            now,
            now,
            now,
            null);
    okStore.insert(nullUnit);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), any(Object[].class));

    assertThat(JdbcEPrescriptionStore.intVal(null)).isZero();
    assertThat(JdbcEPrescriptionStore.intVal(3)).isEqualTo(3);
    assertThat(JdbcEPrescriptionStore.intVal("9")).isEqualTo(9);
    assertThat(JdbcEPrescriptionStore.intVal("nope")).isZero();
    assertThat(JdbcEPrescriptionStore.str(null)).isEmpty();
    assertThat(JdbcEPrescriptionStore.str("a")).isEqualTo("a");
  }
}
