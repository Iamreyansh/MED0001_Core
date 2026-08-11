package com.nammamedmate.analytics.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelReasonRow;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.CancelSummary;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.DeliverySegment;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.OpsTotals;
import com.nammamedmate.analytics.application.port.out.PlatformOpsStore.ZoneDeliveryRow;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
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
class JdbcPlatformOpsStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final Instant from = Instant.parse("2026-07-24T00:00:00Z");
  private final Instant to = Instant.parse("2026-07-25T00:00:00Z");
  private final LocalDate day = LocalDate.of(2026, 7, 24);
  private final UUID zoneId = UUID.randomUUID();

  @Test
  @SuppressWarnings("unchecked")
  void coversReadsRefreshAndNormalize() throws Exception {
    when(rs.getLong(anyString())).thenReturn(10L);
    when(rs.getInt(anyString())).thenReturn(45);
    when(rs.getDouble(anyString())).thenReturn(7.5);
    when(rs.getString(anyString())).thenReturn("Indiranagar");
    when(rs.getString("reason")).thenReturn("out_of_stock");
    when(rs.getString("actor")).thenReturn("pharmacy");
    when(rs.getString("reason_raw")).thenReturn("out_of_stock");
    when(rs.getString("actor_raw")).thenReturn("PHARMACY");
    when(rs.getString("name")).thenReturn("Apollo");
    when(rs.getString("zone_name")).thenReturn("Indiranagar");
    when(rs.getObject("zone_id")).thenReturn(zoneId);
    when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
    when(rs.getObject(1)).thenReturn(zoneId);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(47L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(5L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(5L);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));

    lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    lenient().when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    lenient().when(jdbc.update(anyString(), eq(Date.valueOf(day)))).thenReturn(1);
    lenient()
        .when(
            jdbc.update(
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
                any()))
        .thenReturn(1);
    lenient()
        .when(
            jdbc.update(
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
                any()))
        .thenReturn(1);

    JdbcPlatformOpsStore store = new JdbcPlatformOpsStore(jdbc);

    assertThat(store.zoneExists(zoneId)).isTrue();
    assertThat(store.liveOrdersNow(null)).isEqualTo(47L);
    assertThat(store.liveOrdersNow(zoneId)).isEqualTo(5L);

    OpsTotals live = store.liveOps(from, to, null);
    assertThat(live.ordersPlaced()).isEqualTo(10L);
    assertThat(store.liveOps(from, to, zoneId).ordersPlaced()).isEqualTo(10L);
    assertThat(store.aggregatedOps(day, day, null).ordersPlaced()).isEqualTo(10L);
    assertThat(store.aggregatedOps(day, day, zoneId).fillDenom()).isGreaterThanOrEqualTo(0L);

    DeliverySegment platform = store.liveDeliveryPlatform(from, to);
    assertThat(platform.pharmacyPrep().p50()).isEqualByComparingTo("7.5");
    List<ZoneDeliveryRow> zones = store.liveDeliveryByZone(from, to);
    assertThat(zones).isNotEmpty();

    CancelSummary liveCancel = store.liveCancellations(from, to, null);
    assertThat(liveCancel.totalCancellations()).isEqualTo(5L);
    CancelSummary aggCancel = store.aggregatedCancellations(from, to, zoneId);
    assertThat(aggCancel.byReason()).isNotEmpty();

    store.refreshOpsSnapshots(day, day);
    verify(jdbc).update(anyString(), eq(Date.valueOf(day)));

    assertThat(JdbcPlatformOpsStore.normalizeReason("wrong_items", "CUSTOMER"))
        .containsExactly("wrong_address", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason("cannot_fulfil", "PHARMACY"))
        .containsExactly("incomplete_prescription", "PHARMACY");
    assertThat(JdbcPlatformOpsStore.normalizeReason("timeout", "SYSTEM"))
        .containsExactly("timeout", "SYSTEM");
    assertThat(JdbcPlatformOpsStore.normalizeReason("other", "PHARMACY"))
        .containsExactly("out_of_stock", "PHARMACY");
    assertThat(JdbcPlatformOpsStore.normalizeReason("other", "CUSTOMER"))
        .containsExactly("changed_mind", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason("other", "ADMIN"))
        .containsExactly("changed_mind", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason("pharmacy_delay", "SYSTEM"))
        .containsExactly("timeout", "SYSTEM");
    assertThat(JdbcPlatformOpsStore.normalizeReason("weird", "SYSTEM"))
        .containsExactly("timeout", "SYSTEM");
    assertThat(JdbcPlatformOpsStore.normalizeReason("weird", "PHARMACY"))
        .containsExactly("out_of_stock", "PHARMACY");
    assertThat(JdbcPlatformOpsStore.normalizeReason("weird", "CUSTOMER"))
        .containsExactly("changed_mind", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason("weird", "ADMIN"))
        .containsExactly("changed_mind", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason(null, null))
        .containsExactly("timeout", "SYSTEM");
    assertThat(JdbcPlatformOpsStore.normalizeReason("changed_mind", "CUSTOMER"))
        .containsExactly("changed_mind", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason("duplicate_order", "CUSTOMER"))
        .containsExactly("duplicate_order", "CUSTOMER");
    assertThat(JdbcPlatformOpsStore.normalizeReason("closing_soon", "PHARMACY"))
        .containsExactly("closing_soon", "PHARMACY");
    assertThat(JdbcPlatformOpsStore.normalizeReason("incomplete_prescription", "PHARMACY"))
        .containsExactly("incomplete_prescription", "PHARMACY");
    assertThat(JdbcPlatformOpsStore.normalizeReason("no_rider_available", "SYSTEM"))
        .containsExactly("no_rider_available", "SYSTEM");
    assertThat(JdbcPlatformOpsStore.normalizeReason("payment_failed", "SYSTEM"))
        .containsExactly("payment_failed", "SYSTEM");
    assertThat(
            JdbcPlatformOpsStore.mergeNormalizedReasons(
                List.of(
                    new CancelReasonRow("wrong_items", "CUSTOMER", 2),
                    new CancelReasonRow("wrong_address", "CUSTOMER", 3))))
        .hasSize(1)
        .first()
        .extracting(CancelReasonRow::count)
        .isEqualTo(5L);
    // equal counts exercise Long.compare tie branch in sort
    assertThat(
            JdbcPlatformOpsStore.mergeNormalizedReasons(
                List.of(
                    new CancelReasonRow("changed_mind", "CUSTOMER", 2),
                    new CancelReasonRow("duplicate_order", "CUSTOMER", 2),
                    new CancelReasonRow("pharmacy_delay", "PHARMACY", 5))))
        .hasSize(3);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.zoneExists(zoneId)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    assertThat(store.zoneExists(zoneId)).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(store.liveOrdersNow(null)).isZero();

    // empty query paths
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.liveDeliveryPlatform(from, to).total().p50())
        .isEqualByComparingTo(BigDecimal.ZERO.setScale(1));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.liveOps(from, to, null).ordersPlaced()).isZero();

    // aggregated without zone + live with zone (covers zoneFilter branches)
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.aggregatedCancellations(from, to, null).totalCancellations()).isEqualTo(2L);
    assertThat(store.liveCancellations(from, to, zoneId).totalCancellations()).isEqualTo(2L);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.liveCancellations(from, to, null).totalCancellations()).isZero();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(null);
    assertThat(store.liveOps(from, to, null).ordersPlaced()).isZero();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(null);
    assertThat(store.aggregatedOps(day, day, null).ordersPlaced()).isZero();
  }
}
