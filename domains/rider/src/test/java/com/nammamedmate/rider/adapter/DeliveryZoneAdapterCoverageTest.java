package com.nammamedmate.rider.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.rider.adapter.in.web.AdminDeliveryZoneController;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDeliveryZoneStore;
import com.nammamedmate.rider.adapter.out.persistence.JdbcRebalancingSuggestionStore;
import com.nammamedmate.rider.application.AdminDeliveryZoneService;
import com.nammamedmate.rider.application.AdminDeliveryZoneService.ListResult;
import com.nammamedmate.rider.application.port.out.RebalancingSuggestionStore;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DeliveryZoneAdapterCoverageTest {

  @Test
  @SuppressWarnings("unchecked")
  void jdbcStoresMapRows() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    org.mockito.Mockito.lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    org.mockito.Mockito.lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any()))
        .thenReturn(1);
    org.mockito.Mockito.lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    org.mockito.Mockito.lenient()
        .when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    org.mockito.Mockito.lenient()
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
    org.mockito.Mockito.lenient()
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
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
        .thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(true);
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any()))
        .thenReturn(new BigDecimal("19.4"));
    when(jdbc.queryForObject(anyString(), eq(BigDecimal.class))).thenReturn(new BigDecimal("19.4"));

    org.mockito.Mockito.lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));
    org.mockito.Mockito.lenient()
        .when(
            jdbc.query(
                anyString(),
                any(RowMapper.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
        .thenAnswer(inv -> mapOne(inv.getArgument(1)));

    JdbcDeliveryZoneStore zones = new JdbcDeliveryZoneStore(jdbc);
    assertThat(zones.findById(Ids.newId())).isPresent();
    assertThat(zones.listPricing()).hasSize(1);
    assertThat(zones.findContaining(12.93, 77.62)).isPresent();
    assertThat(zones.existsNameInCity("n", "c", null)).isTrue();
    assertThat(zones.existsNameInCity("n", "c", Ids.newId())).isTrue();
    assertThat(zones.list(null, null, 0, 10)).hasSize(1);
    assertThat(zones.list("Bengaluru", true, 0, 10)).hasSize(1);
    zones.insert(
        Ids.newId(),
        "n",
        "c",
        "s",
        "POLYGON((0 0,1 0,1 1,0 1,0 0))",
        "{}",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        30,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        BigDecimal.ONE,
        true,
        Ids.newId(),
        Instant.now());
    zones.updateFields(
        Ids.newId(), 25, null, null, null, null, null, null, null, null, Instant.now());
    zones.updateFields(
        Ids.newId(),
        25,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.TEN,
        "n",
        "POLYGON((0 0,1 0,1 1,0 1,0 0))",
        "{}",
        BigDecimal.ONE,
        Instant.now());
    zones.updateSurge(Ids.newId(), true, new BigDecimal("1.5"), Instant.now());
    zones.updateServiceable(Ids.newId(), false, "flood", Instant.now());
    assertThat(zones.countServiceable()).isEqualTo(2);
    assertThat(zones.countOnlineRiders(Ids.newId())).isEqualTo(1);
    assertThat(zones.countOnlineRidersAll()).isEqualTo(2);
    assertThat(zones.countPharmacies(Ids.newId())).isEqualTo(1);
    assertThat(zones.count(null, null)).isEqualTo(1);
    assertThat(zones.count("Bengaluru", true)).isEqualTo(1);
    assertThat(zones.avgDeliveryMinutes(Ids.newId())).isEqualByComparingTo("19.4");
    assertThat(zones.avgDeliveryMinutesAll()).isEqualByComparingTo("19.4");
    assertThat(zones.isPharmacyAddressServiceable(Ids.newId(), 12.9, 77.6)).isTrue();
    assertThat(zones.minOrderValueForPharmacyAddress(Ids.newId(), 12.9, 77.6)).isPresent();
    assertThat(zones.demandVsSupply(Ids.newId(), Instant.now().minusSeconds(3600), Instant.now()))
        .hasSize(1);

    JdbcRebalancingSuggestionStore sug = new JdbcRebalancingSuggestionStore(jdbc);
    sug.insert(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        2,
        "r",
        new BigDecimal("88"),
        "[]",
        Instant.now().plusSeconds(100),
        Instant.now());
    assertThat(sug.listPending(Instant.now())).hasSize(1); // applied_at null
    assertThat(sug.findById(Ids.newId())).isPresent(); // applied_at non-null
    assertThat(sug.markApplied(Ids.newId(), Ids.newId(), Instant.now())).isTrue();
    org.mockito.Mockito.when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    assertThat(sug.markApplied(Ids.newId(), Ids.newId(), Instant.now())).isFalse();
    sug.expireStale(Instant.now());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    assertThat(zones.existsNameInCity("n", "c", null)).isFalse();
    assertThat(zones.list("", false, 0, 10)).hasSize(1);
    assertThat(zones.count("", false)).isEqualTo(1);

    // SpotBugs-safe null Integer → zero / false branches
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    assertThat(zones.existsNameInCity("n", "c", null)).isFalse();
    assertThat(zones.countServiceable()).isZero();
    assertThat(zones.countOnlineRiders(Ids.newId())).isZero();
    assertThat(zones.countOnlineRidersAll()).isZero();
    assertThat(zones.countPharmacies(Ids.newId())).isZero();
    assertThat(zones.count(null, null)).isZero();
  }

  @Test
  void controllerDelegates() {
    AdminDeliveryZoneService svc = mock(AdminDeliveryZoneService.class);
    when(svc.list(any(), any(), any(), any(), any()))
        .thenReturn(new ListResult(Map.of("zones", List.of()), null));
    when(svc.create(any(), any())).thenReturn(Map.of("zone_id", Ids.newId().toString()));
    when(svc.get(any(), any())).thenReturn(Map.of("zone_id", "x"));
    when(svc.patch(any(), any(), any())).thenReturn(Map.of("updated_at", "t"));
    when(svc.setSurge(any(), any(), any(), any())).thenReturn(Map.of("is_surge_active", true));
    when(svc.setServiceable(any(), any(), any(), any()))
        .thenReturn(Map.of("is_serviceable", false));
    when(svc.rebalancingSuggestions(any())).thenReturn(Map.of("suggestions", List.of()));
    when(svc.applyRebalancing(any(), any())).thenReturn(Map.of("applied", true));
    when(svc.demandVsSupply(any(), any(), any(), any()))
        .thenReturn(Map.of("chart_data", List.of()));

    AdminDeliveryZoneController ctrl = new AdminDeliveryZoneController(svc);
    MedmatePrincipal a =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    UUID id = Ids.newId();
    assertThat(ctrl.list(a, null, true, 1, 50).success()).isTrue();
    assertThat(ctrl.create(a, null).success()).isTrue();
    assertThat(
            ctrl.create(
                    a,
                    new AdminDeliveryZoneController.CreateZoneRequest(
                        "n", "c", null, Map.of(), null, null, null, null, null, null, true))
                .success())
        .isTrue();
    assertThat(ctrl.get(a, id).success()).isTrue();
    assertThat(ctrl.patch(a, id, null).success()).isTrue();
    assertThat(
            ctrl.patch(
                    a,
                    id,
                    new AdminDeliveryZoneController.PatchZoneRequest(
                        "n",
                        25,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        Map.of()))
                .success())
        .isTrue();
    assertThat(ctrl.surge(a, id, null).success()).isTrue();
    assertThat(
            ctrl.surge(
                    a,
                    id,
                    new AdminDeliveryZoneController.SurgeRequest(true, new BigDecimal("1.5")))
                .success())
        .isTrue();
    assertThat(ctrl.serviceable(a, id, null).success()).isTrue();
    assertThat(
            ctrl.serviceable(a, id, new AdminDeliveryZoneController.ServiceableRequest(false, "r"))
                .success())
        .isTrue();
    assertThat(ctrl.rebalancingSuggestions(a).success()).isTrue();
    assertThat(ctrl.applyRebalancing(a, id).success()).isTrue();
    assertThat(ctrl.demandVsSupply(a, id, LocalDate.now(), LocalDate.now()).success()).isTrue();
    assertThat(new RebalancingSuggestionStore.SuggestedRider(id, "Ravi", BigDecimal.ONE).name())
        .isEqualTo("Ravi");
  }

  private static final java.util.concurrent.atomic.AtomicInteger APPLIED_CALLS =
      new java.util.concurrent.atomic.AtomicInteger();

  private static List<?> mapOne(RowMapper<?> mapper) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    UUID id = Ids.newId();
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("from_zone_id")).thenReturn(id);
    when(rs.getObject("to_zone_id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("Koramangala");
    when(rs.getString("from_zone_name")).thenReturn("A");
    when(rs.getString("to_zone_name")).thenReturn("B");
    when(rs.getString("city")).thenReturn("Bengaluru");
    when(rs.getString("state")).thenReturn("Karnataka");
    when(rs.getString("polygon_geojson")).thenReturn("{\"type\":\"Polygon\"}");
    when(rs.getBigDecimal("area_sq_km")).thenReturn(new BigDecimal("7.2"));
    when(rs.getBigDecimal("base_fee")).thenReturn(new BigDecimal("25"));
    when(rs.getBigDecimal("per_km_fee")).thenReturn(new BigDecimal("5"));
    when(rs.getInt("sla_minutes")).thenReturn(30);
    when(rs.getBigDecimal("min_order_value")).thenReturn(new BigDecimal("50"));
    when(rs.getBigDecimal("free_delivery_threshold")).thenReturn(new BigDecimal("199"));
    when(rs.getBigDecimal("surge_multiplier")).thenReturn(new BigDecimal("1.5"));
    when(rs.getBoolean("is_surge_active")).thenReturn(false);
    when(rs.getBoolean("is_serviceable")).thenReturn(true);
    when(rs.getString("offline_reason")).thenReturn(null);
    when(rs.getBoolean("active")).thenReturn(true);
    when(rs.getObject("created_by")).thenReturn(id);
    when(rs.getObject("applied_by")).thenReturn(null);
    when(rs.getTimestamp(anyString()))
        .thenAnswer(
            inv -> {
              String col = inv.getArgument(0);
              if ("applied_at".equals(col)) {
                return APPLIED_CALLS.getAndIncrement() == 0 ? null : Timestamp.from(Instant.now());
              }
              return Timestamp.from(Instant.now());
            });
    when(rs.getInt("riders_to_move")).thenReturn(2);
    when(rs.getString("reason")).thenReturn("r");
    when(rs.getBigDecimal("confidence_pct")).thenReturn(new BigDecimal("88.4"));
    when(rs.getString("suggested_riders")).thenReturn("[]");
    when(rs.getString("status")).thenReturn("PENDING");
    when(rs.getInt("pharmacies_count")).thenReturn(1);
    when(rs.getInt("orders")).thenReturn(12);
    when(rs.getInt("online_riders")).thenReturn(5);
    when(rs.getTimestamp("hour")).thenReturn(Timestamp.from(Instant.now()));
    return List.of(mapper.mapRow(rs, 0));
  }
}
