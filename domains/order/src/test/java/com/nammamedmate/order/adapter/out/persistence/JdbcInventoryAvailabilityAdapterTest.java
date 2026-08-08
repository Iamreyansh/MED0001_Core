package com.nammamedmate.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.ProductPage;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
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
class JdbcInventoryAvailabilityAdapterTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcInventoryAvailabilityAdapter adapter;
  private final UUID pharmacy = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private final UUID med1 = UUID.fromString("22222222-2222-4222-8222-222222222221");
  private final UUID med2 = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private final UUID med3 = UUID.fromString("22222222-2222-4222-8222-222222222223");

  @BeforeEach
  void setUp() {
    adapter = new JdbcInventoryAvailabilityAdapter(jdbc);
  }

  @Test
  void stocksMedicine() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med1)))
        .thenAnswer(
            inv -> {
              RowMapper<Integer> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getInt(1)).thenReturn(5);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(adapter.stocksMedicine(pharmacy, med1)).isTrue();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med1)))
        .thenReturn(List.of(0));
    assertThat(adapter.stocksMedicine(pharmacy, med1)).isFalse();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med1)))
        .thenReturn(List.of());
    assertThat(adapter.stocksMedicine(pharmacy, med1)).isFalse();
  }

  @Test
  void checkAvailabilitySplitsAndEmpty() throws Exception {
    assertThat(adapter.checkAvailability(pharmacy, List.of())).isEmpty();
    assertThat(adapter.checkAvailability(pharmacy, null)).isEmpty();

    doAnswer(
            inv -> {
              RowCallbackHandler handler = inv.getArgument(1);
              ResultSet rs1 = mockRow(med1, "A", 1000L, false, 10, 900L);
              ResultSet rs2 = mockRow(med2, "B", 2000L, false, 0, 1800L);
              ResultSet rs3 = mockRow(med3, "C", 3000L, true, null, null);
              handler.processRow(rs1);
              handler.processRow(rs2);
              handler.processRow(rs3);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

    List<StockLine> lines = adapter.checkAvailability(pharmacy, List.of(med1, med2, med3));
    assertThat(lines).hasSize(3);
    assertThat(lines.get(0).inStock()).isTrue();
    assertThat(lines.get(1).unavailableReason()).isEqualTo("OUT_OF_STOCK");
    assertThat(lines.get(2).unavailableReason()).isEqualTo("BANNED");

    UUID missing = UUID.fromString("22222222-2222-4222-8222-222222222299");
    doAnswer(inv -> null)
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(missing))).thenReturn(List.of());
    List<StockLine> notFound = adapter.checkAvailability(pharmacy, List.of(missing));
    assertThat(notFound.getFirst().unavailableReason()).isEqualTo("NOT_FOUND");
    assertThat(notFound.getFirst().name()).isEqualTo("Unknown");
  }

  @Test
  void listVisibleProductsWithFilters() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med1);
              when(rs.getString("name")).thenReturn("Metformin");
              when(rs.getString("manufacturer")).thenReturn("USV");
              when(rs.getString("category_name")).thenReturn("Diabetic Care");
              when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("10"));
              when(rs.getString("pack_unit")).thenReturn("TABLET");
              when(rs.getLong("mrp_paise")).thenReturn(2850L);
              when(rs.getBoolean("is_rx_only")).thenReturn(true);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(2565L);
              when(rs.getInt("stock_quantity")).thenReturn(200);
              return List.of(mapper.mapRow(rs, 0));
            });

    ProductPage page = adapter.listVisibleProducts(pharmacy, "Diabetic Care", "met", 1, 20);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().getFirst().packSize()).contains("10");

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med1);
              when(rs.getString("name")).thenReturn("X");
              when(rs.getString("manufacturer")).thenReturn("Y");
              when(rs.getString("category_name")).thenReturn("Z");
              when(rs.getBigDecimal("pack_size")).thenReturn(null);
              when(rs.getString("pack_unit")).thenReturn("ML");
              when(rs.getLong("mrp_paise")).thenReturn(100L);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(90L);
              when(rs.getInt("stock_quantity")).thenReturn(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    ProductPage page2 = adapter.listVisibleProducts(pharmacy, null, null, 0, 0);
    assertThat(page2.page()).isEqualTo(1);
    assertThat(page2.limit()).isEqualTo(1);
    assertThat(page2.items().getFirst().packSize()).isEqualTo("ML");

    ProductPage page3 = adapter.listVisibleProducts(pharmacy, "  ", "", 1, 20);
    assertThat(page3.total()).isEqualTo(0);
  }

  @Test
  void medicineNameAndFindMedicine() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq(med1)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med1);
              when(rs.getString("name")).thenReturn("Metformin");
              when(rs.getString("manufacturer")).thenReturn("USV");
              when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("10"));
              when(rs.getString("pack_unit")).thenReturn("TABLETS");
              when(rs.getBoolean("is_rx_only")).thenReturn(true);
              when(rs.getBoolean("is_banned")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(adapter.findMedicine(med1)).isPresent();
    assertThat(adapter.medicineName(med1)).contains("Metformin");
    when(jdbc.query(anyString(), any(RowMapper.class), eq(med1))).thenReturn(List.of());
    assertThat(adapter.medicineName(med1)).isEmpty();
  }

  @Test
  void checkAvailabilityNotMapped() throws Exception {
    doAnswer(
            inv -> {
              RowCallbackHandler handler = inv.getArgument(1);
              handler.processRow(mockRow(med1, "A", 1000L, false, null, null));
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    assertThat(adapter.checkAvailability(pharmacy, List.of(med1)).getFirst().unavailableReason())
        .isEqualTo("NOT_MAPPED");
  }

  private static ResultSet mockRow(
      UUID id, String name, long mrp, boolean banned, Integer stock, Long price) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn(name);
    when(rs.getLong("mrp_paise")).thenReturn(mrp);
    when(rs.getBoolean("is_banned")).thenReturn(banned);
    when(rs.getObject("stock_quantity")).thenReturn(stock);
    when(rs.getObject("pharmacy_price_paise")).thenReturn(price);
    return rs;
  }
}
