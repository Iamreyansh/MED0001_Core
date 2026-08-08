package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
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
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcStoresCoverageTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPharmacyCandidateStore pharmacyStore;
  private JdbcInventoryAvailabilityAdapter inventory;
  private final UUID id = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private final UUID med = UUID.fromString("22222222-2222-4222-8222-222222222221");

  @BeforeEach
  void setUp() {
    pharmacyStore = new JdbcPharmacyCandidateStore(jdbc, new ObjectMapper());
    inventory = new JdbcInventoryAvailabilityAdapter(jdbc);
  }

  @Test
  void findOpenNearPoleAndNullGeoAndAddressVariants() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRow> mapper = inv.getArgument(1);
              ResultSet nullGeo = mock(ResultSet.class);
              when(nullGeo.getObject("id")).thenReturn(id);
              when(nullGeo.getString("display_name")).thenReturn("P");
              when(nullGeo.getString("city")).thenReturn("   ");
              when(nullGeo.getString("logo_url")).thenReturn(null);
              when(nullGeo.getString("tagline")).thenReturn(null);
              when(nullGeo.getString("address_json")).thenReturn("{\"area\":\"OnlyArea\"}");
              when(nullGeo.getObject("latitude")).thenReturn(null);
              when(nullGeo.getObject("longitude")).thenReturn(77.6);
              when(nullGeo.getBoolean("is_online")).thenReturn(true);
              when(nullGeo.getBoolean("admin_forced_offline")).thenReturn(false);
              when(nullGeo.getString("status")).thenReturn("ACTIVE");
              when(nullGeo.getBigDecimal("rating")).thenReturn(BigDecimal.ONE);
              when(nullGeo.getInt("review_count")).thenReturn(0);
              when(nullGeo.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.TEN);
              when(nullGeo.getObject("avg_prep_minutes")).thenReturn(null);
              when(nullGeo.wasNull()).thenReturn(true);

              ResultSet cityOnly = mock(ResultSet.class);
              when(cityOnly.getObject("id"))
                  .thenReturn(UUID.fromString("aaaaaaaa-0001-4000-8000-000000000099"));
              when(cityOnly.getString("display_name")).thenReturn("Q");
              when(cityOnly.getString("city")).thenReturn(null);
              when(cityOnly.getString("logo_url")).thenReturn(null);
              when(cityOnly.getString("tagline")).thenReturn(null);
              when(cityOnly.getString("address_json")).thenReturn("{\"city\":\"Bengaluru\"}");
              when(cityOnly.getObject("latitude")).thenReturn(12.9);
              when(cityOnly.getObject("longitude")).thenReturn(null);
              when(cityOnly.getBoolean("is_online")).thenReturn(true);
              when(cityOnly.getBoolean("admin_forced_offline")).thenReturn(false);
              when(cityOnly.getString("status")).thenReturn("ACTIVE");
              when(cityOnly.getBigDecimal("rating")).thenReturn(BigDecimal.ONE);
              when(cityOnly.getInt("review_count")).thenReturn(0);
              when(cityOnly.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.TEN);
              when(cityOnly.getObject("avg_prep_minutes")).thenReturn(5.0);
              when(cityOnly.wasNull()).thenReturn(false);

              ResultSet blankAddr = mock(ResultSet.class);
              when(blankAddr.getObject("id"))
                  .thenReturn(UUID.fromString("aaaaaaaa-0001-4000-8000-000000000098"));
              when(blankAddr.getString("display_name")).thenReturn("R");
              when(blankAddr.getString("city")).thenReturn("Bengaluru");
              when(blankAddr.getString("logo_url")).thenReturn(null);
              when(blankAddr.getString("tagline")).thenReturn(null);
              when(blankAddr.getString("address_json")).thenReturn(null);
              when(blankAddr.getObject("latitude")).thenReturn(89.99);
              when(blankAddr.getObject("longitude")).thenReturn(0.0);
              when(blankAddr.getBoolean("is_online")).thenReturn(true);
              when(blankAddr.getBoolean("admin_forced_offline")).thenReturn(false);
              when(blankAddr.getString("status")).thenReturn("ACTIVE");
              when(blankAddr.getBigDecimal("rating")).thenReturn(BigDecimal.ONE);
              when(blankAddr.getInt("review_count")).thenReturn(0);
              when(blankAddr.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.TEN);
              when(blankAddr.getObject("avg_prep_minutes")).thenReturn(5.0);
              when(blankAddr.wasNull()).thenReturn(false);

              return List.of(
                  mapper.mapRow(nullGeo, 0),
                  mapper.mapRow(cityOnly, 1),
                  mapper.mapRow(blankAddr, 2));
            });

    // lat=90 → cosLat≈0 branch for bounding box
    List<PharmacyRow> near = pharmacyStore.findOpenNear(90.0, 0.0, 5.0);
    assertThat(near).extracting(PharmacyRow::name).contains("R");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRow> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("display_name")).thenReturn("S");
              when(rs.getString("city")).thenReturn("");
              when(rs.getString("logo_url")).thenReturn(null);
              when(rs.getString("tagline")).thenReturn(null);
              when(rs.getString("address_json")).thenReturn("");
              when(rs.getObject("latitude")).thenReturn(12.9);
              when(rs.getObject("longitude")).thenReturn(77.6);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("admin_forced_offline")).thenReturn(false);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getBigDecimal("rating")).thenReturn(BigDecimal.ZERO);
              when(rs.getInt("review_count")).thenReturn(0);
              when(rs.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.ZERO);
              when(rs.getObject("avg_prep_minutes")).thenReturn(null);
              when(rs.wasNull()).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(pharmacyStore.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(mapPharmacy(inv.getArgument(1), id, null, "{\"area\":\"Indiranagar\"}")));
    assertThat(pharmacyStore.findById(id).orElseThrow().area()).isEqualTo("Indiranagar");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> List.of(mapPharmacy(inv.getArgument(1), id, "Mysuru", "{\"city\":\"Mysuru\"}")));
    assertThat(pharmacyStore.findById(id).orElseThrow().addressLine()).contains("Mysuru");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapPharmacy(
                        inv.getArgument(1),
                        id,
                        "Bengaluru",
                        "{\"flat\":\"\",\"area\":\"\",\"city\":\"\"}")));
    assertThat(pharmacyStore.findById(id).orElseThrow().area()).isEqualTo("Bengaluru");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapPharmacy(
                        inv.getArgument(1),
                        id,
                        "   ",
                        "{\"flat\":\"12\",\"area\":\"\",\"city\":\"Bengaluru\"}")));
    PharmacyRow mixed = pharmacyStore.findById(id).orElseThrow();
    assertThat(mixed.addressLine()).contains("12").contains("Bengaluru");
    assertThat(mixed.area()).contains("Bengaluru");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapPharmacy(
                        inv.getArgument(1),
                        id,
                        null,
                        "{\"flat\":\"\",\"area\":\"HSR\",\"city\":null}")));
    assertThat(pharmacyStore.findById(id).orElseThrow().area()).isEqualTo("HSR");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv ->
                List.of(
                    mapPharmacy(
                        inv.getArgument(1), id, "", "{\"area\":\"Koramangala\",\"city\":\"\"}")));
    assertThat(pharmacyStore.findById(id).orElseThrow().area()).isEqualTo("Koramangala");
  }

  private static PharmacyRow mapPharmacy(
      RowMapper<PharmacyRow> mapper, UUID pharmacyId, String city, String addressJson)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(pharmacyId);
    when(rs.getString("display_name")).thenReturn("T");
    when(rs.getString("city")).thenReturn(city);
    when(rs.getString("logo_url")).thenReturn(null);
    when(rs.getString("tagline")).thenReturn(null);
    when(rs.getString("address_json")).thenReturn(addressJson);
    when(rs.getObject("latitude")).thenReturn(12.9);
    when(rs.getObject("longitude")).thenReturn(77.6);
    when(rs.getBoolean("is_online")).thenReturn(true);
    when(rs.getBoolean("admin_forced_offline")).thenReturn(false);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getBigDecimal("rating")).thenReturn(BigDecimal.ZERO);
    when(rs.getInt("review_count")).thenReturn(0);
    when(rs.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.ZERO);
    when(rs.getObject("avg_prep_minutes")).thenReturn(null);
    when(rs.wasNull()).thenReturn(true);
    return mapper.mapRow(rs, 0);
  }

  @Test
  void inventoryNullIdsAndNullPrices() throws Exception {
    assertThat(inventory.checkAvailability(id, java.util.Arrays.asList((UUID) null))).isEmpty();

    doAnswer(
            inv -> {
              RowCallbackHandler handler = inv.getArgument(1);
              ResultSet oosNullPrice = mock(ResultSet.class);
              when(oosNullPrice.getObject("id")).thenReturn(med);
              when(oosNullPrice.getString("name")).thenReturn("A");
              when(oosNullPrice.getLong("mrp_paise")).thenReturn(1000L);
              when(oosNullPrice.getBoolean("is_banned")).thenReturn(false);
              when(oosNullPrice.getObject("stock_quantity")).thenReturn(0);
              when(oosNullPrice.getObject("pharmacy_price_paise")).thenReturn(null);
              handler.processRow(oosNullPrice);

              UUID med2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
              ResultSet inStockNullPrice = mock(ResultSet.class);
              when(inStockNullPrice.getObject("id")).thenReturn(med2);
              when(inStockNullPrice.getString("name")).thenReturn("B");
              when(inStockNullPrice.getLong("mrp_paise")).thenReturn(1000L);
              when(inStockNullPrice.getBoolean("is_banned")).thenReturn(false);
              when(inStockNullPrice.getObject("stock_quantity")).thenReturn(3);
              when(inStockNullPrice.getObject("pharmacy_price_paise")).thenReturn(null);
              handler.processRow(inStockNullPrice);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

    List<StockLine> lines =
        inventory.checkAvailability(
            id, List.of(med, UUID.fromString("22222222-2222-4222-8222-222222222222")));
    assertThat(lines.get(0).pricePaise()).isEqualTo(0);
    assertThat(lines.get(1).inStock()).isTrue();
    assertThat(lines.get(1).pricePaise()).isEqualTo(0);

    // exercise RowMapper path for findMedicine / medicineName
    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Metformin");
              when(rs.getString("manufacturer")).thenReturn("USV");
              when(rs.getBigDecimal("pack_size")).thenReturn(null);
              when(rs.getString("pack_unit")).thenReturn("STRIP");
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getBoolean("is_banned")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(inventory.medicineName(med)).contains("Metformin");
  }
}
