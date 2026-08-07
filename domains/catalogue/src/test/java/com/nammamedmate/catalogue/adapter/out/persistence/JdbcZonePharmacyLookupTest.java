package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.ZonePharmacyLookupPort.PharmacyRef;
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
class JdbcZonePharmacyLookupTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcZonePharmacyLookup lookup;

  @BeforeEach
  void setUp() {
    lookup = new JdbcZonePharmacyLookup(jdbc);
  }

  @Test
  void findByIdAndZone() {
    UUID id = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<PharmacyRef> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("pharmacy_name")).thenReturn("Shop");
              when(rs.getObject("zone_id")).thenReturn(zone);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("admin_forced_offline")).thenReturn(false);
              when(rs.getString("status")).thenReturn("ACTIVE");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(lookup.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThat(lookup.findById(id)).isEmpty();

    assertThat(lookup.zoneIdForPincode(null)).isEmpty();
    assertThat(lookup.zoneIdForPincode("  ")).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), eq("560001")))
        .thenAnswer(
            inv -> {
              RowMapper<UUID> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
              when(rs.getObject("zone_id")).thenReturn(zone);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(lookup.zoneIdForPincode("560001")).contains(zone);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("999999"))).thenReturn(List.of());
    assertThat(lookup.zoneIdForPincode("999999")).isEmpty();
  }
}
