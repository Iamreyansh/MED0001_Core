package com.nammamedmate.prescription.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcCustomerContactAdapter;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcPharmacyRxQueueStore;
import com.nammamedmate.prescription.application.port.out.CustomerContactPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPharmacyRxQueueStoreCoverageTest {

  private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

  @Test
  void pageNullItems() {
    assertThat(new PharmacyRxQueueStore.Page(null, 0).items()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void insertFindListKpisAndMutations() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcPharmacyRxQueueStore store = new JdbcPharmacyRxQueueStore(jdbc, om);
    UUID id = UUID.randomUUID();
    UUID rx = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T07:30:00Z");

    store.insert(
        entry(
            id,
            rx,
            pharmacy,
            now,
            List.of(new ApprovedMedicine("Metformin", 2, new BigDecimal("10.00"), "H1"))));
    store.insert(
        entry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            pharmacy,
            now,
            List.of(new ApprovedMedicine("Plain", 1, BigDecimal.ONE, "  "))));
    store.insert(entry(UUID.randomUUID(), UUID.randomUUID(), pharmacy, now, null));
    store.insert(
        new PharmacyRxQueueEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            pharmacy,
            UUID.randomUUID(),
            now,
            "APPROVED",
            List.of(new ApprovedMedicine("A", 1, BigDecimal.ONE)),
            UUID.randomUUID(),
            now,
            "ILLEGIBLE",
            "m",
            UUID.randomUUID(),
            now,
            UUID.randomUUID(),
            now,
            "n",
            true,
            now,
            now,
            now,
            null));

    ResultSet rs =
        mockRs(
            id,
            rx,
            pharmacy,
            now,
            "[{\"name\":\"Metformin\",\"quantity\":2,\"price\":10.00,\"schedule\":\"H1\"}]");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(pharmacy)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.findByRxAndPharmacy(rx, pharmacy)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.findLatestByRxId(rx)).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(pharmacy, null, null, null, 1, 20, null).total()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    store.list(pharmacy, "PENDING_REVIEW", "DIGITAL", "Met", 1, 20, "urgency");
    store.list(pharmacy, null, "UPLOADED", " ", 1, 20, "received_at");
    store.list(pharmacy, null, null, null, 1, 20, "patient_name");
    store.list(pharmacy, null, null, null, 1, 20, "other");

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any()))
        .thenReturn(List.of());
    assertThat(store.computeKpis(pharmacy, now).pendingReview()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet r1 = mock(ResultSet.class);
              when(r1.getString(1)).thenReturn("[{\"name\":\"x\",\"quantity\":1,\"price\":12.5}]");
              ResultSet r2 = mock(ResultSet.class);
              when(r2.getString(1))
                  .thenReturn("[{\"name\":\"y\",\"quantity\":\"bad\",\"price\":\"3.25\"}]");
              ResultSet r3 = mock(ResultSet.class);
              when(r3.getString(1)).thenReturn("not-json");
              ResultSet r4 = mock(ResultSet.class);
              when(r4.getString(1)).thenReturn("[{\"name\":null,\"quantity\":1}]");
              ResultSet r5 = mock(ResultSet.class);
              when(r5.getString(1)).thenReturn("   ");
              return List.of(
                  mapper.mapRow(r1, 0),
                  mapper.mapRow(r2, 0),
                  mapper.mapRow(r3, 0),
                  mapper.mapRow(r4, 0),
                  mapper.mapRow(r5, 0));
            });
    PharmacyRxQueueStore.Kpis kpis = store.computeKpis(pharmacy, now);
    assertThat(kpis.digitalSharePct()).isGreaterThanOrEqualTo(0);

    store.markApproved(
        id, List.of(new ApprovedMedicine("x", 1, null)), UUID.randomUUID(), now, "n", true, now);
    store.markRejected(id, "ILLEGIBLE", "msg", UUID.randomUUID(), now, now);
    store.markDispensed(id, UUID.randomUUID(), now, now);
    store.markOverdueNotified(id, now, now);

    ResultSet rsBad =
        mockRs(id, rx, pharmacy, now, "[{\"name\":\"z\",\"quantity\":1,\"price\":\"nope\"}]");
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rsBad, 0)));
    assertThat(store.findPendingOverdueUnnotified(now, 10)).hasSize(1);

    ResultSet rsNullMeds = mockRs(id, rx, pharmacy, now, null);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(rx), eq(pharmacy)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rsNullMeds, 0)));
    assertThat(store.findByRxAndPharmacy(rx, pharmacy).orElseThrow().approvedMedicines()).isNull();

    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any(), any()))
        .thenReturn(false);
    assertThat(store.hasDuplicateDispense(UUID.randomUUID(), "Metformin", now, rx)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any(), any()))
        .thenReturn(null);
    assertThat(store.hasDuplicateDispense(UUID.randomUUID(), "Metformin", now, rx)).isFalse();

    ObjectMapper exploding =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("boom") {};
          }
        };
    JdbcPharmacyRxQueueStore explodingStore = new JdbcPharmacyRxQueueStore(jdbc, exploding);
    explodingStore.insert(
        entry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            pharmacy,
            now,
            List.of(new ApprovedMedicine(null, 1, null))));
  }

  @Test
  @SuppressWarnings("unchecked")
  void customerContactAdapter() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCustomerContactAdapter adapter = new JdbcCustomerContactAdapter(jdbc);
    UUID cust = UUID.randomUUID();
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("name")).thenReturn("A");
    when(rs.getString("phone")).thenReturn("1");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(cust)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(adapter.find(cust)).contains(new CustomerContactPort.Contact("A", "1"));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(cust), any())).thenReturn(3);
    assertThat(adapter.previousOrdersCount(cust, UUID.randomUUID())).isEqualTo(3);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(cust), any())).thenReturn(null);
    assertThat(adapter.previousOrdersCount(cust, UUID.randomUUID())).isZero();
  }

  private static PharmacyRxQueueEntry entry(
      UUID id, UUID rx, UUID pharmacy, Instant now, List<ApprovedMedicine> meds) {
    return new PharmacyRxQueueEntry(
        id,
        rx,
        pharmacy,
        null,
        now,
        "PENDING_REVIEW",
        meds,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "n",
        false,
        null,
        now,
        now,
        null);
  }

  private static ResultSet mockRs(UUID id, UUID rx, UUID pharmacy, Instant now, String medsJson)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("rx_id")).thenReturn(rx);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("order_id")).thenReturn(null);
    when(rs.getTimestamp("received_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("status")).thenReturn("PENDING_REVIEW");
    when(rs.getString("approved_medicines")).thenReturn(medsJson);
    when(rs.getObject("approved_by")).thenReturn(null);
    when(rs.getTimestamp("approved_at")).thenReturn(null);
    when(rs.getString("rejected_reason")).thenReturn(null);
    when(rs.getString("rejected_custom_message")).thenReturn(null);
    when(rs.getObject("rejected_by")).thenReturn(null);
    when(rs.getTimestamp("rejected_at")).thenReturn(null);
    when(rs.getObject("dispensed_by")).thenReturn(null);
    when(rs.getTimestamp("dispensed_at")).thenReturn(null);
    when(rs.getString("notes")).thenReturn("n");
    when(rs.getBoolean("duplicate_warning")).thenReturn(false);
    when(rs.getTimestamp("overdue_notified_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
