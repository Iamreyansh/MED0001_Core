package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.FefoBatchSelectionPort;
import com.nammamedmate.inventory.application.port.out.InventoryAvailabilityQuery;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

class OrderInventoryBridgeConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void bridgesQueryToOrderPortWithCatalogueFallback() throws Exception {
    InventoryAvailabilityQuery query = mock(InventoryAvailabilityQuery.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    FefoBatchSelectionPort fefo = mock(FefoBatchSelectionPort.class);
    ProductBatchStore batches = mock(ProductBatchStore.class);
    InventoryAvailabilityPort port =
        new OrderInventoryBridgeConfig().orderInventoryAvailabilityPort(query, jdbc, fefo, batches);

    UUID pharmacy = UUID.randomUUID();
    UUID med = UUID.randomUUID();
    when(query.stocksMedicine(pharmacy, med)).thenReturn(true);
    assertThat(port.stocksMedicine(pharmacy, med)).isTrue();

    when(query.stocksMedicine(pharmacy, med)).thenReturn(false);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med)))
        .thenReturn(List.of(3));
    assertThat(port.stocksMedicine(pharmacy, med)).isTrue();

    when(query.findMedicine(med))
        .thenReturn(
            Optional.of(
                new InventoryAvailabilityQuery.MedicineDetails(
                    med, "N", "B", "10 tab", false, null, false)));
    when(query.medicineName(med)).thenReturn(Optional.of("N"));
    assertThat(port.findMedicine(med)).isPresent();
    assertThat(port.medicineName(med)).contains("N");

    when(query.checkAvailability(eq(pharmacy), anyList()))
        .thenReturn(
            List.of(new InventoryAvailabilityQuery.StockLine(med, "N", 5, 100, 120, true, null)));
    assertThat(port.checkAvailability(pharmacy, List.of(med))).hasSize(1);

    when(query.checkAvailability(eq(pharmacy), anyList()))
        .thenReturn(
            List.of(
                new InventoryAvailabilityQuery.StockLine(
                    med, "N", 0, 0, 120, false, "NOT_MAPPED")));
    doAnswer(
            inv -> {
              RowCallbackHandler h = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getBoolean("is_banned")).thenReturn(false);
              when(rs.getObject("stock_quantity")).thenReturn(4);
              when(rs.getObject("pharmacy_price_paise")).thenReturn(900L);
              when(rs.getLong("mrp_paise")).thenReturn(1000L);
              when(rs.getString("name")).thenReturn("N");
              h.processRow(rs);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    assertThat(port.checkAvailability(pharmacy, List.of(med))).hasSize(1);

    when(query.listVisibleProducts(eq(pharmacy), any(), anyString(), anyInt(), anyInt()))
        .thenReturn(
            new InventoryAvailabilityQuery.ProductPage(
                List.of(
                    new InventoryAvailabilityQuery.ProductRow(
                        med, "N", "B", "C", "10", 100, 100, false, 5, null)),
                1,
                1,
                20));
    assertThat(port.listVisibleProducts(pharmacy, null, "n", 1, 20).total()).isEqualTo(1);

    when(query.listVisibleProducts(eq(pharmacy), any(), any(), anyInt(), anyInt()))
        .thenReturn(new InventoryAvailabilityQuery.ProductPage(List.of(), 0, 1, 20));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(port.listVisibleProducts(pharmacy, null, null, 1, 20).total()).isEqualTo(0);

    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    port.reserveForOrder(
        pharmacy, UUID.randomUUID(), List.of(new InventoryAvailabilityPort.ReserveLine(med, 2)));
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0, 1);
    port.reserveForOrder(
        pharmacy, UUID.randomUUID(), List.of(new InventoryAvailabilityPort.ReserveLine(med, 2)));
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                port.reserveForOrder(
                    pharmacy,
                    UUID.randomUUID(),
                    List.of(new InventoryAvailabilityPort.ReserveLine(med, 2))))
        .isInstanceOf(com.nammamedmate.kernel.error.AppException.class);
    port.reserveForOrder(pharmacy, UUID.randomUUID(), null);
    UUID orderId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(med)))
        .thenReturn(List.of(productId));
    when(fefo.listPosEligibleBatches(eq(pharmacy), eq(productId)))
        .thenReturn(
            List.of(
                new ProductBatch(
                    UUID.randomUUID(),
                    productId,
                    pharmacy,
                    "B1",
                    java.time.LocalDate.of(2027, 1, 1),
                    null,
                    10,
                    10,
                    100,
                    120,
                    true,
                    null,
                    null,
                    null,
                    java.time.Instant.parse("2026-01-01T00:00:00Z"),
                    java.time.Instant.parse("2026-01-01T00:00:00Z"))));
    doAnswer(
            inv -> {
              RowCallbackHandler h = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getObject("medicine_id")).thenReturn(med);
              when(rs.getInt("quantity")).thenReturn(2);
              when(rs.getString("status")).thenReturn("RESERVED");
              h.processRow(rs);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), eq(orderId));
    port.deductForOrder(orderId);
    doAnswer(
            inv -> {
              RowCallbackHandler h = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getObject("medicine_id")).thenReturn(med);
              when(rs.getInt("quantity")).thenReturn(2);
              when(rs.getString("status")).thenReturn("RESERVED");
              h.processRow(rs);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class), any());
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    port.releaseForOrder(UUID.randomUUID());
    port.deductForOrder(UUID.randomUUID());
    port.releaseForOrder(UUID.randomUUID());
  }
}
