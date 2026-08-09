package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryAlertRow;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryReportRow;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryBatchServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);
  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final UUID PRODUCT = UUID.fromString("11111111-2222-4333-8444-555555555555");
  private static final UUID BATCH = UUID.fromString("22222222-3333-4444-8555-666666666666");

  @Mock private ProductBatchStore batchStore;
  @Mock private PharmacyProductStore productStore;

  private InventoryBatchService service;
  private MedmatePrincipal owner;
  private MedmatePrincipal staff;

  @BeforeEach
  void setUp() {
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
    service =
        new InventoryBatchService(
            batchStore,
            productStore,
            new SimpleXlsxExporter(),
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_insufficientBatchQuantity_rejected() {
    when(batchStore.findById(PHARMACY, PRODUCT, BATCH))
        .thenReturn(Optional.of(sampleBatch(150, true, LocalDate.of(2027, 1, 1))));

    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, -200, "DAMAGE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INSUFFICIENT_BATCH_QUANTITY");
    verify(batchStore, never()).updateQuantities(any(), anyInt(), anyInt(), anyBoolean(), any());
  }

  @Test
  void ac_staffCannotWriteOff() {
    assertThatThrownBy(() -> service.writeOffBatch(staff, PRODUCT, BATCH, "EXPIRED", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("STAFF_CANNOT_WRITE_OFF");
  }

  @Test
  void ac_freeQuantity_receivedIncludesFree() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(sampleProduct()));
    when(batchStore.findByBatchNumber(PHARMACY, PRODUCT, "BN1")).thenReturn(Optional.empty());
    when(batchStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> data =
        service.addBatch(
            owner,
            PRODUCT,
            "BN1",
            LocalDate.of(2027, 6, 30),
            null,
            100,
            20,
            new BigDecimal("13.00"),
            new BigDecimal("22.50"));

    assertThat(data.get("quantity_received")).isEqualTo(120);
    assertThat(data.get("quantity_current")).isEqualTo(120);
    assertThat(data.get("topped_up")).isEqualTo(false);
    ArgumentCaptor<ProductBatch> cap = ArgumentCaptor.forClass(ProductBatch.class);
    verify(batchStore).insert(cap.capture());
    assertThat(cap.getValue().purchasePricePaise()).isEqualTo(1300L);
  }

  @Test
  void ac_writeOff_zerosQtyAndLogs() {
    when(batchStore.findById(PHARMACY, PRODUCT, BATCH))
        .thenReturn(Optional.of(sampleBatch(150, true, LocalDate.of(2026, 7, 1))));
    when(batchStore.writeOff(eq(BATCH), eq("EXPIRED"), any(), eq(NOW)))
        .thenReturn(sampleBatch(0, false, LocalDate.of(2026, 7, 1)));

    Map<String, Object> data = service.writeOffBatch(owner, PRODUCT, BATCH, "EXPIRED", "notes");

    assertThat(data.get("units_written_off")).isEqualTo(150);
    assertThat(data.get("is_active")).isEqualTo(false);
    ArgumentCaptor<ProductBatchStore.AdjustmentLogRow> log =
        ArgumentCaptor.forClass(ProductBatchStore.AdjustmentLogRow.class);
    verify(batchStore).insertAdjustmentLog(log.capture());
    assertThat(log.getValue().reason()).isEqualTo("EXPIRY_WRITE_OFF");
    assertThat(log.getValue().afterQty()).isEqualTo(0);
    verify(batchStore).refreshProductDenorm(PHARMACY, PRODUCT, NOW);
  }

  @Test
  void ac_expiryAlerts_valueAtRiskIsQtyTimesPrice() {
    when(batchStore.listExpiringWithinMonths(eq(PHARMACY), eq(4), eq(TODAY)))
        .thenReturn(
            List.of(
                new ExpiryAlertRow(
                    PRODUCT, "Amox", "AM1", LocalDate.of(2026, 8, 20), 30, 850L, List.of("B2"))));

    Map<String, Object> data = service.expiryAlerts(owner);

    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) data.get("summary");
    assertThat(summary.get("total_value_at_risk")).isEqualTo(new BigDecimal("255.00"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
    assertThat(groups.get(0).get("bucket")).isEqualTo("UNDER_1_MONTH");
    assertThat(groups.get(0).get("units")).isEqualTo(30);
  }

  @Test
  void ac_expiryReportPdf_returnsPdfBytes() {
    when(batchStore.listExpiryReport(eq(PHARMACY), eq(4), eq(TODAY)))
        .thenReturn(
            List.of(
                new ExpiryReportRow("Amox", "AM1", LocalDate.of(2026, 8, 20), 30, 850L, "B2-04")));

    Object result = service.expiryReport(owner, 4, "PDF");

    assertThat(result).isInstanceOf(InventoryBatchService.FileExport.class);
    InventoryBatchService.FileExport file = (InventoryBatchService.FileExport) result;
    assertThat(file.contentType()).isEqualTo("application/pdf");
    assertThat(new String(file.bytes())).startsWith("%PDF");
  }

  @Test
  void duplicateBatch_topsUpWith201Flag() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(sampleProduct()));
    ProductBatch existing = sampleBatch(100, true, LocalDate.of(2027, 1, 1));
    when(batchStore.findByBatchNumber(PHARMACY, PRODUCT, "BN1")).thenReturn(Optional.of(existing));
    when(batchStore.updateQuantities(eq(BATCH), eq(220), eq(220), eq(true), eq(NOW)))
        .thenReturn(sampleBatch(220, true, LocalDate.of(2027, 1, 1)));

    Map<String, Object> data =
        service.addBatch(
            owner,
            PRODUCT,
            "BN1",
            LocalDate.of(2027, 6, 30),
            null,
            100,
            20,
            new BigDecimal("13.00"),
            new BigDecimal("22.50"));

    assertThat(data.get("topped_up")).isEqualTo(true);
    assertThat(data.get("quantity_current")).isEqualTo(220);
  }

  @Test
  void listBatches_andAdjustSuccess() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(sampleProduct()));
    when(batchStore.listByProduct(PHARMACY, PRODUCT, false))
        .thenReturn(List.of(sampleBatch(150, true, LocalDate.of(2026, 10, 31))));

    Map<String, Object> listed = service.listBatches(owner, PRODUCT, false);
    assertThat(listed.get("total_active_units")).isEqualTo(150);

    when(batchStore.findById(PHARMACY, PRODUCT, BATCH))
        .thenReturn(Optional.of(sampleBatch(150, true, LocalDate.of(2026, 10, 31))));
    when(batchStore.updateQuantities(eq(BATCH), eq(150), eq(145), eq(true), eq(NOW)))
        .thenReturn(sampleBatch(145, true, LocalDate.of(2026, 10, 31)));

    Map<String, Object> adj = service.adjustBatch(owner, PRODUCT, BATCH, -5, "DAMAGE");
    assertThat(adj.get("after_qty")).isEqualTo(145);
  }

  @Test
  void expiryReportJsonAndExcel() {
    when(batchStore.listExpiryReport(eq(PHARMACY), eq(4), eq(TODAY)))
        .thenReturn(
            List.of(
                new ExpiryReportRow("Amox", "AM1", LocalDate.of(2026, 9, 15), 10, 1000L, null)));

    @SuppressWarnings("unchecked")
    Map<String, Object> json = (Map<String, Object>) service.expiryReport(owner, null, null);
    assertThat(json.get("total_batches")).isEqualTo(1);

    Object excel = service.expiryReport(owner, 4, "EXCEL");
    assertThat(excel).isInstanceOf(InventoryBatchService.FileExport.class);
    assertThat(((InventoryBatchService.FileExport) excel).filename()).endsWith(".xlsx");
  }

  @Test
  void validationBranches() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(sampleProduct()));
    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "BN",
                    LocalDate.of(2020, 1, 1),
                    null,
                    1,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.ZERO))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_MRP");

    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, -1, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REASON");

    when(batchStore.findById(PHARMACY, PRODUCT, BATCH))
        .thenReturn(Optional.of(sampleBatch(10, false, LocalDate.of(2027, 1, 1))));
    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, -1, "DAMAGE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BATCH_INACTIVE");

    when(batchStore.findById(PHARMACY, PRODUCT, BATCH)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.writeOffBatch(owner, PRODUCT, BATCH, "EXPIRED", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BATCH_NOT_FOUND");

    when(batchStore.findById(PHARMACY, PRODUCT, BATCH))
        .thenReturn(Optional.of(sampleBatch(10, false, LocalDate.of(2027, 1, 1))));
    assertThatThrownBy(() -> service.writeOffBatch(owner, PRODUCT, BATCH, "EXPIRED", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BATCH_ALREADY_INACTIVE");

    assertThatThrownBy(() -> service.expiryReport(staff, 4, "JSON"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.expiryReport(owner, 4, "CSV"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void mapBatchesForDetail_includesInactive() {
    when(batchStore.listByProduct(PHARMACY, PRODUCT, true))
        .thenReturn(List.of(sampleBatch(0, false, LocalDate.of(2026, 1, 1))));
    assertThat(service.mapBatchesForDetail(PHARMACY, PRODUCT)).hasSize(1);
  }

  private PharmacyProduct sampleProduct() {
    return new PharmacyProduct(
        PRODUCT,
        PHARMACY,
        null,
        "Paracetamol 500mg Tab",
        null,
        null,
        15,
        "tablets",
        null,
        null,
        "TABLET",
        "OTC",
        null,
        BigDecimal.valueOf(12),
        2250L,
        false,
        false,
        true,
        0,
        List.of(),
        0,
        0,
        null,
        0L,
        null,
        null,
        NOW,
        NOW);
  }

  private ProductBatch sampleBatch(int qty, boolean active, LocalDate expiry) {
    return new ProductBatch(
        BATCH,
        PRODUCT,
        PHARMACY,
        "BN24001",
        expiry,
        null,
        Math.max(qty, 1),
        qty,
        1400L,
        2250L,
        active,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
