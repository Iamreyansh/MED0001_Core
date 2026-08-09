package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.DistributorStore.ListResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceCompareResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceOffer;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceProduct;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.SetPreferredResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.SupplyRow;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.domain.Distributor;
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
class DistributorServiceTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000001");
  private static final UUID DIST_A = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000001");
  private static final UUID DIST_B = UUID.fromString("bbbbbbbb-0002-4000-8000-000000000002");
  private static final UUID PRODUCT = UUID.fromString("cccccccc-0001-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private DistributorStore store;
  @Mock private DistributorSupplyItemStore supplyStore;
  @Mock private InventoryPlanPort planPort;
  @Mock private RateLimiter rateLimiter;

  private DistributorService service;
  private MedmatePrincipal owner;
  private MedmatePrincipal staff;

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
    staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void freePlan_returnsPlanFeatureLocked() {
    when(planPort.growthFeaturesEnabled()).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, true, null, 1, 20))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_FEATURE_LOCKED");
  }

  @Test
  void create_rejectsInvalidGstinAndPhone_andDuplicatePhone() {
    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "Firm",
                    null,
                    "+919876543210",
                    null,
                    "INVALIDGSTIN",
                    null,
                    null,
                    30,
                    new BigDecimal("1000"),
                    true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_GSTIN_FORMAT");

    assertThatThrownBy(
            () ->
                service.create(
                    owner, "Firm", null, "bad", null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_PHONE");

    when(store.findActiveByPhone(PHARMACY, "+919876543210", null))
        .thenReturn(Optional.of(Distributor.minimal(DIST_A, PHARMACY, "X", true, NOW)));
    assertThatThrownBy(
            () ->
                service.create(
                    owner, "Firm", null, "+919876543210", null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_PHONE_EXISTS");
  }

  @Test
  void createPatchListDeactivateHappyPath() {
    when(store.findActiveByPhone(eq(PHARMACY), anyString(), isNull())).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> created =
        service.create(
            owner,
            "Medico Pharma Distributors",
            "Ramesh Kumar",
            "+919876543210",
            "ramesh@medicopharma.in",
            "27AABCM1234A1Z5",
            "DL-MH-2024-00123",
            "Mumbai",
            30,
            new BigDecimal("100000.00"),
            true);
    assertThat(created.get("firm_name")).isEqualTo("Medico Pharma Distributors");
    assertThat(created.get("payment_terms_days")).isEqualTo(30);

    Distributor existing =
        new Distributor(
            DIST_A,
            PHARMACY,
            "Medico Pharma Distributors",
            "Ramesh",
            "+919876543210",
            null,
            "27AABCM1234A1Z5",
            null,
            null,
            30,
            10000000L,
            true,
            NOW,
            NOW,
            null);
    when(store.findById(PHARMACY, DIST_A)).thenReturn(Optional.of(existing));
    when(store.findActiveByPhone(PHARMACY, "+919876543210", DIST_A)).thenReturn(Optional.empty());
    when(store.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> patched =
        service.patch(
            owner, DIST_A, "Medico Updated", null, null, null, null, null, null, null, null, null);
    assertThat(patched.get("firm_name")).isEqualTo("Medico Updated");

    when(store.list(PHARMACY, true, "medico", 1, 20))
        .thenReturn(new ListResult(List.of(existing), 1));
    when(store.kpi(PHARMACY)).thenReturn(new KpiRow(1, 2, 2850000L, 1));
    when(store.outstandingPayablePaise(PHARMACY, DIST_A)).thenReturn(2850000L);
    when(store.lastPurchaseDate(PHARMACY, DIST_A)).thenReturn(LocalDate.of(2026, 7, 22));

    DistributorService.ListPage page = service.list(owner, null, "medico", 1, 20);
    assertThat(page.data().get("distributors")).asList().hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = ((List<Map<String, Object>>) page.data().get("distributors")).get(0);
    assertThat(row.get("firm_name").toString().toLowerCase()).contains("medico");
    assertThat(row.get("outstanding_payable")).isEqualTo(new BigDecimal("28500.00"));

    Map<String, Object> deactivated = service.deactivate(owner, DIST_A);
    assertThat(deactivated.get("is_active")).isEqualTo(false);
    verify(store).deactivate(PHARMACY, DIST_A, NOW);
  }

  @Test
  void deactivate_preservesIdentity_grnStillFindableViaStore() {
    Distributor existing = Distributor.minimal(DIST_A, PHARMACY, "Firm", true, NOW);
    when(store.findById(PHARMACY, DIST_A)).thenReturn(Optional.of(existing));
    Map<String, Object> data = service.deactivate(owner, DIST_A);
    assertThat(data.get("id")).isEqualTo(DIST_A.toString());
    // soft deactivate only flips is_active — GRN FKs remain intact
    verify(store, never()).update(any());
  }

  @Test
  void setPreferred_clearsOtherDistributor() {
    when(store.findById(PHARMACY, DIST_A))
        .thenReturn(Optional.of(Distributor.minimal(DIST_A, PHARMACY, "A", true, NOW)));
    when(supplyStore.setPreferred(PHARMACY, DIST_A, PRODUCT, NOW))
        .thenReturn(Optional.of(new SetPreferredResult(DIST_B)));

    Map<String, Object> data = service.setPreferred(owner, DIST_A, PRODUCT);
    assertThat(data.get("is_preferred_source")).isEqualTo(true);
    assertThat(data.get("previous_preferred_distributor_id")).isEqualTo(DIST_B.toString());
  }

  @Test
  void priceCompare_onlyMultiSource() {
    PriceProduct product =
        new PriceProduct(
            PRODUCT,
            "Paracetamol 500mg Tab",
            "Cipla Ltd",
            List.of(
                new PriceOffer(DIST_A, "Medico", 1300, "1 free on 10", 2250, true, 1),
                new PriceOffer(DIST_B, "Apollo", 1450, null, 2250, false, 2)));
    when(supplyStore.priceCompare(PHARMACY, true, null, 1, 20))
        .thenReturn(new PriceCompareResult(List.of(product), 1));

    DistributorService.ListPage page = service.priceCompare(owner, true, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> products = (List<Map<String, Object>>) page.data().get("products");
    assertThat(products).hasSize(1);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> offers =
        (List<Map<String, Object>>) products.get(0).get("distributor_prices");
    assertThat(offers).hasSize(2);
    assertThat(offers.get(0).get("effective_landed_cost")).isEqualTo(new BigDecimal("11.82"));
  }

  @Test
  void supplyList_computesEffectiveLandedCost() {
    when(store.findById(PHARMACY, DIST_A))
        .thenReturn(Optional.of(Distributor.minimal(DIST_A, PHARMACY, "Medico", true, NOW)));
    when(supplyStore.listByDistributor(PHARMACY, DIST_A, null, 1, 20))
        .thenReturn(
            new DistributorSupplyItemStore.ListResult(
                List.of(
                    new SupplyRow(
                        PRODUCT,
                        "Paracetamol 500mg Tab",
                        "Cipla Ltd",
                        1300,
                        "1 free on 10",
                        2250,
                        true,
                        1)),
                1));

    DistributorService.ListPage page = service.supplyList(staff, DIST_A, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) page.data().get("supply_items");
    assertThat(items.get(0).get("effective_landed_cost")).isEqualTo(new BigDecimal("11.82"));
  }

  @Test
  void staffCannotCreate_ownerRequired_andNotFound() {
    assertThatThrownBy(
            () ->
                service.create(
                    staff, "Firm", null, "+919876543210", null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    when(store.findById(PHARMACY, DIST_A)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deactivate(owner, DIST_A))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");
  }

  @Test
  void rateLimitedAndValidationBranches() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, true, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.create(
                    owner, "", null, "+919876543210", null, null, null, null, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    owner,
                    "Firm",
                    null,
                    "+919876543210",
                    "bad-email",
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
                service.create(
                    owner, "Firm", null, "+919876543210", null, null, null, null, -1, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findById(PHARMACY, DIST_A)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.setPreferred(owner, DIST_A, PRODUCT))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    when(store.findById(PHARMACY, DIST_A))
        .thenReturn(Optional.of(Distributor.minimal(DIST_A, PHARMACY, "A", true, NOW)));
    when(supplyStore.setPreferred(PHARMACY, DIST_A, PRODUCT, NOW)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.setPreferred(owner, DIST_A, PRODUCT))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SUPPLY_ITEM_NOT_FOUND");

    assertThat(DistributorService.paiseToRupees(100)).isEqualByComparingTo("1.00");
    assertThat(DistributorService.rupeesToPaise(new BigDecimal("1.50"), "x")).isEqualTo(150L);
    assertThatThrownBy(() -> DistributorService.rupeesToPaise(null, "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> DistributorService.rupeesToPaise(new BigDecimal("1.234"), "x"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
