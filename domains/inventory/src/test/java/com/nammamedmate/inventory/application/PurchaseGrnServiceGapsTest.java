package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListResult;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseGrnServiceGapsTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-ffff-4fff-8fff-000000000001");
  private static final UUID DIST = UUID.fromString("bbbbbbbb-ffff-4fff-8fff-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private PurchaseGrnStore grnStore;
  @Mock private DistributorStore distributorStore;
  @Mock private DistributorSupplyItemStore supplyItemStore;
  @Mock private PharmacyProductStore productStore;
  @Mock private ProductBatchStore batchStore;
  @Mock private RateLimiter rateLimiter;

  private PurchaseGrnService service;
  private MedmatePrincipal owner;
  private Distributor activeDist;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    service =
        new PurchaseGrnService(
            grnStore,
            distributorStore,
            supplyItemStore,
            productStore,
            batchStore,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    activeDist = Distributor.minimal(DIST, PHARMACY, "D", true, NOW);
    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST))
        .thenReturn(Optional.of(activeDist));
    when(grnStore.list(any())).thenReturn(new ListResult(null, 0));
    when(grnStore.kpi(any(), any(), any())).thenReturn(new KpiRow(0, 0, 0));
  }

  @Test
  void listDefaultsAndValidationErrors() {
    service.list(owner, " ", null, null, null, null, 0, null);
    service.list(owner, null, null, null, null, null, 1, 0);
    service.list(owner, null, null, null, null, null, 1, 500);

    assertThatThrownBy(() -> service.create(owner, null, "I", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, DIST, " ", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, DIST, "I", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST))
        .thenReturn(
            Optional.of(Distributor.minimal(DIST, PHARMACY, "D", true, NOW).withDeletedAt(NOW)));
    assertThatThrownBy(() -> service.create(owner, DIST, "I", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");
  }

  @Test
  void addItemPatchDeleteBranches() {
    UUID grnId = UUID.randomUUID();
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId)));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));
    when(productStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    UUID.randomUUID(),
                    false,
                    null,
                    null,
                    null,
                    null,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    null,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    null,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "BAD",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    service.addItem(
        owner,
        grnId,
        null,
        null,
        true,
        "Syrup",
        null,
        1,
        "SYRUP",
        "BN",
        LocalDate.of(2027, 1, 1),
        null,
        1,
        -0,
        new BigDecimal("1"),
        new BigDecimal("2"),
        new BigDecimal("0"));
    service.addItem(
        owner,
        grnId,
        null,
        null,
        true,
        "Inj",
        null,
        1,
        "INJECTION",
        "BN2",
        LocalDate.of(2027, 1, 1),
        LocalDate.of(2026, 1, 1),
        1,
        null,
        new BigDecimal("1"),
        new BigDecimal("2"),
        new BigDecimal("18"));

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "X",
                    null,
                    1,
                    "TABLET",
                    "",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "X",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2020, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "X",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    0,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "X",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    -1,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID itemId = UUID.randomUUID();
    when(grnStore.findItem(PHARMACY, grnId, itemId)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.patchItem(owner, grnId, itemId, 1, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEM_NOT_FOUND");

    PurchaseGrnItem cur =
        new PurchaseGrnItem(
            itemId,
            grnId,
            PHARMACY,
            UUID.randomUUID(),
            "BN",
            LocalDate.of(2027, 1, 1),
            null,
            10,
            0,
            100,
            200,
            12,
            1000,
            120,
            1120,
            false,
            NOW,
            NOW);
    when(grnStore.findItem(PHARMACY, grnId, itemId)).thenReturn(Optional.of(cur));
    when(grnStore.updateItem(any())).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () -> service.patchItem(owner, grnId, itemId, 0, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchItem(owner, grnId, itemId, 1, -1, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchItem(
                    owner, grnId, itemId, null, null, null, null, LocalDate.of(2020, 1, 1), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");

    service.patchItem(
        owner,
        grnId,
        itemId,
        null,
        2,
        new BigDecimal("1.25"),
        new BigDecimal("2.50"),
        LocalDate.of(2027, 2, 1),
        new BigDecimal("5"));

    when(grnStore.deleteItem(PHARMACY, grnId, itemId)).thenReturn(false);
    assertThatThrownBy(() -> service.deleteItem(owner, grnId, itemId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ITEM_NOT_FOUND");

    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.saveAndStock(owner, grnId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_NOT_FOUND");
    assertThatThrownBy(() -> service.get(owner, grnId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_NOT_FOUND");
  }

  @Test
  void saveAndStockNewProductAndCsvPaths() throws Exception {
    UUID grnId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId)));
    PurchaseGrnItem item =
        new PurchaseGrnItem(
            UUID.randomUUID(),
            grnId,
            PHARMACY,
            productId,
            "BN",
            LocalDate.of(2027, 1, 1),
            null,
            10,
            5,
            100,
            200,
            12,
            1000,
            120,
            1120,
            true,
            NOW,
            NOW);
    when(grnStore.listItems(PHARMACY, grnId)).thenReturn(List.of(new ItemWithProduct(item, "P")));
    when(batchStore.findByBatchNumber(any(), any(), any())).thenReturn(Optional.empty());
    when(batchStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(productStore.findById(PHARMACY, productId))
        .thenReturn(
            Optional.of(
                new PharmacyProduct(
                    productId,
                    PHARMACY,
                    null,
                    "P",
                    null,
                    "M",
                    1,
                    "u",
                    null,
                    null,
                    "TABLET",
                    "OTC",
                    null,
                    BigDecimal.TEN,
                    200,
                    false,
                    false,
                    false,
                    0,
                    List.of(),
                    15,
                    1,
                    null,
                    0,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(grnStore.updateStatus(any(), any(), any(), any(), any())).thenReturn(draft(grnId));

    Map<String, Object> stocked = service.saveAndStock(owner, grnId);
    assertThat(stocked.get("new_products_created")).isEqualTo(1);

    assertThatThrownBy(() -> service.importCsv(owner, null, DIST, "I", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");
    assertThatThrownBy(
            () ->
                service.importCsv(
                    owner,
                    new MockMultipartFile("csv_file", "x.csv", "text/csv", new byte[0]),
                    DIST,
                    "I",
                    LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");

    when(grnStore.invoiceExists(any(), any(), anyString())).thenReturn(false);
    when(grnStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.updateImportUnmatched(any(), any(), any())).thenReturn(draft(grnId));
    when(productStore.findByNameAndManufacturer(eq(PHARMACY), eq("Known"), anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyProduct(
                    productId,
                    PHARMACY,
                    null,
                    "Known",
                    null,
                    "M",
                    1,
                    "u",
                    null,
                    null,
                    "TABLET",
                    "OTC",
                    null,
                    BigDecimal.TEN,
                    200,
                    false,
                    false,
                    false,
                    0,
                    List.of(),
                    0,
                    0,
                    null,
                    0,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(productStore.findByNameAndManufacturer(eq(PHARMACY), eq("Known, Extra"), anyString()))
        .thenReturn(
            Optional.of(
                new PharmacyProduct(
                    productId,
                    PHARMACY,
                    null,
                    "Known, Extra",
                    null,
                    "M",
                    1,
                    "u",
                    null,
                    null,
                    "TABLET",
                    "OTC",
                    null,
                    BigDecimal.TEN,
                    200,
                    false,
                    false,
                    false,
                    0,
                    List.of(),
                    0,
                    0,
                    null,
                    0,
                    null,
                    null,
                    NOW,
                    NOW)));

    String csv =
        """
        product_name,manufacturer,batch_number,expiry_date,quantity,free_quantity,purchase_price,mrp,gst_pct
        "Known, Extra","M","BN1",2027-06-30,10,,13.00,22.50,12
        Known,M,BN2,2027-06-30,10,5,13.00,22.50,12
        Known,M,BN3,2027-06-30,10, ,13.00,22.50,12
        """;
    service.importCsv(
        owner,
        new MockMultipartFile(
            "csv_file", "a.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)),
        DIST,
        "CSV-OK",
        LocalDate.of(2026, 7, 1));

    String badQty =
        """
        product_name,manufacturer,batch_number,expiry_date,quantity,free_quantity,purchase_price,mrp,gst_pct
        Known,M,BN1,2027-06-30,0,0,13.00,22.50,12
        """;
    assertThatThrownBy(
            () ->
                service.importCsv(
                    owner,
                    new MockMultipartFile(
                        "csv_file", "b.csv", "text/csv", badQty.getBytes(StandardCharsets.UTF_8)),
                    DIST,
                    "CSV-BAD",
                    LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    String past =
        """
        product_name,manufacturer,batch_number,expiry_date,quantity,free_quantity,purchase_price,mrp,gst_pct
        Known,M,BN1,2020-01-01,1,0,13.00,22.50,12
        """;
    assertThatThrownBy(
            () ->
                service.importCsv(
                    owner,
                    new MockMultipartFile(
                        "csv_file", "c.csv", "text/csv", past.getBytes(StandardCharsets.UTF_8)),
                    DIST,
                    "CSV-PAST",
                    LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");

    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(Optional.of(draftWithUnmatched(grnId, "not-json")));
    assertThatThrownBy(() -> service.confirmImport(owner, grnId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");

    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(Optional.of(draftWithUnmatched(grnId, "[{\"row_number\":1}]")));
    when(grnStore.updateImportUnmatched(any(), any(), any())).thenReturn(draft(grnId));
    assertThat(service.confirmImport(owner, grnId).get("items_created")).isEqualTo(0);

    assertThatThrownBy(() -> PurchaseGrnService.rupeesToPaise(new BigDecimal("1.234"), "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> PurchaseGrnService.rupeesToPaise(BigDecimal.ZERO, "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId)));
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "X",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12.5")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_GST_PCT");

    // remaining compound-condition branches
    service.list(owner, null, null, null, null, null, null, 20);
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    "   ",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(Optional.of(draftWithUnmatched(grnId, null)));
    assertThat(service.confirmImport(owner, grnId).get("items_created")).isEqualTo(0);
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "   ",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "TABLET",
                    null,
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, DIST, "x".repeat(101), LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, DIST, null, LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(grnStore.invoiceExists(any(), any(), anyString())).thenReturn(false);
    when(grnStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.updateImportUnmatched(any(), any(), any())).thenReturn(draft(grnId));
    when(productStore.findByNameAndManufacturer(any(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    String shortRow =
        """
        product_name,manufacturer,batch_number,expiry_date,quantity,free_quantity,purchase_price,mrp,gst_pct
        OnlyName
        """;
    Map<String, Object> importedShort =
        service.importCsv(
            owner,
            new MockMultipartFile(
                "csv_file", "s.csv", "text/csv", shortRow.getBytes(StandardCharsets.UTF_8)),
            DIST,
            "SHORT",
            LocalDate.of(2026, 7, 1));
    assertThat(importedShort.get("unmatched_rows")).isEqualTo(1);
  }

  private PurchaseGrn draft(UUID grnId) {
    return draftWithUnmatched(grnId, null);
  }

  private PurchaseGrn draftWithUnmatched(UUID grnId, String unmatched) {
    return new PurchaseGrn(
        grnId,
        PHARMACY,
        DIST,
        "I",
        LocalDate.of(2026, 7, 1),
        GrnStatus.DRAFT,
        null,
        null,
        owner.subject(),
        unmatched,
        NOW,
        NOW,
        null);
  }
}
