package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.DistributorStore.ListResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceCompareResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.SetPreferredResult;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.DistributorFormats;
import com.nammamedmate.inventory.domain.DistributorSupplyItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
class DistributorServiceCoverageTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID DIST = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private static final UUID PRODUCT = UUID.fromString("cccccccc-0001-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private DistributorStore store;
  @Mock private DistributorSupplyItemStore supplyStore;
  @Mock private InventoryPlanPort planPort;
  @Mock private RateLimiter rateLimiter;

  private DistributorService service;
  private MedmatePrincipal owner;
  private MedmatePrincipal customer;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(planPort.growthFeaturesEnabled()).thenReturn(true);
    service =
        new DistributorService(
            store, supplyStore, planPort, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void paginationDefaultsAndAuthEdges() {
    when(store.list(eq(PHARMACY), any(), isNull(), anyInt(), anyInt()))
        .thenReturn(new ListResult(List.of(), 0));
    when(store.kpi(PHARMACY)).thenReturn(new KpiRow(0, 0, 0, 0));
    service.list(owner, null, null, 0, 0);
    service.list(owner, false, null, 2, 200);

    assertThatThrownBy(() -> service.list(null, true, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.list(customer, true, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(noPharmacy, true, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void createAndPatchFieldBranches() {
    when(store.findActiveByPhone(eq(PHARMACY), anyString(), isNull())).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(owner, "Firm", "  ", "+919876543210", "  ", "  ", "  ", "  ", null, null, null);

    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "Firm",
                    null,
                    "+919876543210",
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("-1"),
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "Firm",
                    "x".repeat(101),
                    "+919876543210",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    Distributor cur =
        new Distributor(
            DIST,
            PHARMACY,
            "Firm",
            "C",
            "+919876543210",
            "a@b.co",
            "27AABCM1234A1Z5",
            "DL",
            "Addr",
            10,
            100L,
            true,
            NOW,
            NOW,
            null);
    when(store.findById(PHARMACY, DIST)).thenReturn(Optional.of(cur));
    when(store.findActiveByPhone(PHARMACY, "+919811122233", DIST)).thenReturn(Optional.empty());
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    service.patch(
        owner,
        DIST,
        null,
        "New Contact",
        "+919811122233",
        "new@b.co",
        "27AABCM1234A1Z5",
        "DL2",
        "Addr2",
        15,
        new BigDecimal("10.00"),
        false);

    assertThatThrownBy(
            () ->
                service.patch(
                    owner, DIST, null, null, null, null, null, null, null, -1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.patch(
                    owner,
                    DIST,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("-1"),
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findActiveByPhone(PHARMACY, "+919876543210", DIST))
        .thenReturn(
            Optional.of(Distributor.minimal(UUID.randomUUID(), PHARMACY, "Other", true, NOW)));
    assertThatThrownBy(
            () ->
                service.patch(
                    owner,
                    DIST,
                    null,
                    null,
                    "+919876543210",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_PHONE_EXISTS");
  }

  @Test
  void supplyListNotFoundAndPreferredNullPrevious() {
    when(store.findById(PHARMACY, DIST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.supplyList(owner, DIST, null, 0, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    when(store.findById(PHARMACY, DIST))
        .thenReturn(Optional.of(Distributor.minimal(DIST, PHARMACY, "A", true, NOW)));
    when(supplyStore.setPreferred(PHARMACY, DIST, PRODUCT, NOW))
        .thenReturn(Optional.of(new SetPreferredResult(null)));
    Map<String, Object> data = service.setPreferred(owner, DIST, PRODUCT);
    assertThat(data.get("previous_preferred_distributor_id")).isNull();

    when(supplyStore.priceCompare(PHARMACY, false, null, 1, 20))
        .thenReturn(new PriceCompareResult(List.of(), 0));
    service.priceCompare(owner, null, null, null, null);
  }

  @Test
  void lastPurchaseDateNullInListItem() {
    Distributor d = Distributor.minimal(DIST, PHARMACY, "Medico", true, NOW);
    when(store.list(PHARMACY, true, null, 1, 20)).thenReturn(new ListResult(List.of(d), 1));
    when(store.kpi(PHARMACY)).thenReturn(new KpiRow(1, 0, 0, 0));
    when(store.outstandingPayablePaise(PHARMACY, DIST)).thenReturn(0L);
    when(store.lastPurchaseDate(PHARMACY, DIST)).thenReturn(null);
    DistributorService.ListPage page = service.list(owner, true, null, 1, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = ((List<Map<String, Object>>) page.data().get("distributors")).get(0);
    assertThat(row.get("last_purchase_date")).isNull();
  }

  @Test
  void remainingBranchGaps() {
    when(store.list(eq(PHARMACY), any(), isNull(), anyInt(), anyInt()))
        .thenReturn(new ListResult(List.of(), 0));
    when(store.kpi(PHARMACY)).thenReturn(new KpiRow(0, 0, 0, 0));
    service.list(owner, true, null, null, null);

    when(store.findById(PHARMACY, DIST)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () ->
                service.patch(
                    owner, DIST, null, null, null, null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    when(store.findById(PHARMACY, DIST))
        .thenReturn(Optional.of(Distributor.minimal(DIST, PHARMACY, "A", true, NOW)));
    when(supplyStore.listByDistributor(eq(PHARMACY), eq(DIST), isNull(), anyInt(), anyInt()))
        .thenReturn(new DistributorSupplyItemStore.ListResult(List.of(), 0));
    service.supplyList(owner, DIST, null, null, null);
    service.supplyList(owner, DIST, null, 0, 5);
    service.supplyList(owner, DIST, null, 3, 5);

    when(supplyStore.priceCompare(eq(PHARMACY), anyBoolean(), isNull(), anyInt(), anyInt()))
        .thenReturn(new PriceCompareResult(List.of(), 0));
    service.priceCompare(owner, null, null, null, null);
    service.priceCompare(owner, false, null, 0, 5);
    service.priceCompare(owner, true, null, 2, 5);

    when(store.findActiveByPhone(eq(PHARMACY), anyString(), isNull())).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    service.create(owner, "Firm", null, "+919876543210", null, null, null, null, null, null, false);

    assertThatThrownBy(
            () ->
                service.create(
                    owner, null, null, "+919876543210", null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "x".repeat(201),
                    null,
                    "+919876543210",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.create(owner, "Firm", null, null, null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PHONE");

    assertThat(DistributorFormats.effectiveLandedCostPaise(100, "   "))
        .isEqualByComparingTo("1.00");

    assertThat(DistributorFormats.isValidGstin(null)).isFalse();
    assertThat(DistributorFormats.isValidPhone(null)).isFalse();
    assertThat(DistributorFormats.isValidEmail(null)).isFalse();
    assertThat(DistributorFormats.effectiveLandedCostPaise(100, "nope"))
        .isEqualByComparingTo("1.00");
    assertThat(DistributorFormats.effectiveLandedCostPaise(100, "0 free on 0"))
        .isEqualByComparingTo("1.00");
    assertThat(DistributorFormats.marginPct(null, BigDecimal.ONE)).isEqualByComparingTo("0.0");
    assertThat(DistributorFormats.marginPct(new BigDecimal("10"), null))
        .isEqualByComparingTo("0.0");

    DistributorSupplyItem item =
        new DistributorSupplyItem(
            UUID.randomUUID(), DIST, PRODUCT, PHARMACY, 100, null, false, null, NOW);
    assertThat(item.purchasePricePaise()).isEqualTo(100);

    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "Firm",
                    null,
                    "+919876543210",
                    "a@" + "b".repeat(260) + ".com",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
