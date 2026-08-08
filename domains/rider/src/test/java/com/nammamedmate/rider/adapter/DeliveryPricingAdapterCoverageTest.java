package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.in.web.AdminDeliveryPricingController;
import com.nammamedmate.rider.adapter.in.web.DeliveryFeeEstimateController;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDeliveryFeeSnapshotStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDeliveryPricingLookupAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcPlatformPricingConfigStore;
import com.nammamedmate.rider.application.DeliveryPricingService;
import com.nammamedmate.rider.application.port.out.DeliveryFeeSnapshotStore.Snapshot;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DeliveryPricingAdapterCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void jdbcStoresAndControllers() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("value")).thenReturn("5.00");
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("order_id")).thenReturn(Ids.newId());
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getString("name")).thenReturn("Apollo");
              when(rs.getObject("latitude")).thenReturn(12.93);
              when(rs.getObject("longitude")).thenReturn(77.62);
              when(rs.getDouble("latitude")).thenReturn(12.93);
              when(rs.getDouble("longitude")).thenReturn(77.62);
              when(rs.getBigDecimal(anyString())).thenReturn(new BigDecimal("25.00"));
              when(rs.getBoolean("is_free_delivery")).thenReturn(false);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
              Object row = mapper.mapRow(rs, 0);
              return row == null ? List.of() : List.of(row);
            });
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("value")).thenReturn("5.00");
              Object row = mapper.mapRow(rs, 0);
              return List.of(row);
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(
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
            any()))
        .thenReturn(1);

    JdbcPlatformPricingConfigStore cfg = new JdbcPlatformPricingConfigStore(jdbc);
    assertThat(cfg.get("handling_fee")).contains("5.00");
    assertThat(cfg.handlingFeeRupees()).isEqualByComparingTo("5.00");
    cfg.upsert("handling_fee", "5.00", "d", Ids.newId(), Instant.now());

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("value")).thenReturn("nope");
              Object row = mapper.mapRow(rs, 0);
              return List.of(row);
            });
    assertThat(new JdbcPlatformPricingConfigStore(jdbc).handlingFeeRupees())
        .isEqualByComparingTo("5.00");

    JdbcDeliveryFeeSnapshotStore snaps = new JdbcDeliveryFeeSnapshotStore(jdbc);
    UUID orderId = Ids.newId();
    snaps.insert(
        new Snapshot(
            orderId,
            Ids.newId(),
            new BigDecimal("2.00"),
            new BigDecimal("25"),
            new BigDecimal("10"),
            BigDecimal.ONE,
            new BigDecimal("35"),
            new BigDecimal("5"),
            false,
            new BigDecimal("34.30"),
            Instant.now()));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("order_id")).thenReturn(orderId);
              when(rs.getObject("zone_id")).thenReturn(Ids.newId());
              when(rs.getBigDecimal(anyString())).thenReturn(new BigDecimal("1.00"));
              when(rs.getBoolean("is_free_delivery")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(snaps.findByOrderId(orderId)).isPresent();

    JdbcDeliveryPricingLookupAdapter lookup = new JdbcDeliveryPricingLookupAdapter(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("name")).thenReturn("P");
              when(rs.getObject("latitude")).thenReturn(null);
              when(rs.getObject("longitude")).thenReturn(null);
              Object row = mapper.mapRow(rs, 0);
              return row == null ? List.of() : List.of(row);
            });
    assertThat(lookup.findPharmacy(Ids.newId())).isEmpty();
    assertThat(lookup.findPharmacy(null)).isEmpty();
    assertThat(lookup.findAddress(null)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("name")).thenReturn("P");
              when(rs.getObject("latitude")).thenReturn(new BigDecimal("12.93"));
              when(rs.getObject("longitude")).thenReturn(new BigDecimal("77.62"));
              when(rs.getDouble("latitude")).thenReturn(12.93);
              when(rs.getDouble("longitude")).thenReturn(77.62);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(lookup.findPharmacy(Ids.newId())).isPresent();
    assertThat(lookup.findAddress(Ids.newId())).isPresent();

    DeliveryPricingService svc = mock(DeliveryPricingService.class);
    when(svc.listPricing(any())).thenReturn(Map.of("zones", List.of()));
    when(svc.simulate(any(), any(), any(), any())).thenReturn(Map.of("zone_id", "z"));
    when(svc.patchPricing(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("zone_id", "z"));
    when(svc.feeEstimate(any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("delivery_fee", 40.0));

    AdminDeliveryPricingController admin = new AdminDeliveryPricingController(svc);
    MedmatePrincipal a =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThat(admin.listPricing(a).success()).isTrue();
    assertThat(admin.simulate(a, null).success()).isTrue();
    assertThat(
            admin
                .simulate(
                    a,
                    new AdminDeliveryPricingController.SimulateRequest(
                        Ids.newId(), new BigDecimal("3.2"), new BigDecimal("150")))
                .success())
        .isTrue();
    assertThat(admin.patchPricing(a, Ids.newId(), null).success()).isTrue();
    assertThat(
            admin
                .patchPricing(
                    a,
                    Ids.newId(),
                    new AdminDeliveryPricingController.PatchPricingRequest(
                        new BigDecimal("30"),
                        new BigDecimal("6"),
                        25,
                        new BigDecimal("60"),
                        new BigDecimal("249")))
                .success())
        .isTrue();

    DeliveryFeeEstimateController pub = new DeliveryFeeEstimateController(svc);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader(eq("X-Forwarded-For"))).thenReturn("10.0.0.1, 10.0.0.2");
    assertThat(pub.feeEstimate(req, Ids.newId(), null, 12.9, 77.6, null).success()).isTrue();
    when(req.getHeader(eq("X-Forwarded-For"))).thenReturn("  ");
    when(req.getRemoteAddr()).thenReturn("127.0.0.1");
    assertThat(pub.feeEstimate(req, Ids.newId(), null, 12.9, 77.6, null).success()).isTrue();
    when(req.getHeader(eq("X-Forwarded-For"))).thenReturn(null);
    when(req.getRemoteAddr()).thenReturn(null);
    assertThat(pub.feeEstimate(req, Ids.newId(), Ids.newId(), null, null, BigDecimal.TEN).success())
        .isTrue();

    // string lat/lng parse path + mixed null coords
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("name")).thenReturn("P");
              when(rs.getObject("latitude")).thenReturn("12.93");
              when(rs.getObject("longitude")).thenReturn("77.62");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(lookup.findPharmacy(Ids.newId())).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("latitude")).thenReturn(12.93);
              when(rs.getObject("longitude")).thenReturn(null);
              Object row = mapper.mapRow(rs, 0);
              return row == null ? java.util.Collections.singletonList(null) : List.of(row);
            });
    assertThat(lookup.findPharmacy(Ids.newId())).isEmpty();
  }
}
