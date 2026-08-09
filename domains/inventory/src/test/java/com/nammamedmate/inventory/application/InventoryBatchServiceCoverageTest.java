package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryAlertRow;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryBatchServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID PRODUCT = UUID.randomUUID();
  private static final UUID BATCH = UUID.randomUUID();

  @Mock private ProductBatchStore batchStore;
  @Mock private PharmacyProductStore productStore;
  @Mock private RateLimiter rateLimiter;

  private InventoryBatchService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    service =
        new InventoryBatchService(
            batchStore,
            productStore,
            new SimpleXlsxExporter(),
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void rateLimitAndAuthBranches() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.expiryAlerts(owner))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    assertThatThrownBy(() -> service.listBatches(null, PRODUCT, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listBatches(customer, PRODUCT, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal noPh =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listBatches(noPh, PRODUCT, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void addBatchValidationEdges() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(product()));
    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    " ",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    0,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    -1,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

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
                    null,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

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
                    new BigDecimal("1.999"),
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

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
                    BigDecimal.ZERO,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adjustAndWriteOffEdges() {
    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, -1, "NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, 0, "DAMAGE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(batchStore.findById(PHARMACY, PRODUCT, BATCH)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, 1, "RETURN"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("BATCH_NOT_FOUND");

    when(batchStore.findById(PHARMACY, PRODUCT, BATCH)).thenReturn(Optional.of(batch(10, true)));
    when(batchStore.updateQuantities(any(), anyInt(), anyInt(), anyBoolean(), any()))
        .thenReturn(batch(0, false));
    Map<String, Object> zeroed =
        service.adjustBatch(owner, PRODUCT, BATCH, -10, "AUDIT_CORRECTION");
    assertThat(zeroed.get("after_qty")).isEqualTo(0);

    assertThatThrownBy(() -> service.writeOffBatch(owner, PRODUCT, BATCH, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.writeOffBatch(owner, PRODUCT, BATCH, "   ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.expiryReport(owner, 25, "JSON"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(batchStore.listExpiryReport(any(), anyInt(), any())).thenReturn(List.of());
    @SuppressWarnings("unchecked")
    Map<String, Object> blankExport = (Map<String, Object>) service.expiryReport(owner, 4, "  ");
    assertThat(blankExport.get("total_batches")).isEqualTo(0);

    assertThatThrownBy(() -> service.writeOffBatch(owner, PRODUCT, BATCH, "OTHER", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () -> service.writeOffBatch(owner, PRODUCT, BATCH, "EXPIRED", "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void expiryAlertBucketsAndReportMonths() {
    UUID p2 = UUID.randomUUID();
    when(batchStore.listExpiringWithinMonths(any(), anyInt(), any()))
        .thenReturn(
            List.of(
                new ExpiryAlertRow(
                    PRODUCT, "A", "B1", LocalDate.of(2026, 8, 20), 1, 100L, List.of()),
                new ExpiryAlertRow(
                    PRODUCT, "A", "B2", LocalDate.of(2026, 9, 20), 2, 100L, List.of()),
                new ExpiryAlertRow(p2, "C", "B3", LocalDate.of(2026, 11, 1), 3, 100L, List.of())));

    Map<String, Object> alerts = service.expiryAlerts(owner);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups = (List<Map<String, Object>>) alerts.get("groups");
    assertThat(groups).hasSize(3);
    assertThat(groups.get(1).get("bucket")).isEqualTo("1_TO_2_MONTHS");
    assertThat(groups.get(2).get("bucket")).isEqualTo("2_TO_4_MONTHS");

    assertThatThrownBy(() -> service.expiryReport(owner, 0, "JSON"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void listBatchesNotFound() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listBatches(owner, PRODUCT, true))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void fileExportCloneAndPaiseHelpers() {
    InventoryBatchService.FileExport empty = new InventoryBatchService.FileExport(null, "a", "b");
    assertThat(empty.bytes()).isEmpty();
    assertThat(InventoryBatchService.paiseToRupees(25500)).isEqualByComparingTo("255.00");
  }

  @Test
  void moreEdgeBranches() {
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(product()));
    when(batchStore.findByBatchNumber(PHARMACY, PRODUCT, "BN")).thenReturn(Optional.empty());
    when(batchStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    // freeQuantity null defaults to 0; manufactured date present
    Map<String, Object> created =
        service.addBatch(
            owner,
            PRODUCT,
            "BN",
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2025, 1, 1),
            5,
            null,
            BigDecimal.ONE,
            BigDecimal.TEN);
    assertThat(created.get("quantity_received")).isEqualTo(5);

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    null,
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "x".repeat(51),
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner, PRODUCT, "BN2", null, null, 1, 0, BigDecimal.ONE, BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");

    assertThatThrownBy(
            () ->
                service.addBatch(
                    owner,
                    PRODUCT,
                    "BN2",
                    LocalDate.of(2027, 1, 1),
                    null,
                    null,
                    0,
                    BigDecimal.ONE,
                    BigDecimal.TEN))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, -1, "  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MISSING_REASON");

    assertThatThrownBy(() -> service.adjustBatch(owner, PRODUCT, BATCH, null, "DAMAGE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(batchStore.listByProduct(PHARMACY, PRODUCT, true))
        .thenReturn(
            List.of(
                new ProductBatch(
                    BATCH,
                    PRODUCT,
                    PHARMACY,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    LocalDate.of(2025, 6, 1),
                    10,
                    10,
                    100L,
                    200L,
                    false,
                    null,
                    null,
                    null,
                    null,
                    NOW),
                new ProductBatch(
                    UUID.randomUUID(),
                    PRODUCT,
                    PHARMACY,
                    "BN2",
                    LocalDate.of(2027, 2, 1),
                    null,
                    1,
                    1,
                    100L,
                    200L,
                    true,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    List<Map<String, Object>> mapped = service.mapBatchesForDetail(PHARMACY, PRODUCT);
    assertThat(mapped.get(0).get("is_active")).isEqualTo(false);
    assertThat(mapped.get(0).get("manufactured_date")).isEqualTo("2025-06-01");
    assertThat(mapped.get(0).get("received_date")).isNull();
    assertThat(mapped.get(1).get("manufactured_date")).isNull();
    assertThat(mapped.get(1).get("received_date")).isEqualTo(NOW.toString());

    when(batchStore.listExpiringWithinMonths(any(), anyInt(), any()))
        .thenReturn(
            List.of(
                new ExpiryAlertRow(
                    PRODUCT, "A", "BX", LocalDate.of(2028, 1, 1), 1, 100L, List.of())));
    Map<String, Object> alerts = service.expiryAlerts(owner);
    @SuppressWarnings("unchecked")
    Map<String, Object> summary = (Map<String, Object>) alerts.get("summary");
    assertThat(summary.get("total_expiring_units")).isEqualTo(0L);

    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(product()));
    when(batchStore.listByProduct(PHARMACY, PRODUCT, false)).thenReturn(List.of(batch(10, false)));
    assertThat(service.listBatches(owner, PRODUCT, false).get("total_active_units")).isEqualTo(0);
  }

  private PharmacyProduct product() {
    return new PharmacyProduct(
        PRODUCT,
        PHARMACY,
        null,
        "n",
        null,
        null,
        1,
        "t",
        null,
        null,
        "TABLET",
        "OTC",
        null,
        BigDecimal.TEN,
        100L,
        false,
        false,
        false,
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

  private ProductBatch batch(int qty, boolean active) {
    return new ProductBatch(
        BATCH,
        PRODUCT,
        PHARMACY,
        "BN",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2025, 1, 1),
        Math.max(qty, 1),
        qty,
        100L,
        200L,
        active,
        null,
        null,
        null,
        NOW,
        NOW);
  }
}
