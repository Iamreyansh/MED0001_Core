package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.PurchaseGrnService.ListPage;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.GrnListRow;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListResult;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
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
import java.util.ArrayList;
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
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseGrnServiceTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID DIST = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private static final UUID PRODUCT = UUID.fromString("cccccccc-0001-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private PurchaseGrnStore grnStore;
  @Mock private DistributorStore distributorStore;
  @Mock private DistributorSupplyItemStore supplyItemStore;
  @Mock private PharmacyProductStore productStore;
  @Mock private ProductBatchStore batchStore;
  @Mock private RateLimiter rateLimiter;

  private PurchaseGrnService service;
  private MedmatePrincipal owner;
  private MedmatePrincipal staff;
  private Distributor distributor;
  private PharmacyProduct product;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new PurchaseGrnService(
            grnStore,
            distributorStore,
            supplyItemStore,
            productStore,
            batchStore,
            rateLimiter,
            clock,
            new ObjectMapper());
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
    distributor = Distributor.minimal(DIST, PHARMACY, "Medico Pharma", true, NOW);
    product = sampleProduct(PRODUCT, "Paracetamol 500mg Tab");
    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST))
        .thenReturn(Optional.of(distributor));
  }

  @Test
  void createDraft_andDuplicateInvoice() {
    when(grnStore.invoiceExists(PHARMACY, DIST, "INV-1")).thenReturn(false);
    when(grnStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> created = service.create(owner, DIST, "INV-1", LocalDate.of(2026, 7, 22));
    assertThat(created.get("status")).isEqualTo("DRAFT");
    assertThat(created.get("distributor_name")).isEqualTo("Medico Pharma");

    when(grnStore.invoiceExists(PHARMACY, DIST, "INV-1")).thenReturn(true);
    assertThatThrownBy(() -> service.create(owner, DIST, "INV-1", LocalDate.of(2026, 7, 22)))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DUPLICATE_INVOICE_NUMBER");
  }

  @Test
  void createDraft_usesWalkInWhenDistributorOmitted() {
    UUID walkInId = UUID.fromString("dddddddd-0001-4000-8000-000000000001");
    Distributor walkIn = Distributor.walkIn(walkInId, PHARMACY, NOW);
    when(distributorStore.findActiveSystem(PHARMACY)).thenReturn(Optional.empty());
    when(distributorStore.insertSystem(any())).thenReturn(walkIn);
    when(grnStore.invoiceExists(PHARMACY, walkInId, "WALK-1")).thenReturn(false);
    when(grnStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> created = service.create(owner, null, "WALK-1", LocalDate.of(2026, 7, 22));
    assertThat(created.get("status")).isEqualTo("DRAFT");
    assertThat(created.get("distributor_id")).isEqualTo(walkInId.toString());
    assertThat(created.get("distributor_name")).isEqualTo(Distributor.WALK_IN_FIRM);
    verify(distributorStore).insertSystem(any());
  }

  @Test
  void saveAndStock_createsBatches_freeQty_andRejectsStaffAndAlreadyStocked() {
    UUID grnId = UUID.randomUUID();
    PurchaseGrn draft = draftGrn(grnId);
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft));

    List<ItemWithProduct> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      UUID pid = UUID.randomUUID();
      PurchaseGrnItem item = lineItem(grnId, pid, "BN-" + i, 100, i == 0 ? 20 : 0, 1300, 2250, 12);
      items.add(new ItemWithProduct(item, "Prod" + i));
      when(batchStore.findByBatchNumber(PHARMACY, pid, "BN-" + i)).thenReturn(Optional.empty());
      when(productStore.findById(PHARMACY, pid))
          .thenReturn(Optional.of(withStock(sampleProduct(pid, "Prod" + i), i == 0 ? 120 : 100)));
    }
    when(grnStore.listItems(PHARMACY, grnId)).thenReturn(items);
    when(batchStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.updateStatus(eq(grnId), eq(GrnStatus.STOCKED), any(), any(), any()))
        .thenAnswer(
            inv ->
                new PurchaseGrn(
                    grnId,
                    PHARMACY,
                    DIST,
                    "INV",
                    LocalDate.of(2026, 7, 22),
                    GrnStatus.STOCKED,
                    NOW,
                    owner.subject(),
                    owner.subject(),
                    null,
                    NOW,
                    NOW,
                    null));

    Map<String, Object> result = service.saveAndStock(owner, grnId);
    assertThat(result.get("status")).isEqualTo("STOCKED");
    assertThat(result.get("batches_created")).isEqualTo(10);
    assertThat(result.get("total_units_added")).isEqualTo(100 * 10 + 20);

    ArgumentCaptor<ProductBatch> batchCap = ArgumentCaptor.forClass(ProductBatch.class);
    verify(batchStore, org.mockito.Mockito.atLeastOnce()).insert(batchCap.capture());
    ProductBatch freeBatch =
        batchCap.getAllValues().stream()
            .filter(b -> b.quantityReceived() == 120)
            .findFirst()
            .orElseThrow();
    assertThat(freeBatch.quantityReceived()).isEqualTo(120);

    assertThatThrownBy(() -> service.saveAndStock(staff, grnId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_CANNOT_STOCK");

    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(
            Optional.of(
                new PurchaseGrn(
                    grnId,
                    PHARMACY,
                    DIST,
                    "INV",
                    LocalDate.of(2026, 7, 22),
                    GrnStatus.STOCKED,
                    NOW,
                    owner.subject(),
                    owner.subject(),
                    null,
                    NOW,
                    NOW,
                    null)));
    assertThatThrownBy(() -> service.saveAndStock(owner, grnId))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_ALREADY_STOCKED");
  }

  @Test
  void listFiltersStocked_andCsvImportUnmatched() throws Exception {
    when(grnStore.list(any()))
        .thenReturn(
            new ListResult(
                List.of(
                    new GrnListRow(
                        UUID.randomUUID(),
                        "Medico",
                        "INV-S",
                        LocalDate.of(2026, 7, 1),
                        2,
                        260000L,
                        31200L,
                        291200L,
                        GrnStatus.STOCKED,
                        NOW)),
                1L));
    when(grnStore.kpi(eq(PHARMACY), any(), any())).thenReturn(new KpiRow(1, 31200L, 1));

    ListPage page = service.list(owner, "STOCKED", null, null, null, null, 1, 20);
    assertThat(page.data().get("grns")).asList().hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = ((List<Map<String, Object>>) page.data().get("grns")).getFirst();
    assertThat(row.get("status")).isEqualTo("STOCKED");
    assertThat(row.get("taxable_amount")).isEqualTo(new BigDecimal("2600.00"));

    when(grnStore.invoiceExists(any(), any(), anyString())).thenReturn(false);
    when(grnStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.updateImportUnmatched(any(), any(), any()))
        .thenAnswer(inv -> draftGrn((UUID) inv.getArgument(0)));

    StringBuilder csv = new StringBuilder();
    csv.append(
        "product_name,manufacturer,batch_number,expiry_date,quantity,free_quantity,purchase_price,mrp,gst_pct\n");
    for (int i = 0; i < 26; i++) {
      String name = "Known" + i;
      when(productStore.findByNameAndManufacturer(eq(PHARMACY), eq(name), anyString()))
          .thenReturn(Optional.of(sampleProduct(UUID.randomUUID(), name)));
      csv.append(name).append(",Mfg,BN").append(i).append(",2027-06-30,10,0,13.00,22.50,12\n");
    }
    for (int i = 0; i < 4; i++) {
      String name = "Unknown" + i;
      when(productStore.findByNameAndManufacturer(eq(PHARMACY), eq(name), anyString()))
          .thenReturn(Optional.empty());
      csv.append(name).append(",XYZ,BNU").append(i).append(",2027-06-30,5,0,10.00,20.00,12\n");
    }

    MockMultipartFile file =
        new MockMultipartFile(
            "csv_file", "inv.csv", "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8));
    Map<String, Object> imported =
        service.importCsv(owner, file, DIST, "CSV-1", LocalDate.of(2026, 7, 22));
    assertThat(imported.get("matched_rows")).isEqualTo(26);
    assertThat(imported.get("unmatched_rows")).isEqualTo(4);
    assertThat(imported.get("status")).isEqualTo("DRAFT");
  }

  @Test
  void addItem_patch_delete_get_andTopUp() {
    UUID grnId = UUID.randomUUID();
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draftGrn(grnId)));
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(product));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> added =
        service.addItem(
            owner,
            grnId,
            null,
            PRODUCT,
            false,
            null,
            null,
            null,
            null,
            "BN25100",
            LocalDate.of(2027, 6, 30),
            null,
            100,
            20,
            new BigDecimal("13.00"),
            new BigDecimal("22.50"),
            new BigDecimal("12"));
    assertThat(added.get("quantity_total")).isEqualTo(120);
    assertThat(added.get("taxable_amount")).isEqualTo(new BigDecimal("1300.00"));

    UUID itemId = UUID.fromString(added.get("item_id").toString());
    PurchaseGrnItem existing = lineItem(grnId, PRODUCT, "BN25100", 100, 20, 1300, 2250, 12);
    existing =
        new PurchaseGrnItem(
            itemId,
            grnId,
            PHARMACY,
            PRODUCT,
            "BN25100",
            LocalDate.of(2027, 6, 30),
            null,
            100,
            20,
            1300,
            2250,
            12,
            130000,
            15600,
            145600,
            false,
            NOW,
            NOW);
    when(grnStore.findItem(PHARMACY, grnId, itemId)).thenReturn(Optional.of(existing));
    when(grnStore.updateItem(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> patched =
        service.patchItem(owner, grnId, itemId, 250, null, null, null, null, null);
    assertThat(patched.get("quantity")).isEqualTo(250);

    when(grnStore.deleteItem(PHARMACY, grnId, itemId)).thenReturn(true);
    assertThat(service.deleteItem(owner, grnId, itemId).get("deleted")).isEqualTo(true);

    when(grnStore.listItems(PHARMACY, grnId))
        .thenReturn(List.of(new ItemWithProduct(existing, product.name())));
    when(grnStore.distributorFirmName(PHARMACY, DIST)).thenReturn("Medico Pharma");
    Map<String, Object> detail = service.get(owner, grnId);
    assertThat(detail.get("status")).isEqualTo("DRAFT");

    // top-up path on save-and-stock
    when(batchStore.findByBatchNumber(PHARMACY, PRODUCT, "BN25100"))
        .thenReturn(
            Optional.of(
                new ProductBatch(
                    UUID.randomUUID(),
                    PRODUCT,
                    PHARMACY,
                    "BN25100",
                    LocalDate.of(2027, 6, 30),
                    null,
                    50,
                    50,
                    1300,
                    2250,
                    true,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(batchStore.topUpFromGrn(any(), anyInt(), anyInt(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(
            inv ->
                new ProductBatch(
                    UUID.randomUUID(),
                    PRODUCT,
                    PHARMACY,
                    "BN25100",
                    LocalDate.of(2027, 6, 30),
                    null,
                    (int) inv.getArgument(1),
                    (int) inv.getArgument(2),
                    1300,
                    2250,
                    true,
                    null,
                    null,
                    inv.getArgument(5),
                    NOW,
                    NOW));
    when(productStore.findById(PHARMACY, PRODUCT))
        .thenReturn(Optional.of(withStock(sampleProduct(PRODUCT, product.name()), 170)));
    when(grnStore.listItems(PHARMACY, grnId))
        .thenReturn(List.of(new ItemWithProduct(existing, product.name())));
    when(grnStore.updateStatus(any(), any(), any(), any(), any()))
        .thenAnswer(inv -> draftGrn(grnId));

    Map<String, Object> stocked = service.saveAndStock(owner, grnId);
    assertThat(stocked.get("batches_topped_up")).isEqualTo(1);
    verify(batchStore).topUpFromGrn(any(), anyInt(), anyInt(), anyLong(), anyLong(), any(), any());
    verify(batchStore, never()).insert(any());
  }

  private PurchaseGrn draftGrn(UUID grnId) {
    return new PurchaseGrn(
        grnId,
        PHARMACY,
        DIST,
        "INV",
        LocalDate.of(2026, 7, 22),
        GrnStatus.DRAFT,
        null,
        null,
        owner.subject(),
        null,
        NOW,
        NOW,
        null);
  }

  private static PurchaseGrnItem lineItem(
      UUID grnId,
      UUID productId,
      String batch,
      int qty,
      int free,
      long purchasePaise,
      long mrpPaise,
      int gst) {
    long taxable = PurchaseGrnItem.taxablePaise(qty, purchasePaise);
    long gstAmt = PurchaseGrnItem.gstPaise(taxable, gst);
    return new PurchaseGrnItem(
        UUID.randomUUID(),
        grnId,
        PHARMACY,
        productId,
        batch,
        LocalDate.of(2027, 6, 30),
        null,
        qty,
        free,
        purchasePaise,
        mrpPaise,
        gst,
        taxable,
        gstAmt,
        PurchaseGrnItem.lineTotalPaise(taxable, gstAmt),
        false,
        NOW,
        NOW);
  }

  private PharmacyProduct sampleProduct(UUID id, String name) {
    return new PharmacyProduct(
        id,
        PHARMACY,
        null,
        name,
        null,
        "Mfg",
        10,
        "units",
        null,
        null,
        "TABLET",
        "OTC",
        null,
        BigDecimal.valueOf(12),
        2250,
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
        NOW);
  }

  private PharmacyProduct withStock(PharmacyProduct base, int s) {
    return new PharmacyProduct(
        base.id(),
        base.pharmacyId(),
        base.masterMedicineId(),
        base.name(),
        base.saltComposition(),
        base.manufacturer(),
        base.packSize(),
        base.packUnit(),
        base.categoryId(),
        base.categoryName(),
        base.form(),
        base.schedule(),
        base.hsnCode(),
        base.gstPct(),
        base.mrpPaise(),
        base.isRxOnly(),
        base.isLooseSellingEnabled(),
        base.isOnlineVisible(),
        base.reorderLevel(),
        base.rackLocations(),
        s,
        base.totalBatches(),
        base.earliestExpiry(),
        base.costValuePaise(),
        base.lastMovementAt(),
        base.productPhotoUrl(),
        base.createdAt(),
        base.updatedAt());
  }
}
