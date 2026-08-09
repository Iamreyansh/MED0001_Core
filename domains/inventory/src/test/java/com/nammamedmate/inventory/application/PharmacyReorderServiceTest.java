package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.LowStockProduct;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.SuggestionRow;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore.SupplyOffer;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.PoSentChannel;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderItem;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import com.nammamedmate.inventory.domain.ReorderSuggestionSnapshot;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PharmacyReorderServiceTest {

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

  private InMemoryOutboxStore outboxStore;
  private PharmacyReorderService service;
  private MedmatePrincipal owner;
  private MedmatePrincipal staff;

  @BeforeEach
  void setUp() {
    outboxStore = new InMemoryOutboxStore();
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
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            true,
            true);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void starterPlan_returnsPlanFeatureLocked() {
    when(planPort.growthFeaturesEnabled()).thenReturn(false);
    assertThatThrownBy(() -> service.listSuggestions(owner, "distributor", 1, 50))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_FEATURE_LOCKED");
  }

  @Test
  void listSuggestions_groupsByDistributor_andNullDaysOfCover() {
    List<SuggestionRow> rows = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      UUID pid = UUID.randomUUID();
      rows.add(
          suggestionRow(pid, "Product " + i, 40, 60, DIST_A, 1182L, "Medico", "+919876543210"));
    }
    when(suggestionStore.listLatest(PHARMACY, 1, 50))
        .thenReturn(new ReorderSuggestionStore.ListResult(rows, 24));
    when(poStore.countOpen(PHARMACY)).thenReturn(2L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.of(NOW));
    when(suggestionStore.listActiveOffers(eq(PHARMACY), any()))
        .thenReturn(
            List.of(
                new SupplyOffer(DIST_A, "Medico", "+919876543210", 1300L, "1 free on 10"),
                new SupplyOffer(DIST_B, "Apollo", "+919811111111", 1300L, null)));

    var page = service.listSuggestions(owner, "distributor", 1, 50);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) page.data().get("kpi");
    assertThat(kpi.get("items_below_reorder_level")).isEqualTo(24L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups =
        (List<Map<String, Object>>) page.data().get("suggestion_groups");
    assertThat(groups).isNotEmpty();
    assertThat(groups.get(0).get("distributor_name")).isEqualTo("Medico");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) groups.get(0).get("items");
    assertThat(items.get(0).get("days_of_cover")).isNull();
  }

  @Test
  void createPo_draftWithPoNumberFormat() {
    when(distributorStore.findById(PHARMACY, DIST_A))
        .thenReturn(Optional.of(activeDistributor(DIST_A, "Medico")));
    when(poStore.nextSequence(eq(PHARMACY), any())).thenReturn(18);
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.of(product(PRODUCT)));
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT))
        .thenReturn(List.of(new SupplyOffer(DIST_A, "Medico", "+919876543210", 1182L, null)));

    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      UUID pid = i == 0 ? PRODUCT : UUID.randomUUID();
      when(productStore.findById(PHARMACY, pid)).thenReturn(Optional.of(product(pid)));
      when(suggestionStore.listActiveOffers(PHARMACY, pid))
          .thenReturn(List.of(new SupplyOffer(DIST_A, "Medico", "+919876543210", 1182L, null)));
      items.add(Map.of("product_id", pid.toString(), "quantity", 10));
    }

    Map<String, Object> data = service.createPo(owner, DIST_A, items);
    assertThat(data.get("status")).isEqualTo("DRAFT");
    assertThat(data.get("po_number").toString()).matches("PO-\\d{4}-\\d{2}-\\d{6}");
    assertThat(data.get("items_count")).isEqualTo(12);
    verify(poStore).insert(any(PurchaseOrder.class));
  }

  @Test
  void patchPo_addsAndRemovesItems() {
    PurchaseOrder draft = draftPo();
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draft));
    when(productStore.findById(eq(PHARMACY), any())).thenReturn(Optional.of(product(PRODUCT)));
    when(suggestionStore.listActiveOffers(any(), any())).thenReturn(List.of());
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(13);
    when(poStore.estimatedTotalPaise(PHARMACY, PO_ID)).thenReturn(1000L);
    UUID removeId = UUID.randomUUID();
    UUID updateId = UUID.randomUUID();
    when(poStore.findItem(PHARMACY, PO_ID, updateId))
        .thenReturn(
            Optional.of(new PurchaseOrderItem(updateId, PO_ID, PHARMACY, PRODUCT, 5, 100L, NOW)));

    Map<String, Object> data =
        service.patchPo(
            owner,
            PO_ID,
            List.of(
                Map.of("product_id", PRODUCT.toString(), "quantity", 2),
                Map.of("product_id", UUID.randomUUID().toString(), "quantity", 3)),
            List.of(removeId),
            List.of(Map.of("item_id", updateId.toString(), "quantity", 8)));

    assertThat(data.get("items_count")).isEqualTo(13);
    verify(poStore).deleteItem(PHARMACY, PO_ID, removeId);
    verify(poStore).updateItemQuantity(updateId, 8);
  }

  @Test
  void sendPo_alreadySent_returnsPoAlreadySent() {
    PurchaseOrder sent =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST_A,
            "PO-2026-08-000001",
            PurchaseOrderStatus.SENT,
            owner.subject(),
            NOW,
            PoSentChannel.WHATSAPP,
            null,
            NOW,
            NOW,
            null);
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(sent));

    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_ALREADY_SENT");
  }

  @Test
  void recordGrn_fromSentPo_createsDraftGrn() {
    PurchaseOrder sent =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST_A,
            "PO-2026-08-000001",
            PurchaseOrderStatus.SENT,
            owner.subject(),
            NOW,
            PoSentChannel.WHATSAPP,
            null,
            NOW,
            NOW,
            null);
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(sent));
    when(grnStore.invoiceExists(PHARMACY, DIST_A, "INV-1")).thenReturn(false);
    when(poStore.listItems(PHARMACY, PO_ID))
        .thenReturn(
            List.of(
                new ItemWithProduct(
                    new PurchaseOrderItem(
                        UUID.randomUUID(), PO_ID, PHARMACY, PRODUCT, 12, 1182L, NOW),
                    "Paracetamol",
                    2000L,
                    12)));

    Map<String, Object> data = service.recordGrn(owner, PO_ID, "INV-1", LocalDate.of(2026, 8, 9));
    assertThat(data.get("grn_status")).isEqualTo("DRAFT");
    assertThat(data.get("prefilled_items_count")).isEqualTo(1);
    verify(grnStore).insert(any());
    verify(grnStore).insertItem(any());
    verify(poStore)
        .update(eq(PO_ID), eq(PurchaseOrderStatus.RECEIVED), any(), any(), any(UUID.class), any());
  }

  @Test
  void refresh_secondCallWithin5Min_returns429() {
    when(rateLimiter.tryAcquire(anyString(), eq(1), eq(300))).thenReturn(true, false);
    when(suggestionStore.listLowStockProducts(PHARMACY)).thenReturn(List.of());
    when(suggestionStore.replaceSnapshots(eq(PHARMACY), any(), any())).thenReturn(0);

    assertThat(service.refresh(owner).get("items_below_reorder_level")).isEqualTo(0);
    assertThatThrownBy(() -> service.refresh(owner))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void daysOfCover_nullWhenAvgDailyZero() {
    assertThat(PharmacyReorderService.daysOfCover(40)).isNull();
    assertThat(PharmacyReorderService.suggestedQuantity(40, 60)).isEqualTo(80);
  }

  @Test
  void sendPo_publishesOutboxIdsOnly() {
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draftPo()));
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(1);
    when(distributorStore.findById(PHARMACY, DIST_A))
        .thenReturn(Optional.of(activeDistributor(DIST_A, "Medico")));
    when(poStore.listItems(PHARMACY, PO_ID))
        .thenReturn(
            List.of(
                new ItemWithProduct(
                    new PurchaseOrderItem(
                        UUID.randomUUID(), PO_ID, PHARMACY, PRODUCT, 1, 100L, NOW),
                    "P",
                    200L,
                    12)));
    when(poStore.update(any(), any(), any(), any(), isNull(), any()))
        .thenAnswer(
            inv ->
                new PurchaseOrder(
                    PO_ID,
                    PHARMACY,
                    DIST_A,
                    "PO-2026-08-000001",
                    PurchaseOrderStatus.SENT,
                    owner.subject(),
                    NOW,
                    PoSentChannel.WHATSAPP,
                    null,
                    NOW,
                    NOW,
                    null));

    Map<String, Object> data = service.sendPo(owner, PO_ID, "WHATSAPP", null);
    assertThat(data.get("status")).isEqualTo("SENT");
    assertThat(outboxStore.findUnpublished(10)).hasSize(1);
    assertThat(outboxStore.findUnpublished(10).get(0).type()).isEqualTo("inventory.po.sent");
  }

  @Test
  void staffCannotSendPo() {
    assertThatThrownBy(() -> service.sendPo(staff, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("STAFF_CANNOT_SEND_PO");
  }

  @Test
  void channelUnavailable_whenWhatsappDisabled() {
    service =
        new PharmacyReorderService(
            suggestionStore,
            poStore,
            distributorStore,
            productStore,
            grnStore,
            planPort,
            new OutboxPublisher(outboxStore, new ObjectMapper()),
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            false,
            true);
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draftPo()));
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(1);

    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");
  }

  @Test
  void refreshPharmacy_picksBestDistributorByLandedCost() {
    when(suggestionStore.listLowStockProducts(PHARMACY))
        .thenReturn(List.of(new LowStockProduct(PRODUCT, "Para", "Cipla", 40, 60, 2000L, 12)));
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT))
        .thenReturn(
            List.of(
                new SupplyOffer(DIST_B, "Apollo", "+91", 1300L, null),
                new SupplyOffer(DIST_A, "Medico", "+91", 1300L, "1 free on 10")));

    ArgumentCaptor<List<ReorderSuggestionSnapshot>> captor = ArgumentCaptor.forClass(List.class);
    when(suggestionStore.replaceSnapshots(eq(PHARMACY), any(), captor.capture())).thenReturn(1);

    assertThat(service.refreshPharmacy(PHARMACY, NOW)).isEqualTo(1);
    assertThat(captor.getValue().get(0).bestDistributorId()).isEqualTo(DIST_A);
  }

  @Test
  void refreshAllPharmacies_iterates() {
    when(suggestionStore.listPharmacyIdsWithLowStock()).thenReturn(List.of(PHARMACY));
    when(suggestionStore.listLowStockProducts(PHARMACY)).thenReturn(List.of());
    when(suggestionStore.replaceSnapshots(eq(PHARMACY), any(), any())).thenReturn(0);
    service.refreshAllPharmacies();
    verify(suggestionStore).replaceSnapshots(eq(PHARMACY), any(), any());
  }

  @Test
  void listPurchaseOrders_andCreateEmptyItems() {
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
                        PurchaseOrderStatus.DRAFT,
                        NOW,
                        null)),
                1));
    assertThat(service.listPurchaseOrders(owner, null, null, 1, 20).meta().total()).isEqualTo(1);

    assertThatThrownBy(() -> service.createPo(owner, DIST_A, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EMPTY_ITEMS_LIST");
  }

  @Test
  void groupByUrgency() {
    when(suggestionStore.listLatest(PHARMACY, 1, 50))
        .thenReturn(
            new ReorderSuggestionStore.ListResult(
                List.of(
                    suggestionRow(PRODUCT, "P", 0, 60, null, null, null, null),
                    suggestionRow(UUID.randomUUID(), "Q", 10, 60, DIST_A, 100L, "M", "+91")),
                2));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.empty());
    when(suggestionStore.listActiveOffers(any(), any())).thenReturn(List.of());

    var page = service.listSuggestions(owner, "urgency", 1, 50);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> groups =
        (List<Map<String, Object>>) page.data().get("suggestion_groups");
    assertThat(groups).extracting(g -> g.get("urgency")).contains("OUT_OF_STOCK", "BELOW_REORDER");
  }

  private SuggestionRow suggestionRow(
      UUID productId,
      String name,
      int stock,
      int reorder,
      UUID distId,
      Long landed,
      String distName,
      String phone) {
    return new SuggestionRow(
        new ReorderSuggestionSnapshot(
            UUID.randomUUID(),
            PHARMACY,
            productId,
            stock,
            reorder,
            null,
            distId,
            landed,
            LocalDate.of(2026, 8, 9),
            NOW),
        name,
        "Cipla",
        distName,
        phone);
  }

  private PurchaseOrder draftPo() {
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

  private static Distributor activeDistributor(UUID id, String name) {
    return new Distributor(
        id,
        PHARMACY,
        name,
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
  }

  private static PharmacyProduct product(UUID id) {
    return new PharmacyProduct(
        id,
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
