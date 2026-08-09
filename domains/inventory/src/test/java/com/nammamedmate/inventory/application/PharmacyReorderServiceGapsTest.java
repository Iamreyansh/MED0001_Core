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
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore;
import com.nammamedmate.inventory.application.port.out.PurchaseOrderStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore;
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
class PharmacyReorderServiceGapsTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID DIST_A = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private static final UUID DIST_B = UUID.fromString("bbbbbbbb-0002-4000-8000-000000000002");
  private static final UUID DIST_C = UUID.fromString("bbbbbbbb-0003-4000-8000-000000000003");
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
  void nullPageLimit_andRateLimitOnMutators() {
    when(suggestionStore.listLatest(PHARMACY, 1, 50))
        .thenReturn(new ReorderSuggestionStore.ListResult(List.of(), 0));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.empty());
    assertThat(service.listSuggestions(owner, null, null, null).meta().page()).isEqualTo(1);

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(
            () ->
                service.createPo(
                    owner, DIST_A, List.of(Map.of("product_id", PRODUCT, "quantity", 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
    assertThatThrownBy(() -> service.listPurchaseOrders(owner, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
    assertThatThrownBy(() -> service.recordGrn(owner, PO_ID, "INV", LocalDate.now()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void threeOffers_hitsAlternativeComparator_andPositiveEstimatedPrice() {
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
                            1000L,
                            LocalDate.of(2026, 8, 9),
                            NOW),
                        "P",
                        "M",
                        "A",
                        "+91")),
                1));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.empty());
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT))
        .thenReturn(
            List.of(
                new SupplyOffer(DIST_A, "A", "+91", 1000L, null),
                new SupplyOffer(DIST_B, "B", "+91", 1200L, null),
                new SupplyOffer(DIST_C, "C", "+91", 1400L, null)));
    assertThat(service.listSuggestions(owner, "distributor", 1, 50).data().get("suggestion_groups"))
        .asList()
        .isNotEmpty();

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
    when(grnStore.invoiceExists(any(), any(), any())).thenReturn(false);
    when(poStore.listItems(PHARMACY, PO_ID))
        .thenReturn(
            List.of(
                new ItemWithProduct(
                    new PurchaseOrderItem(
                        UUID.randomUUID(), PO_ID, PHARMACY, PRODUCT, 2, 1500L, NOW),
                    "P",
                    2000L,
                    12)));
    when(poStore.update(any(), any(), any(), any(), any(), any())).thenReturn(sent);
    assertThat(
            service
                .recordGrn(owner, PO_ID, "INV2", LocalDate.of(2026, 8, 1))
                .get("prefilled_items_count"))
        .isEqualTo(1);
  }

  @Test
  void parsePositiveInt_nullAndNonNumber() {
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

    Map<String, Object> nullQty = new LinkedHashMap<>();
    nullQty.put("product_id", PRODUCT.toString());
    nullQty.put("quantity", null);
    assertThatThrownBy(() -> service.createPo(owner, DIST_A, List.of(nullQty)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.createPo(
                    owner,
                    DIST_A,
                    List.of(Map.of("product_id", PRODUCT.toString(), "quantity", "nope"))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void remainingCompoundBranches() {
    when(poStore.list(any())).thenReturn(new PurchaseOrderStore.ListResult(List.of(), 0));
    assertThat(service.listPurchaseOrders(owner, "  ", null, 2, 10).meta().page()).isEqualTo(2);

    PurchaseOrder received =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST_A,
            "PO-2026-08-000001",
            PurchaseOrderStatus.RECEIVED,
            owner.subject(),
            NOW,
            PoSentChannel.WHATSAPP,
            UUID.randomUUID(),
            NOW,
            NOW,
            null);
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(received));
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_ALREADY_SENT");

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
    assertThatThrownBy(() -> service.recordGrn(owner, PO_ID, null, LocalDate.of(2026, 8, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(grnStore.invoiceExists(any(), any(), any())).thenReturn(false);
    when(poStore.listItems(PHARMACY, PO_ID))
        .thenReturn(
            List.of(
                new ItemWithProduct(
                    new PurchaseOrderItem(
                        UUID.randomUUID(), PO_ID, PHARMACY, PRODUCT, 1, null, NOW),
                    "P",
                    100L,
                    12)));
    when(poStore.update(any(), any(), any(), any(), any(), any())).thenReturn(sent);
    assertThat(
            service
                .recordGrn(owner, PO_ID, "INV-NULL-PRICE", LocalDate.of(2026, 8, 1))
                .get("prefilled_items_count"))
        .isEqualTo(1);
  }

  @Test
  void nullItemsList_andNullContacts() {
    assertThatThrownBy(() -> service.createPo(owner, DIST_A, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EMPTY_ITEMS_LIST");

    PurchaseOrder draft =
        new PurchaseOrder(
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
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(draft));
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(1);
    when(distributorStore.findById(PHARMACY, DIST_A))
        .thenReturn(
            Optional.of(
                new Distributor(
                    DIST_A, PHARMACY, "F", null, null, null, null, null, null, 0, 0L, true, NOW,
                    NOW, null)));
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "EMAIL", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void pickBestOfferNullList_viaRefreshWithEmptyThenNullPath() {
    when(suggestionStore.listLatest(eq(PHARMACY), eq(1), eq(1)))
        .thenReturn(new ReorderSuggestionStore.ListResult(List.of(), 0));
    when(poStore.countOpen(PHARMACY)).thenReturn(0L);
    when(suggestionStore.latestRefreshedAt(PHARMACY)).thenReturn(Optional.empty());
    assertThat(service.listSuggestions(owner, "distributor", 1, 0).meta().limit()).isEqualTo(1);

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
                            null,
                            null,
                            LocalDate.of(2026, 8, 9),
                            NOW),
                        "P",
                        "M",
                        null,
                        null)),
                1));
    when(suggestionStore.listActiveOffers(PHARMACY, PRODUCT)).thenReturn(null);
    assertThat(service.listSuggestions(owner, "distributor", 1, 50).data().get("suggestion_groups"))
        .asList()
        .isNotEmpty();
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
