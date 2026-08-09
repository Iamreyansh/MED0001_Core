package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.port.out.FefoBatchSelectionPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosNotificationPort;
import com.nammamedmate.pos.application.port.out.PosPharmacyPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.StockDeductionPort;
import com.nammamedmate.pos.domain.ShareChannel;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
class PosBridgeConfigTest {

  @Mock FefoBatchSelectionPort fefo;
  @Mock ProductBatchStore batches;
  @Mock PharmacyProductStore products;
  @Mock JdbcTemplate jdbc;

  @Test
  void inventoryBridgeWiresPorts() throws Exception {
    PosInventoryBridgeConfig cfg = new PosInventoryBridgeConfig();
    UUID pharmacy = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T12:00:00Z");
    ProductBatch batch =
        new ProductBatch(
            batchId,
            product,
            pharmacy,
            "BN",
            LocalDate.of(2027, 1, 1),
            null,
            10,
            10,
            100L,
            200L,
            true,
            null,
            null,
            null,
            now,
            now);
    when(fefo.selectFefoBatch(pharmacy, product)).thenReturn(Optional.of(batch));
    when(fefo.listPosEligibleBatches(pharmacy, product)).thenReturn(List.of(batch));
    when(batches.findById(pharmacy, product, batchId)).thenReturn(Optional.of(batch));

    PosFefoPort posFefo = cfg.posFefoPort(fefo, batches);
    assertThat(posFefo.selectFefoBatch(pharmacy, product)).isPresent();
    assertThat(posFefo.listEligibleBatches(pharmacy, product)).hasSize(1);
    assertThat(posFefo.findBatch(pharmacy, product, batchId)).isPresent();

    StockDeductionPort stock = cfg.posStockDeductionPort(batches);
    when(batches.updateQuantities(any(), anyInt(), anyInt(), any(Boolean.class), any()))
        .thenReturn(batch);
    stock.deductSale(pharmacy, product, batchId, 2, UUID.randomUUID(), now);
    verify(batches)
        .insertStockMovement(
            any(),
            eq(pharmacy),
            eq(product),
            eq(batchId),
            eq("SALE"),
            eq(-2),
            anyString(),
            any(),
            eq(now));

    when(batches.findById(pharmacy, product, batchId))
        .thenReturn(
            Optional.of(
                new ProductBatch(
                    batchId,
                    product,
                    pharmacy,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    10,
                    0,
                    100L,
                    200L,
                    true,
                    null,
                    null,
                    null,
                    now,
                    now)));
    assertThatThrownBy(
            () -> stock.deductSale(pharmacy, product, batchId, 1, UUID.randomUUID(), now))
        .isInstanceOf(AppException.class);

    PharmacyProduct pp =
        new PharmacyProduct(
            product,
            pharmacy,
            null,
            "Para",
            null,
            "Cipla",
            15,
            "TAB",
            null,
            null,
            "TABLET",
            "OTC",
            "3004",
            BigDecimal.valueOf(12),
            2250L,
            false,
            false,
            true,
            0,
            List.of("A1-03"),
            10,
            1,
            LocalDate.of(2027, 1, 1),
            0L,
            now,
            null,
            now,
            now);
    when(products.findById(pharmacy, product)).thenReturn(Optional.of(pp));
    when(products.searchByName(pharmacy, "para", 10)).thenReturn(List.of(pp));

    PosFefoPort fefoBean = cfg.posFefoPort(fefo, batches);
    ProductLookupPort lookup = cfg.posProductLookupPort(products, fefoBean, jdbc);
    assertThat(lookup.findById(pharmacy, product)).isPresent();
    assertThat(lookup.searchByText(pharmacy, "para", 10)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(product);
              when(rs.getString("name")).thenReturn("Para");
              when(rs.getString("manufacturer")).thenReturn("Cipla");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getInt("pack_size")).thenReturn(15);
              when(rs.getLong("mrp_paise")).thenReturn(2250L);
              when(rs.getInt("total_stock_units")).thenReturn(10);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getBoolean("is_loose_selling_enabled")).thenReturn(false);
              when(rs.getInt("gst_pct")).thenReturn(12);
              when(rs.getString("hsn_code")).thenReturn("3004");
              Array arr = mock(Array.class);
              when(arr.getArray()).thenReturn(new String[] {"A1-03"});
              when(rs.getArray("rack_locations")).thenReturn(arr);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(lookup.findByBarcode(pharmacy, "890")).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(product);
              when(rs.getString("name")).thenReturn("Para");
              when(rs.getString("manufacturer")).thenReturn("Cipla");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getInt("pack_size")).thenReturn(15);
              when(rs.getLong("mrp_paise")).thenReturn(2250L);
              when(rs.getInt("total_stock_units")).thenReturn(10);
              when(rs.getBoolean("is_rx_only")).thenReturn(false);
              when(rs.getBoolean("is_loose_selling_enabled")).thenReturn(false);
              when(rs.getInt("gst_pct")).thenReturn(12);
              when(rs.getString("hsn_code")).thenReturn(null);
              when(rs.getArray("rack_locations")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(lookup.searchByRack(pharmacy, "A1-03", 10)).hasSize(1);
  }

  @Test
  void customerBridgeFindOrCreate() {
    PosCustomerBridgeConfig cfg = new PosCustomerBridgeConfig();
    PosCustomerPort port = cfg.posCustomerPort(jdbc);
    UUID id = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenReturn(List.of(new Object[] {})); // will fail - need proper

    // empty → create
    when(jdbc.query(anyString(), any(RowMapper.class), eq("+9198"))).thenReturn(List.of());
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    PosCustomerPort.CustomerRef created = port.findOrCreate("+9198", "Priya");
    assertThat(created.isNew()).isTrue();
    assertThat(created.name()).isEqualTo("Priya");

    when(jdbc.query(anyString(), any(RowMapper.class), eq("+9199")))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("Priya");
              when(rs.getString("phone")).thenReturn("+9199");
              return List.of(mapper.mapRow(rs, 0));
            });
    PosCustomerPort.CustomerRef existing = port.findOrCreate("+9199", null);
    assertThat(existing.isNew()).isFalse();
  }

  @Test
  void pharmacyAndNotificationBridges() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    PosPharmacyBridgeConfig pharmacyCfg = new PosPharmacyBridgeConfig();
    PosPharmacyPort pharmacyPort = pharmacyCfg.posPharmacyPort(jdbc, mapper);
    UUID pharmacy = UUID.randomUUID();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              RowMapper<?> rm = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("display_name")).thenReturn("Balaji");
              when(rs.getString("address"))
                  .thenReturn(
                      "{\"line1\":\"Shop 4\",\"city\":\"Bangalore\",\"pincode\":\"560001\"}");
              when(rs.getString("phone")).thenReturn("+9180");
              when(rs.getString("gstin")).thenReturn("29A");
              when(rs.getString("drug_licence_number")).thenReturn("DL");
              return List.of(rm.mapRow(rs, 0));
            });
    assertThat(pharmacyPort.findById(pharmacy)).isPresent();
    assertThat(pharmacyPort.findById(pharmacy).orElseThrow().address()).contains("Shop 4");
    assertThat(PosPharmacyBridgeConfig.formatAddress(null, mapper)).isNull();
    assertThat(PosPharmacyBridgeConfig.formatAddress("{}", mapper)).isEqualTo("{}");
    assertThat(PosPharmacyBridgeConfig.formatAddress("not-json", mapper)).isEqualTo("not-json");

    InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    PosNotificationBridgeConfig notifyCfg = new PosNotificationBridgeConfig();
    PosNotificationPort notify =
        notifyCfg.posNotificationPort(
            java.util.Optional.of(new OutboxPublisher(outbox, mapper)), true);
    PosNotificationPort.ShareResult sent =
        notify.shareInvoice(
            pharmacy, UUID.randomUUID(), "INV-1", ShareChannel.WHATSAPP, "+91", "data:pdf");
    assertThat(sent.messageId()).startsWith("whatsapp_msg_");
    assertThat(outbox.findUnpublished(10)).isNotEmpty();

    PosNotificationPort.ShareResult remind =
        notify.sendKhataReminder(
            pharmacy, UUID.randomUUID(), ShareChannel.SMS, "POLITE", "+91", 5000L);
    assertThat(remind.messageId()).startsWith("sms_msg_");

    PosNotificationPort disabled = notifyCfg.posNotificationPort(java.util.Optional.empty(), false);
    assertThatThrownBy(
            () ->
                disabled.shareInvoice(
                    pharmacy, UUID.randomUUID(), "INV-1", ShareChannel.SMS, "+91", "x"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                disabled.sendKhataReminder(
                    pharmacy, UUID.randomUUID(), ShareChannel.WHATSAPP, "FIRM", "+91", 1L))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");
  }
}
