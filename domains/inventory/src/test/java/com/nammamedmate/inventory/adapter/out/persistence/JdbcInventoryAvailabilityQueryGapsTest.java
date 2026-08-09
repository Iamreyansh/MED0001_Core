package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery.StockLine;
import java.sql.ResultSet;
import java.util.Collections;
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
class JdbcInventoryAvailabilityQueryGapsTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcInventoryAvailabilityQuery query;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID med1 = UUID.randomUUID();
  private final UUID med2 = UUID.randomUUID();
  private final UUID med3 = UUID.randomUUID();
  private final UUID med4 = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    query = new JdbcInventoryAvailabilityQuery(jdbc);
  }

  @Test
  void availabilityBranches() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med1)))
        .thenReturn(List.of(0));
    assertThat(query.stocksMedicine(pharmacy, med1)).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med1)))
        .thenReturn(List.of());
    assertThat(query.stocksMedicine(pharmacy, med1)).isFalse();

    assertThat(query.checkAvailability(pharmacy, null)).isEmpty();
    assertThat(query.checkAvailability(pharmacy, Collections.singletonList(null))).isEmpty();

    doAnswer(
            inv -> {
              RowCallbackHandler h = inv.getArgument(1);
              ResultSet banned = mock(ResultSet.class);
              when(banned.getObject("id")).thenReturn(med1);
              when(banned.getBoolean("is_banned")).thenReturn(true);
              when(banned.getObject("total_stock_units")).thenReturn(5);
              when(banned.getObject("pharmacy_mrp_paise")).thenReturn(100L);
              when(banned.getLong("mrp_paise")).thenReturn(100L);
              when(banned.getString("name")).thenReturn("B");
              h.processRow(banned);

              ResultSet unmapped = mock(ResultSet.class);
              when(unmapped.getObject("id")).thenReturn(med2);
              when(unmapped.getBoolean("is_banned")).thenReturn(false);
              when(unmapped.getObject("total_stock_units")).thenReturn(null);
              when(unmapped.getObject("pharmacy_mrp_paise")).thenReturn(null);
              when(unmapped.getLong("mrp_paise")).thenReturn(200L);
              when(unmapped.getString("name")).thenReturn("U");
              h.processRow(unmapped);

              ResultSet oos = mock(ResultSet.class);
              when(oos.getObject("id")).thenReturn(med3);
              when(oos.getBoolean("is_banned")).thenReturn(false);
              when(oos.getObject("total_stock_units")).thenReturn(0);
              when(oos.getObject("pharmacy_mrp_paise")).thenReturn(null);
              when(oos.getLong("mrp_paise")).thenReturn(300L);
              when(oos.getString("name")).thenReturn("O");
              h.processRow(oos);

              ResultSet ok = mock(ResultSet.class);
              when(ok.getObject("id")).thenReturn(med4);
              when(ok.getBoolean("is_banned")).thenReturn(false);
              when(ok.getObject("total_stock_units")).thenReturn(3);
              when(ok.getObject("pharmacy_mrp_paise")).thenReturn(null);
              when(ok.getLong("mrp_paise")).thenReturn(400L);
              when(ok.getString("name")).thenReturn("K");
              h.processRow(ok);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-4000-8000-000000000099"))))
        .thenReturn(List.of());

    UUID missing = UUID.fromString("00000000-0000-4000-8000-000000000099");
    List<StockLine> lines =
        query.checkAvailability(pharmacy, List.of(med1, med2, med3, med4, missing));
    assertThat(lines).hasSize(5);
    assertThat(lines.stream().filter(s -> "BANNED".equals(s.unavailableReason())).count())
        .isEqualTo(1);
    assertThat(lines.stream().filter(s -> "NOT_MAPPED".equals(s.unavailableReason())).count())
        .isEqualTo(1);
    assertThat(lines.stream().filter(s -> "OUT_OF_STOCK".equals(s.unavailableReason())).count())
        .isEqualTo(1);
    assertThat(lines.stream().filter(StockLine::inStock).count()).isEqualTo(1);
    assertThat(lines.stream().filter(s -> "NOT_FOUND".equals(s.unavailableReason())).count())
        .isEqualTo(1);

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(query.listVisibleProducts(pharmacy, " ", " ", 0, 0).total()).isEqualTo(0);
    assertThat(query.listVisibleProducts(pharmacy, null, null, 1, 20).total()).isEqualTo(0);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med1)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med1);
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("manufacturer")).thenReturn("M");
              when(rs.getBigDecimal("pack_size")).thenReturn(null);
              when(rs.getString("pack_unit")).thenReturn("tab");
              when(rs.getBoolean("is_rx_only")).thenReturn(true);
              when(rs.getBoolean("is_banned")).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(query.findMedicine(med1)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med2))).thenReturn(List.of());
    assertThat(query.findMedicine(med2)).isEmpty();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med1);
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("manufacturer")).thenReturn("M");
              when(rs.getString("category_name")).thenReturn("C");
              when(rs.getInt("pack_size")).thenReturn(10);
              when(rs.getString("pack_unit")).thenReturn(null);
              when(rs.getLong("mrp_paise")).thenReturn(100L);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getInt("total_stock_units")).thenReturn(1);
              when(rs.getString("product_photo_url")).thenReturn("u");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(query.listVisibleProducts(pharmacy, "Cat", "search", 1, 20).items()).hasSize(1);
  }
}
