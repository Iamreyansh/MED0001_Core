package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.InventoryExcelExporter;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListResult;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.SummaryRow;
import com.nammamedmate.inventory.domain.PharmacyProduct;
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
class InventoryProductServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final UUID PRODUCT = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @Mock private PharmacyProductStore store;
  @Mock private InventoryBatchService batchService;
  @Mock private InventoryPlanPort planPort;
  @Mock private InventoryExcelExporter excelExporter;

  private InventoryProductService service;

  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  private final MedmatePrincipal staff =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(planPort.growthFeaturesEnabled()).thenReturn(true);
    when(batchService.mapBatchesForDetail(any(), any())).thenReturn(List.of());
    service =
        new InventoryProductService(
            store,
            batchService,
            planPort,
            excelExporter,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_lowStockTab_passesTabToStore() {
    when(store.list(any(), any()))
        .thenReturn(
            new ListResult(List.of(sampleProduct(40, 60, null, NOW)), 1, Map.of("ALL", 1L)));

    service.list(staff, "LOW_STOCK", null, null, null, 1, 20, null);

    ArgumentCaptor<ListFilter> cap = ArgumentCaptor.forClass(ListFilter.class);
    verify(store).list(cap.capture(), eq(NOW));
    assertThat(cap.getValue().tab()).isEqualTo("LOW_STOCK");
  }

  @Test
  void ac_expiringTab_productWithinFourMonths_hasExpiringFlag() {
    LocalDate expiry = LocalDate.of(2026, 10, 31);
    PharmacyProduct row = sampleProduct(100, 0, expiry, NOW);
    when(store.list(any(), any()))
        .thenReturn(new ListResult(List.of(row), 1, Map.of("EXPIRING", 1L)));

    InventoryProductService.ListPage page =
        service.list(staff, "EXPIRING", null, null, null, 1, 20, null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> products = (List<Map<String, Object>>) page.data().get("products");
    assertThat(products.get(0).get("flags")).asList().contains("EXPIRING");
  }

  @Test
  void ac_alertsTab_deadStockFlag_whenNoMovement() {
    PharmacyProduct dead = sampleProduct(100, 0, null, null);
    when(store.list(any(), any()))
        .thenReturn(new ListResult(List.of(dead), 1, Map.of("ALERTS", 1L)));

    InventoryProductService.ListPage page =
        service.list(staff, "ALERTS", null, null, null, 1, 20, null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> products = (List<Map<String, Object>>) page.data().get("products");
    assertThat(products.get(0).get("flags")).asList().contains("dead_stock");
  }

  @Test
  void ac_staffPatchOnlineVisible_forbidden() {
    assertThatThrownBy(() -> service.patchSettings(staff, PRODUCT, null, true, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac_freePlanOnlineVisible_planFeatureLocked() {
    when(planPort.growthFeaturesEnabled()).thenReturn(false);

    assertThatThrownBy(() -> service.patchSettings(owner, PRODUCT, null, true, null, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PLAN_FEATURE_LOCKED");
  }

  @Test
  void ac_exportExcel_returnsBytes() {
    when(store.listAllForExport(any(), any())).thenReturn(List.of(sampleProduct(10, 0, null, NOW)));
    when(excelExporter.export(any())).thenReturn(new byte[] {'P', 'K', 3, 4});

    InventoryProductService.ExcelExport file =
        service.exportExcel(owner, "ALL", null, null, null, null);

    assertThat(file.bytes()).startsWith((byte) 'P', (byte) 'K');
    assertThat(file.contentType()).contains("spreadsheetml");
    assertThat(file.filename()).endsWith(".xlsx");
  }

  @Test
  void ac_searchQ_passesQuery() {
    when(store.list(any(), any())).thenReturn(new ListResult(List.of(), 0, Map.of("ALL", 0L)));

    service.list(staff, "ALL", "para", null, null, 1, 20, null);

    ArgumentCaptor<ListFilter> cap = ArgumentCaptor.forClass(ListFilter.class);
    verify(store).list(cap.capture(), eq(NOW));
    assertThat(cap.getValue().q()).isEqualTo("para");
  }

  @Test
  void ac_invalidGstPct_rejected() {
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    PRODUCT,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    BigDecimal.valueOf(7),
                    null,
                    null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GST_PCT");
  }

  @Test
  void patchDetails_invalidHsn_rejected() {
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner, PRODUCT, null, null, null, null, null, null, null, null, "123", null,
                    null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_HSN_CODE");
  }

  @Test
  void patchSettings_invalidReorder_rejected() {
    assertThatThrownBy(() -> service.patchSettings(owner, PRODUCT, null, null, -1, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_REORDER_LEVEL");
  }

  @Test
  void get_notFound() {
    when(store.findById(PHARMACY, PRODUCT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(owner, PRODUCT))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void get_detailReturnsBatchesFromBatchService() {
    when(store.findById(PHARMACY, PRODUCT))
        .thenReturn(Optional.of(sampleProduct(450, 60, LocalDate.of(2026, 10, 31), NOW)));
    when(batchService.mapBatchesForDetail(PHARMACY, PRODUCT))
        .thenReturn(List.of(Map.of("batch_number", "BN24001", "quantity_current", 150)));

    Map<String, Object> data = service.get(owner, PRODUCT);

    assertThat(data.get("batches")).asList().hasSize(1);
    assertThat(data.get("recent_movements")).asList().isEmpty();
    assertThat(data.get("units_sold_30d")).isEqualTo(0);
    assertThat(data.get("days_of_cover")).isNull();
  }

  @Test
  void summary_mapsPaiseToRupees() {
    when(store.summary(eq(PHARMACY), eq(NOW)))
        .thenReturn(new SummaryRow(2, 100, 675000L, 1012500L, 1, 1, 0, 0, 0));

    Map<String, Object> data = service.summary(owner);

    assertThat(data.get("stock_value_at_cost")).isEqualTo(new BigDecimal("6750.00"));
    assertThat(data.get("retail_value_mrp")).isEqualTo(new BigDecimal("10125.00"));
    assertThat(data.get("as_of")).isEqualTo(NOW.toString());
  }

  @Test
  void patchSettings_ownerSuccess() {
    PharmacyProduct updated = sampleProduct(450, 60, LocalDate.of(2026, 10, 31), NOW);
    when(store.updateSettings(eq(PHARMACY), eq(PRODUCT), any(), eq(NOW)))
        .thenReturn(Optional.of(updated));

    Map<String, Object> data = service.patchSettings(owner, PRODUCT, false, true, 60, "A1-03");

    assertThat(data).containsEntry("is_online_visible", true).containsEntry("reorder_level", 60);
  }

  @Test
  void staffMayUpdateRackOnly() {
    PharmacyProduct updated = sampleProduct(10, 0, null, NOW);
    when(store.updateSettings(eq(PHARMACY), eq(PRODUCT), any(), eq(NOW)))
        .thenReturn(Optional.of(updated));

    Map<String, Object> data = service.patchSettings(staff, PRODUCT, null, null, null, "B2");

    assertThat(data.get("id")).isEqualTo(PRODUCT.toString());
  }

  @Test
  void patchDetails_success() {
    PharmacyProduct updated = sampleProduct(10, 0, null, NOW);
    when(store.updateDetails(eq(PHARMACY), eq(PRODUCT), any(), eq(NOW)))
        .thenReturn(Optional.of(updated));

    Map<String, Object> data =
        service.patchDetails(
            owner,
            PRODUCT,
            "Para 500",
            null,
            null,
            null,
            null,
            null,
            "TABLET",
            "OTC",
            "30049099",
            BigDecimal.valueOf(12),
            List.of("A1"),
            null);

    assertThat(data).containsEntry("hsn_code", "30049099");
  }

  @Test
  void unauthorized_whenNoPrincipal() {
    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  private static PharmacyProduct sampleProduct(
      int units, int reorder, LocalDate expiry, Instant lastMovement) {
    return new PharmacyProduct(
        PRODUCT,
        PHARMACY,
        null,
        "Paracetamol 500mg Tab",
        "Paracetamol 500mg",
        "Cipla Ltd",
        15,
        "tablets",
        null,
        null,
        "TABLET",
        "OTC",
        "30049099",
        BigDecimal.valueOf(12),
        2250L,
        false,
        false,
        true,
        reorder,
        List.of("A1-03"),
        units,
        1,
        expiry,
        675000L,
        lastMovement,
        null,
        NOW,
        NOW);
  }
}
