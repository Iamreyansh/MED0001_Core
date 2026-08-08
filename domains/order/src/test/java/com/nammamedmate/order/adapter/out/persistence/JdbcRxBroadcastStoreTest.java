package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.domain.QuotedMedicine;
import com.nammamedmate.order.domain.RxBroadcast;
import com.nammamedmate.order.domain.RxBroadcast.RequestedMedicine;
import com.nammamedmate.order.domain.RxBroadcastPharmacy;
import com.nammamedmate.order.domain.RxBroadcastStatus;
import com.nammamedmate.order.domain.RxPharmacySlotStatus;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class JdbcRxBroadcastStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcRxBroadcastStore store;
  private final UUID bc = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private final UUID cust = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID ph = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private final Instant now = Instant.parse("2026-08-08T10:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcRxBroadcastStore(jdbc, new ObjectMapper());
  }

  @Test
  void insertUpdateMarkExpireAndMapRows() throws Exception {
    doReturn(1).when(jdbc).update(anyString(), any(Object[].class));

    RxBroadcast broadcast =
        new RxBroadcast(
            bc,
            cust,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Ravi",
            "n",
            List.of(new RequestedMedicine("Metformin 500mg", 60)),
            RxBroadcastStatus.ACTIVE,
            1,
            now,
            now.plusSeconds(1800),
            null,
            null,
            now);
    RxBroadcastPharmacy slot =
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            ph,
            1.2,
            RxPharmacySlotStatus.NOTIFIED,
            null,
            null,
            null,
            now,
            now.plusSeconds(900),
            null,
            null,
            List.of());
    store.insert(broadcast, List.of(slot));

    RxBroadcastPharmacy quoted =
        new RxBroadcastPharmacy(
            slot.id(),
            bc,
            ph,
            1.2,
            RxPharmacySlotStatus.QUOTED,
            List.of(new QuotedMedicine("Metformin 500mg", 60, 25500)),
            22,
            28500L,
            now,
            now.plusSeconds(900),
            now,
            now.plusSeconds(1200),
            List.of("FASTEST"));
    store.updatePharmacySlot(quoted);
    store.markSelected(bc, ph, UUID.randomUUID());
    store.updateBroadcastStatus(bc, RxBroadcastStatus.EXPIRED);
    store.updatePharmacyStatus(slot.id(), RxPharmacySlotStatus.EXPIRED);
    assertThat(store.expirePharmacySlots(now)).isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> mapQuery(inv.getArgument(0), inv.getArgument(1), quoted));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> mapQuery(inv.getArgument(0), inv.getArgument(1), quoted));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(bc))).thenReturn(2);

    assertThat(store.expireBroadcasts(now)).hasSize(1);
    assertThat(store.findById(bc)).isPresent();
    assertThat(store.findByIdForCustomer(bc, cust)).isPresent();
    assertThat(store.listPharmacies(bc)).hasSize(1);
    assertThat(store.findPharmacySlot(bc, ph)).isPresent();
    assertThat(store.listPendingForPharmacy(ph)).hasSize(1);
    assertThat(store.countQuoted(bc)).isEqualTo(2);

    doReturn(0).when(jdbc).update(anyString(), any(Object[].class));
    assertThatThrownBy(() -> store.updatePharmacySlot(quoted))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void emptyResultsAndTagVariants() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.findById(bc)).isEmpty();
    assertThat(store.findByIdForCustomer(bc, cust)).isEmpty();
    assertThat(store.findPharmacySlot(bc, ph)).isEmpty();
    assertThat(store.expireBroadcasts(now)).isEmpty();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(bc))).thenReturn(null);
    assertThat(store.countQuoted(bc)).isZero();

    ResultSet rs =
        mockPharmacyRs(
            new RxBroadcastPharmacy(
                UUID.randomUUID(),
                bc,
                ph,
                1.0,
                RxPharmacySlotStatus.NOTIFIED,
                null,
                null,
                null,
                now,
                now.plusSeconds(900),
                null,
                null,
                List.of()));
    when(rs.getString("medicines_available")).thenReturn(null);
    when(rs.getArray("tags")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(bc)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.listPharmacies(bc).getFirst().tags()).isEmpty();

    Array arr = mock(Array.class);
    when(arr.getArray()).thenReturn(new Object[] {"FASTEST", null});
    when(rs.getArray("tags")).thenReturn(arr);
    assertThat(store.listPharmacies(bc).getFirst().tags()).containsExactly("FASTEST");

    when(arr.getArray()).thenReturn(new String[] {"LOWEST_PRICE"});
    assertThat(store.listPharmacies(bc).getFirst().tags()).containsExactly("LOWEST_PRICE");

    when(arr.getArray()).thenReturn(new int[] {1});
    assertThat(store.listPharmacies(bc).getFirst().tags()).isEmpty();

    ResultSet nullReq = mockBroadcastRs();
    when(nullReq.getString("medicines_requested")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(bc)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(nullReq, 0)));
    assertThat(store.findById(bc)).isPresent();

    ResultSet blankReq = mockBroadcastRs();
    when(blankReq.getString("medicines_requested")).thenReturn("   ");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(bc)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(blankReq, 0)));
    assertThat(store.findById(bc)).isPresent();

    ResultSet badReq = mockBroadcastRs();
    when(badReq.getString("medicines_requested")).thenReturn("not-json");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(bc)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(badReq, 0)));
    assertThatThrownBy(() -> store.findById(bc)).isInstanceOf(IllegalStateException.class);

    ResultSet blankQuoted =
        mockPharmacyRs(
            new RxBroadcastPharmacy(
                UUID.randomUUID(),
                bc,
                ph,
                1,
                RxPharmacySlotStatus.QUOTED,
                List.of(new QuotedMedicine("A", 1, 1)),
                1,
                1L,
                now,
                now.plusSeconds(1),
                now,
                now.plusSeconds(2),
                List.of("FASTEST")));
    when(blankQuoted.getString("medicines_available")).thenReturn(" ");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(bc)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(blankQuoted, 0)));
    assertThat(store.listPharmacies(bc).getFirst().medicinesAvailable()).isNull();

    ResultSet badQuoted =
        mockPharmacyRs(
            new RxBroadcastPharmacy(
                UUID.randomUUID(),
                bc,
                ph,
                1,
                RxPharmacySlotStatus.QUOTED,
                List.of(new QuotedMedicine("A", 1, 1)),
                1,
                1L,
                now,
                now.plusSeconds(1),
                now,
                now.plusSeconds(2),
                List.of()));
    when(badQuoted.getString("medicines_available")).thenReturn("{");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(bc)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(badQuoted, 0)));
    assertThatThrownBy(() -> store.listPharmacies(bc)).isInstanceOf(IllegalStateException.class);

    ObjectMapper failing =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.databind.JsonMappingException(
                (com.fasterxml.jackson.core.JsonParser) null, "boom");
          }
        };
    JdbcRxBroadcastStore broken = new JdbcRxBroadcastStore(jdbc, failing);
    doReturn(1).when(jdbc).update(anyString(), any(Object[].class));
    assertThatThrownBy(
            () ->
                broken.insert(
                    new RxBroadcast(
                        bc,
                        cust,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Ravi",
                        null,
                        List.of(new RequestedMedicine("A", 1)),
                        RxBroadcastStatus.ACTIVE,
                        1,
                        now,
                        now.plusSeconds(1),
                        null,
                        null,
                        now),
                    List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                broken.updatePharmacySlot(
                    new RxBroadcastPharmacy(
                        UUID.randomUUID(),
                        bc,
                        ph,
                        1,
                        RxPharmacySlotStatus.QUOTED,
                        List.of(new QuotedMedicine("A", 1, 1)),
                        1,
                        1L,
                        now,
                        now.plusSeconds(1),
                        now,
                        now.plusSeconds(2),
                        List.of("T"))))
        .isInstanceOf(IllegalStateException.class);

    // insert/update with non-null quoted timestamps + tags
    store.insert(
        new RxBroadcast(
            UUID.randomUUID(),
            cust,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Ravi",
            null,
            List.of(),
            RxBroadcastStatus.ACTIVE,
            1,
            now,
            now.plusSeconds(1),
            null,
            null,
            now),
        List.of(
            new RxBroadcastPharmacy(
                UUID.randomUUID(),
                bc,
                ph,
                1.0,
                RxPharmacySlotStatus.QUOTED,
                List.of(new QuotedMedicine("A", 1, 100)),
                10,
                3100L,
                now,
                now.plusSeconds(900),
                now,
                now.plusSeconds(1200),
                List.of("FASTEST"))));
    store.updatePharmacySlot(
        new RxBroadcastPharmacy(
            UUID.randomUUID(),
            bc,
            ph,
            1.0,
            RxPharmacySlotStatus.QUOTED,
            List.of(new QuotedMedicine("A", 1, 100)),
            10,
            3100L,
            now,
            now.plusSeconds(900),
            null,
            null,
            null));
  }

  private List<?> mapQuery(String sql, RowMapper<?> mapper, RxBroadcastPharmacy quoted)
      throws Exception {
    if (sql.contains("rx_broadcast_pharmacies")) {
      return List.of(mapper.mapRow(mockPharmacyRs(quoted), 0));
    }
    return List.of(mapper.mapRow(mockBroadcastRs(), 0));
  }

  private ResultSet mockBroadcastRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(bc);
    when(rs.getObject("customer_id")).thenReturn(cust);
    when(rs.getObject("prescription_id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("delivery_address_id")).thenReturn(UUID.randomUUID());
    when(rs.getString("patient_name")).thenReturn("Ravi");
    when(rs.getString("notes")).thenReturn(null);
    when(rs.getString("medicines_requested"))
        .thenReturn("[{\"name\":\"Metformin 500mg\",\"quantity\":60}]");
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getInt("pharmacies_notified")).thenReturn(1);
    when(rs.getTimestamp("broadcast_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(now.plusSeconds(1800)));
    when(rs.getObject("selected_pharmacy_id")).thenReturn(null);
    when(rs.getObject("resulting_cart_id")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private ResultSet mockPharmacyRs(RxBroadcastPharmacy p) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(p.id());
    when(rs.getObject("broadcast_id")).thenReturn(p.broadcastId());
    when(rs.getObject("pharmacy_id")).thenReturn(p.pharmacyId());
    when(rs.getBigDecimal("distance_km")).thenReturn(BigDecimal.valueOf(p.distanceKm()));
    when(rs.getString("status")).thenReturn(p.status().name());
    when(rs.getString("medicines_available"))
        .thenReturn(
            p.medicinesAvailable() == null
                ? null
                : "[{\"name\":\"Metformin 500mg\",\"quantity\":60,\"price_paise\":25500}]");
    when(rs.getObject("delivery_eta_minutes")).thenReturn(p.deliveryEtaMinutes());
    when(rs.getObject("total_payable_paise")).thenReturn(p.totalPayablePaise());
    when(rs.getTimestamp("received_at")).thenReturn(Timestamp.from(p.receivedAt()));
    when(rs.getTimestamp("response_deadline")).thenReturn(Timestamp.from(p.responseDeadline()));
    when(rs.getTimestamp("quoted_at"))
        .thenReturn(p.quotedAt() == null ? null : Timestamp.from(p.quotedAt()));
    when(rs.getTimestamp("quote_expires_at"))
        .thenReturn(p.quoteExpiresAt() == null ? null : Timestamp.from(p.quoteExpiresAt()));
    Array tags = mock(Array.class);
    when(tags.getArray()).thenReturn(p.tags().toArray(String[]::new));
    when(rs.getArray("tags")).thenReturn(p.tags().isEmpty() ? null : tags);
    return rs;
  }
}
