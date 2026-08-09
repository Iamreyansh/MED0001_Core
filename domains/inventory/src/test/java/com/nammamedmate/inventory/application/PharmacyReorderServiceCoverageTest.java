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
import com.nammamedmate.inventory.application.port.out.ReorderSuggestionStore;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.PurchaseOrder;
import com.nammamedmate.inventory.domain.PurchaseOrderStatus;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
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
class PharmacyReorderServiceCoverageTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID DIST = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
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
  private MedmatePrincipal staff;

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
            false);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void validationBranches() {
    assertThatThrownBy(() -> service.listSuggestions(owner, "bad", 1, 50))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.listSuggestions(null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listSuggestions(customer, null, 0, 999))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.listSuggestions(noPharmacy, null, 1, 50))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.listSuggestions(owner, null, 1, 50))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void createPo_inactiveAndNotFound() {
    when(distributorStore.findById(PHARMACY, DIST)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.createPo(
                    owner, DIST, List.of(Map.of("product_id", PRODUCT, "quantity", 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    Distributor inactive =
        new Distributor(
            DIST,
            PHARMACY,
            "X",
            null,
            "+919876543210",
            null,
            null,
            null,
            null,
            0,
            0L,
            false,
            NOW,
            NOW,
            null);
    when(distributorStore.findById(PHARMACY, DIST)).thenReturn(Optional.of(inactive));
    assertThatThrownBy(
            () ->
                service.createPo(
                    owner, DIST, List.of(Map.of("product_id", PRODUCT.toString(), "quantity", 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_INACTIVE");
  }

  @Test
  void patchPo_notEditableAndNotFound() {
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.patchPo(owner, PO_ID, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_NOT_FOUND");

    PurchaseOrder sent =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
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
    assertThatThrownBy(() -> service.patchPo(owner, PO_ID, List.of(), List.of(), List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_NOT_EDITABLE");
  }

  @Test
  void sendPo_emptyAndEmailUnavailableAndOverride() {
    PurchaseOrder draft =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
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
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(0);
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EMPTY_PO");

    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(1);
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "EMAIL", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CHANNEL_UNAVAILABLE");

    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "SMS", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void recordGrn_errors() {
    PurchaseOrder draft =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
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
    assertThatThrownBy(() -> service.recordGrn(owner, PO_ID, "INV", LocalDate.now()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_NOT_SENT");

    PurchaseOrder sent =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
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
    assertThatThrownBy(() -> service.recordGrn(owner, PO_ID, " ", LocalDate.now()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.recordGrn(owner, PO_ID, "INV", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(grnStore.invoiceExists(PHARMACY, DIST, "INV")).thenReturn(true);
    assertThatThrownBy(() -> service.recordGrn(owner, PO_ID, "INV", LocalDate.now()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DUPLICATE_INVOICE_NUMBER");
  }

  @Test
  void refresh_staffForbidden_andListStatusFilter() {
    assertThatThrownBy(() -> service.refresh(staff))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.listPurchaseOrders(owner, "NOPE", null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(poStore.list(any())).thenReturn(new PurchaseOrderStore.ListResult(List.of(), 0));
    assertThat(service.listPurchaseOrders(owner, "DRAFT", DIST, null, null).meta().total())
        .isEqualTo(0);
  }

  @Test
  void parseHelpers_andProductMissing() {
    when(distributorStore.findById(PHARMACY, DIST))
        .thenReturn(
            Optional.of(
                new Distributor(
                    DIST,
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
                    null)));
    when(poStore.nextSequence(eq(PHARMACY), any())).thenReturn(1);
    when(productStore.findById(PHARMACY, PRODUCT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createPo(
                    owner, DIST, List.of(Map.of("product_id", PRODUCT.toString(), "quantity", 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    assertThatThrownBy(
            () ->
                service.createPo(
                    owner, DIST, List.of(Map.of("product_id", "not-uuid", "quantity", 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.createPo(
                    owner, DIST, List.of(Map.of("product_id", PRODUCT.toString(), "quantity", 0))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.createPo(
                    owner,
                    DIST,
                    List.of(Map.of("product_id", PRODUCT.toString(), "quantity", "x"))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.createPo(owner, DIST, List.of(Map.of("quantity", 1))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void cancelledPoCannotSend() {
    PurchaseOrder cancelled =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
            "PO-2026-08-000001",
            PurchaseOrderStatus.CANCELLED,
            owner.subject(),
            null,
            null,
            null,
            NOW,
            NOW,
            NOW);
    when(poStore.findById(PHARMACY, PO_ID)).thenReturn(Optional.of(cancelled));
    assertThatThrownBy(() -> service.sendPo(owner, PO_ID, "WHATSAPP", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PO_NOT_EDITABLE");
  }

  @Test
  void sendEmail_whenEnabled_usesOverride() {
    PharmacyReorderService emailService =
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
    PurchaseOrder draft =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
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
    when(distributorStore.findById(PHARMACY, DIST))
        .thenReturn(
            Optional.of(
                new Distributor(
                    DIST,
                    PHARMACY,
                    "F",
                    null,
                    "+919876543210",
                    "dist@x.com",
                    null,
                    null,
                    null,
                    0,
                    0L,
                    true,
                    NOW,
                    NOW,
                    null)));
    when(poStore.listItems(PHARMACY, PO_ID)).thenReturn(List.of());
    when(poStore.update(any(), any(), any(), any(), any(), any())).thenReturn(draft);

    Map<String, Object> data = emailService.sendPo(owner, PO_ID, "EMAIL", "override@x.com");
    assertThat(data.get("sent_to")).isEqualTo("override@x.com");
    assertThat(data.get("channel")).isEqualTo("EMAIL");
  }

  @Test
  void patchPo_nullBody_updatesTimestampOnly() {
    PurchaseOrder draft =
        new PurchaseOrder(
            PO_ID,
            PHARMACY,
            DIST,
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
    when(poStore.countItems(PHARMACY, PO_ID)).thenReturn(0);
    when(poStore.estimatedTotalPaise(PHARMACY, PO_ID)).thenReturn(0L);
    when(poStore.update(any(), any(), any(), any(), any(), any())).thenReturn(draft);

    assertThat(service.patchPo(owner, PO_ID, null, null, null).get("items_count")).isEqualTo(0);
  }

  @Test
  void paiseToRupeesHelper() {
    assertThat(PharmacyReorderService.paiseToRupees(1182L)).isEqualByComparingTo("11.82");
  }
}
