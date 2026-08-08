package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
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
class JdbcPharmacyCandidateStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPharmacyCandidateStore store;
  private final UUID id = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");

  @BeforeEach
  void setUp() {
    store = new JdbcPharmacyCandidateStore(jdbc, new ObjectMapper());
  }

  @Test
  void findOpenNearFiltersByHaversine() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRow> mapper = inv.getArgument(1);
              ResultSet near = mockRs(id, 12.9350, 77.6130, true, false, "ACTIVE");
              ResultSet far =
                  mockRs(
                      UUID.fromString("aaaaaaaa-0001-4000-8000-000000000099"),
                      13.1000,
                      77.8000,
                      true,
                      false,
                      "ACTIVE");
              ResultSet closed =
                  mockRs(
                      UUID.fromString("aaaaaaaa-0001-4000-8000-000000000098"),
                      12.9350,
                      77.6130,
                      false,
                      false,
                      "ACTIVE");
              return List.of(
                  mapper.mapRow(near, 0), mapper.mapRow(far, 1), mapper.mapRow(closed, 2));
            });

    List<PharmacyRow> rows = store.findOpenNear(12.9345, 77.6125, 5.0);
    assertThat(rows).extracting(PharmacyRow::id).containsExactly(id);
  }

  @Test
  void findByIdAndHelpers() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRow> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRs(id, 12.9, 77.6, true, false, "ACTIVE"), 0));
            });
    assertThat(store.findById(id)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(store.findById(id)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn("Antibiotics");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.categoriesAvailable(id)).containsExactly("Antibiotics");

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id))).thenReturn(7);
    assertThat(store.visibleItemsCount(id)).isEqualTo(7);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id))).thenReturn(null);
    assertThat(store.visibleItemsCount(id)).isEqualTo(0);

    when(jdbc.queryForObject(
            eq("SELECT COUNT(*) FROM pharmacy_directory_metrics"), eq(Integer.class)))
        .thenReturn(4);
    assertThat(store.refreshFillRatesFromDirectoryMetrics()).isEqualTo(4);
    when(jdbc.queryForObject(
            eq("SELECT COUNT(*) FROM pharmacy_directory_metrics"), eq(Integer.class)))
        .thenReturn(null);
    assertThat(store.refreshFillRatesFromDirectoryMetrics()).isEqualTo(0);
  }

  @Test
  void openHoursSummaryPaths() {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id), any()))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getBoolean("is_closed")).thenReturn(false);
              when(rs.getTime("open_time")).thenReturn(Time.valueOf(LocalTime.of(8, 0)));
              when(rs.getTime("close_time")).thenReturn(Time.valueOf(LocalTime.of(22, 0)));
              return List.of(mapper.mapRow(rs, 0));
            });
    Optional<String> hours = store.openHoursSummary(id);
    assertThat(hours).isPresent();
    assertThat(hours.get()).contains("AM").contains("PM");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id), any()))
        .thenReturn(java.util.Arrays.asList((String) null));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getBoolean("is_closed")).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.openHoursSummary(id)).contains("Closed");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getBoolean("is_closed")).thenReturn(false);
              when(rs.getTime("open_time")).thenReturn(Time.valueOf(LocalTime.of(9, 0)));
              when(rs.getTime("close_time")).thenReturn(null);
              String formatted = mapper.mapRow(rs, 0);
              return formatted == null ? List.of() : List.of(formatted);
            });
    assertThat(store.openHoursSummary(id)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getBoolean("is_closed")).thenReturn(false);
              when(rs.getTime("open_time")).thenReturn(null);
              when(rs.getTime("close_time")).thenReturn(null);
              String formatted = mapper.mapRow(rs, 0);
              return formatted == null ? List.of() : List.of(formatted);
            });
    assertThat(store.openHoursSummary(id)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(store.openHoursSummary(id)).isEmpty();
  }

  @Test
  void mapRowAddressVariantsAndNullGeo() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRow> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("display_name")).thenReturn("Shop");
              when(rs.getString("city")).thenReturn(null);
              when(rs.getString("logo_url")).thenReturn(null);
              when(rs.getString("tagline")).thenReturn(null);
              when(rs.getString("address_json")).thenReturn("{\"area\":\"BTM\"}");
              when(rs.getObject("latitude")).thenReturn(null);
              when(rs.getObject("longitude")).thenReturn(null);
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
    PharmacyRow row = store.findById(id).orElseThrow();
    assertThat(row.area()).isEqualTo("BTM");
    assertThat(row.latitude()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRow> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("display_name")).thenReturn("Shop");
              when(rs.getString("city")).thenReturn("Bengaluru");
              when(rs.getString("logo_url")).thenReturn(null);
              when(rs.getString("tagline")).thenReturn(null);
              when(rs.getString("address_json")).thenReturn("not-json");
              when(rs.getObject("latitude")).thenReturn(12.9);
              when(rs.getObject("longitude")).thenReturn(null);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("admin_forced_offline")).thenReturn(false);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getBigDecimal("rating")).thenReturn(BigDecimal.ONE);
              when(rs.getInt("review_count")).thenReturn(0);
              when(rs.getBigDecimal("fill_rate_pct")).thenReturn(BigDecimal.TEN);
              when(rs.getObject("avg_prep_minutes")).thenReturn(12.0);
              when(rs.wasNull()).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findOpenNear(12.9, 77.6, 5)).isEmpty();
  }

  private ResultSet mockRs(
      UUID pharmacyId, Double lat, Double lng, boolean online, boolean forced, String status)
      throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(pharmacyId);
    when(rs.getString("display_name")).thenReturn("Sai");
    when(rs.getString("city")).thenReturn("Bengaluru");
    when(rs.getString("logo_url")).thenReturn("https://cdn/x.png");
    when(rs.getString("tagline")).thenReturn("Offer");
    when(rs.getString("address_json"))
        .thenReturn("{\"flat\":\"12\",\"area\":\"Koramangala\",\"city\":\"Bengaluru\"}");
    when(rs.getObject("latitude")).thenReturn(lat);
    when(rs.getObject("longitude")).thenReturn(lng);
    when(rs.getBoolean("is_online")).thenReturn(online);
    when(rs.getBoolean("admin_forced_offline")).thenReturn(forced);
    when(rs.getString("status")).thenReturn(status);
    when(rs.getBigDecimal("rating")).thenReturn(new BigDecimal("4.50"));
    when(rs.getInt("review_count")).thenReturn(10);
    when(rs.getBigDecimal("fill_rate_pct")).thenReturn(new BigDecimal("90.00"));
    when(rs.getObject("avg_prep_minutes")).thenReturn(10.0);
    when(rs.wasNull()).thenReturn(false);
    return rs;
  }
}
