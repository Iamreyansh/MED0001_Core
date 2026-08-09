package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery.ProductPage;
import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery.StockLine;
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
class JdbcInventoryAvailabilityQueryTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcInventoryAvailabilityQuery query;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID med = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    query = new JdbcInventoryAvailabilityQuery(jdbc);
  }

  @Test
  void stocksMedicine() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med)))
        .thenAnswer(
            inv -> {
              RowMapper<Integer> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getInt(1)).thenReturn(5);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(query.stocksMedicine(pharmacy, med)).isTrue();

    assertThat(query.checkAvailability(pharmacy, List.of())).isEmpty();

    doAnswer(
            inv -> {
              RowCallbackHandler h = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getBoolean("is_banned")).thenReturn(false);
              when(rs.getObject("total_stock_units")).thenReturn(10);
              when(rs.getObject("pharmacy_mrp_paise")).thenReturn(900L);
              when(rs.getLong("mrp_paise")).thenReturn(1000L);
              when(rs.getString("name")).thenReturn("Med");
              h.processRow(rs);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    List<StockLine> lines = query.checkAvailability(pharmacy, List.of(med));
    assertThat(lines).hasSize(1);
    assertThat(lines.getFirst().inStock()).isTrue();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Med");
              when(rs.getString("manufacturer")).thenReturn("M");
              when(rs.getString("category_name")).thenReturn("C");
              when(rs.getInt("pack_size")).thenReturn(10);
              when(rs.getString("pack_unit")).thenReturn("TAB");
              when(rs.getLong("mrp_paise")).thenReturn(1000L);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getInt("total_stock_units")).thenReturn(5);
              when(rs.getString("product_photo_url")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    ProductPage page = query.listVisibleProducts(pharmacy, "C", "Med", 1, 20);
    assertThat(page.total()).isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Med");
              when(rs.getString("manufacturer")).thenReturn("M");
              when(rs.getBigDecimal("pack_size")).thenReturn(BigDecimal.TEN);
              when(rs.getString("pack_unit")).thenReturn("TAB");
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getBoolean("is_banned")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(query.findMedicine(med)).isPresent();
    assertThat(query.medicineName(med)).contains("Med");
  }
}
