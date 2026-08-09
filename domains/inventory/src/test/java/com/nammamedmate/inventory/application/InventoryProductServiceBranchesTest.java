package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.InventoryExcelExporter;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListResult;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
class InventoryProductServiceBranchesTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID PRODUCT = UUID.randomUUID();

  @Mock private PharmacyProductStore store;
  @Mock private InventoryBatchService batchService;
  @Mock private InventoryPlanPort planPort;
  @Mock private InventoryExcelExporter excelExporter;

  private InventoryProductService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    when(planPort.growthFeaturesEnabled()).thenReturn(true);
    when(batchService.mapBatchesForDetail(any(), any())).thenReturn(List.of());
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
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
  void list_defaultsAndHasNext() {
    when(store.list(any(), any())).thenReturn(new ListResult(List.of(), 250, Map.of("ALL", 250L)));

    InventoryProductService.ListPage page =
        service.list(owner, "NOPE", null, "bad", "sideways", 0, 200, null);

    assertThat(page.meta().get("has_next")).isEqualTo(true);
    assertThat(page.meta().get("limit")).isEqualTo(100);
    assertThat(page.meta().get("page")).isEqualTo(1);

    service.list(owner, null, null, null, null, null, null, null);
    service.list(owner, " ", " ", " ", " ", 2, 1, null);
    service.list(owner, "ALL", null, "name", "desc", 1, 20, null);
  }

  @Test
  void staffForbiddenVariants_andNullFormScheduleGst() {
    MedmatePrincipal staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.patchSettings(staff, PRODUCT, true, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.patchSettings(staff, PRODUCT, null, null, 5, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    PharmacyProduct updated =
        new PharmacyProduct(
            PRODUCT,
            PHARMACY,
            null,
            "X",
            null,
            null,
            1,
            "t",
            null,
            null,
            "TABLET",
            "OTC",
            "30049099",
            BigDecimal.valueOf(12),
            100L,
            false,
            false,
            false,
            0,
            List.of(),
            1,
            0,
            null,
            0L,
            null,
            null,
            NOW,
            NOW);
    when(store.updateDetails(any(), any(), any(), any())).thenReturn(Optional.of(updated));
    service.patchDetails(
        owner, PRODUCT, "ok", null, null, null, null, null, null, null, null, null, null, null);
    service.patchDetails(
        owner,
        PRODUCT,
        null,
        null,
        null,
        null,
        null,
        null,
        "TABLET",
        "OTC",
        null,
        BigDecimal.valueOf(5),
        null,
        null);
  }

  @Test
  void listResultNullCopies() {
    assertThat(new ListResult(null, 0, null).rows()).isEmpty();
    assertThat(new ListResult(null, 0, null).tabCounts()).isEmpty();
    assertThat(new InventoryProductService.ListPage(null, null).data()).isEmpty();
    assertThat(new InventoryProductService.ExcelExport(null, "x.xlsx", "t").bytes()).isEmpty();
  }

  @Test
  void patchSettings_notFound() {
    when(store.updateSettings(any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.patchSettings(owner, PRODUCT, true, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void patchDetails_validationBranches() {
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner, PRODUCT, " ", null, null, null, null, null, null, null, null, null, null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    PRODUCT,
                    "n".repeat(201),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    PRODUCT,
                    null,
                    "s".repeat(501),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    PRODUCT,
                    null,
                    null,
                    "m".repeat(201),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner, PRODUCT, null, null, null, 0, null, null, null, null, null, null, null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner, PRODUCT, null, null, null, null, null, null, null, "ZZ", null, null,
                    null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void patchDetails_notFound() {
    when(store.updateDetails(any(), any(), any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    PRODUCT,
                    "ok",
                    "salt",
                    "mfg",
                    10,
                    "t",
                    null,
                    "tablet",
                    "otc",
                    "30049099",
                    BigDecimal.valueOf(18),
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void detail_zeroCost_nullMargin_andCategory() {
    PharmacyProduct row =
        new PharmacyProduct(
            PRODUCT,
            PHARMACY,
            null,
            "X",
            null,
            null,
            0,
            "t",
            UUID.randomUUID(),
            "Cat",
            "TABLET",
            "OTC",
            null,
            BigDecimal.ZERO,
            100L,
            false,
            false,
            false,
            0,
            null,
            10,
            0,
            null,
            0L,
            null,
            null,
            NOW,
            NOW);
    when(store.findById(eq(PHARMACY), eq(PRODUCT))).thenReturn(Optional.of(row));

    Map<String, Object> data = service.get(owner, PRODUCT);
    assertThat(data.get("margin_pct")).isNull();
    assertThat(data.get("category_id")).isNotNull();
    assertThat(data.get("last_sold_at")).isNull();
    assertThat(data.get("total_stock_packs")).isEqualTo(0);
  }
}
