package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ListResult;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.PoListRow;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.SuggestionRow;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.SupplyOffer;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderItem;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import com.nammamedmate.inventory.domain.ReorderSuggestionSnapshot;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
class PharmacyReorderServiceFinalCoverageTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID DIST_A = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private static final UUID DIST_B = UUID.fromString("bbbbbbbb-0002-4000-8000-000000000002");
  private static final UUID PRODUCT = UUID.fromString("cccccccc-0001-4000-8000-000000000001");
  private static final UUID PO_ID = UUID.fromString("dddddddd-0001-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private ReorderSuggestionStore suggestionStore;
  @Mock private PurchaseOrderStore poStore;
  @Mock private DistributorStore distributorStore;
  @Mock private PharmacyProductStore productStore;
  @Mock private PurchaseGrnStore grnStore;
  @Mock private InventoryPlanPort planPort;
  @Mock private RateLimiter rateLimiter;

  private PharmacyReorderService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(planPort.growthFeaturesEnabled()).thenReturn(true);
    service =
        new PharmacyReorderService(
            suggestionStore,
            poStore,
            distributorStore,
            productStore,
            grnStore,
            planPort,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            true,
            true);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void listPageNullData_andDefaults() {
    assertThat(new PharmacyReorderService.ListPage(null, PaginationMeta.of(1, 10, 0)).data())
        .isEmpty();
    when(suggestionStore.listLatest(PHARMACY, 1, 200))
        .thenReturn(new ReorderSuggestionStore.ListResult(List.of(), 0));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.empty());
    assertThat(service.listSuggestions(owner, " ", 0, 999).meta().limit()).isEqualTo(200);
  }

  @Test
  void patchPo_missingItemAndProduct_andNullRemoveId() {
    PurchaseOrder draft = draft();
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draft));
    when(poStore.findItem(any(), any(), any())).thenReturn(Optional.empty());
    java.util.ArrayList<UUID> removes = new java.util.ArrayList<>();
    removes.add(null);
    assertThatThrownBy(
            () ->
                service.patchPo(
                    owner,
                    PO_ID,
                    null,
                    removes,
                    List.of(Map.of("item_id", UUID.randomUUID().toString(), "quantity", 2))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_ITEM_NOT_FOUND");

    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draft));
    when(productStore.findById(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.patchPo(
                    owner,
                    PO_ID,
                    List.of(Map.of("product_id", PRODUCT.toString(), "quantity", 1)),
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void sendPo_missingDistributor_nullPrice_andMissingContacts() {
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draft()));
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(1);
    when(distributorStore.findById(PHARMACY, DIST_A)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    Distributor noPhone =
        new Distributor(
            DIST_A, PHARMACY, "F", null, " ", null, null, null, null, 0, 0L, true, NOW, NOW, null);
    when(distributorStore.findById(PHARMACY, DIST_A)).thenReturn(Optional.of(noPhone));
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Distributor noEmail =
        new Distributor(
            DIST_A,
            PHARMACY,
            "F",
            null,
            "+919876543210",
            " ",
            null,
            null,
            null,
            0,
            0L,
            true,
            NOW,
            NOW,
            null);
    when(distributorStore.findById(PHARMACY, DIST_A)).thenReturn(Optional.of(noEmail));
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "EMAIL", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Distributor ok =
        new Distributor(
            DIST_A,
            PHARMACY,
            "F",
            null,
            "+919876543210",
            "a@b.com",
            null,
            null,
            null,
            0,
            0L,
            true,
            NOW,
            NOW,
            null);
    when(distributorStore.findById(PHARMACY, DIST_A)).thenReturn(Optional.of(ok));
    when(poStore.listItems(PHARMACY, PO_ID))
        .thenReturn(
            List.of(
                new ItemWithProduct(
                    new PurchaseOrderItem(
                        UUID.randomUUID(), PO_ID, PHARMACY, PRODUCT, 1, null, NOW),
                    "P",
                    100L,
                    12)));
    when(poStore.update(any(), any(), any(), any(), isNull(), any())).thenReturn(draft());
    assertThat(service.sendPo(owner, PO_ID, "EMAIL", "  ").get("sent_to")).isEqualTo("a@b.com");
  }

  @Test
  void recordGrn_fallbackPurchasePrice() {
    PurchaseOrder sent =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST_A,
            "PO-2026-08-000001",
            PurchaseOrderStatus.SENT,
            owner.subject(),
            NOW,
            null,
            null,
            NOW,
            NOW,
            null);
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(sent));
    when(grnStore.invoiceExists(any(), any(), any())).thenReturn(false);
    when(poStore.listItems(PHARMACY, PO_ID))
        .thenReturn(
            List.of(
                new ItemWithProduct(
                    new PurchaseOrderItem(UUID.randomUUID(), PO_ID, PHARMACY, PRODUCT, 2, 0L, NOW),
                    "P",
                    0L,
                    5)));
    when(poStore.update(any(), any(), any(), any(), any(), any())).thenReturn(sent);
    assertThat(
            service
                .recordGrn(owner, PO_ID, "INV", LocalDate.of(2026, 8, 1))
                .get("prefilled_items_count"))
        .isEqualTo(1);
  }

  @Test
  void listWithNullDistributorGroup_andSingleOfferNoAlt() {
    when(suggestionStore.listLatest(PHARMACY, 1, 50))
        .thenReturn(
            new ReorderSuggestionStore.ListResult(
                List.of(
                    new SuggestionRow(
                        new ReorderSuggestionSnapshot(
                            UUID.randomUUID(),
                            PHARMACY,
                            PRODUCT,
                            10,
                            60,
                            null,
                            null,
                            null,
                            LocalDate.of(2026, 8, 9),
                            NOW),
                        "P",
                        "M",
                        null,
                        null)),
                1));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.of(NOW));
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT))
        .thenReturn(List.of(new SupplyOffer(DIST_A, "Only", "+91", 1000L, null)));

    var page = service.listSuggestions(owner, "distributor", 1, 50);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups =
        (List<Map<String, Object>>) page.data().get("suggestion_groups");
    assertThat(groups.get(0).get("distributor_name")).isEqualTo("no distributor linked");
  }

  @Test
  void listWithLandedAndSavings_andUrgencyEmptyGroupSkipped() {
    when(suggestionStore.listLatest(PHARMACY, 1, 50))
        .thenReturn(
            new ReorderSuggestionStore.ListResult(
                List.of(
                    new SuggestionRow(
                        new ReorderSuggestionSnapshot(
                            UUID.randomUUID(),
                            PHARMACY,
                            PRODUCT,
                            5,
                            60,
                            null,
                            DIST_A,
                            1100L,
                            LocalDate.of(2026, 8, 9),
                            NOW),
                        "P",
                        "M",
                        "Best",
                        "+9198")),
                1));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.empty());
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT))
        .thenReturn(
            List.of(
                new SupplyOffer(DIST_A, "Best", "+9198", 1100L, null),
                new SupplyOffer(DIST_B, "Alt", "+9199", 1500L, null)));

    var page = service.listSuggestions(owner, "urgency", 1, 50);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups =
        (List<Map<String, Object>>) page.data().get("suggestion_groups");
    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).get("urgency")).isEqualTo("BELOW_REORDER");
  }

  @Test
  void createPo_quantityAsNumber_andNullEstimated() {
    when(distributorStore.findById(PHARMACY, DIST_A))
        .thenReturn(
            Optional.of(
                new Distributor(
                    DIST_A,
                    PHARMACY,
                    "F",
                    null,
                    "+919876543210",
                    null,
                    null,
                    null,
                    null,
                    0,
                    0L,
                    true,
                    NOW,
                    NOW,
                    null)));
    when(poStore.nextSequence(any(), any())).thenReturn(1);
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(product()));
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT)).thenReturn(List.of());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("product_id", PRODUCT);
    item.put("quantity", Integer.valueOf(3));
    assertThat(service.createPo(owner, DIST_A, List.of(item)).get("items_count")).isEqualTo(1);
  }

  @Test
  void listPurchaseOrders_sentAtPresent() {
    when(poStore.list(any()))
        .thenReturn(
            new ListResult(
                List.of(
                    new PoListRow(
                        PO_ID,
                        "PO-2026-08-000001",
                        "Medico",
                        1,
                        100L,
                        PurchaseOrderStatus.SENT,
                        NOW,
                        NOW)),
                1));
    assertThat(
            service.listPurchaseOrders(owner, "SENT", DIST_A, 1, 20).data().get("purchase_orders"))
        .asList()
        .hasSize(1);
  }

  @Test
  void refreshPharmacy_noOffers() {
    when(suggestionStore.listLowStockProducts(PHARMACY))
        .thenReturn(
            List.of(
                new ReorderSuggestionStore.LowStockProduct(PRODUCT, "P", "M", 1, 10, 100L, 12)));
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT)).thenReturn(List.of());
    when(suggestionStore.replaceSnapshots(any(), any(), any())).thenReturn(1);
    assertThat(service.refreshPharmacy(PHARMACY, NOW)).isEqualTo(1);
  }

  @Test
  void pickBestOffer_emptyAndParseBlankChannel() {
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "  ", null));
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draft()));
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(1);
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "  ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  private PurchaseOrder draft() {
    return new PurchaseOrder(
        PO_ID,
        PHARMACY,
        DIST_A,
        "PO-2026-08-000001",
        PurchaseOrderStatus.DRAFT,
        owner.subject(),
        null,
        null,
        null,
        NOW,
        NOW,
        null);
  }

  private PharmacyProduct product() {
    return new PharmacyProduct(
        PRODUCT,
        PHARMACY,
        null,
        "Para",
        null,
        "Cipla",
        10,
        "TAB",
        null,
        null,
        "TABLET",
        "OTC",
        null,
        BigDecimal.valueOf(12),
        2000L,
        false,
        false,
        false,
        60,
        List.of(),
        40,
        0,
        null,
        0L,
        null,
        null,
        NOW,
        NOW);
  }
}
