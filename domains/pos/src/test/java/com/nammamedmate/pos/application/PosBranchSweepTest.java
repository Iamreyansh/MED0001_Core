package com.nammamedmate.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.domain.DiscountType;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
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
class PosBranchSweepTest {

  static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Mock PosCartStore cartStore;
  @Mock ProductLookupPort productLookup;
  @Mock PosFefoPort fefo;
  @Mock PosCustomerPort customers;
  @Mock PosKhataPort khata;
  @Mock OfferService offerService;
  @Mock PosPlanPort plan;
  @Mock RateLimiter rateLimiter;

  PosCartService service;
  UUID pharmacy = UUID.randomUUID();
  UUID staff = UUID.randomUUID();
  MedmatePrincipal principal =
      new MedmatePrincipal(staff, AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    when(khata.outstandingPaise(any(), any())).thenReturn(0L);
    when(khata.creditLimitPaise(any(), any())).thenReturn(5_000_000L);
    when(plan.growthFeaturesEnabled()).thenReturn(true);
    when(offerService.bestCounterOffer(any(), any(), any(Boolean.class)))
        .thenReturn(Optional.empty());
    service =
        new PosCartService(
            cartStore,
            productLookup,
            fefo,
            customers,
            khata,
            offerService,
            plan,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(cartStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void sweepRemainingBranches() {
    // create with explicit staff id
    Map<String, Object> created = service.createCart(principal, UUID.randomUUID());
    UUID cartId = UUID.fromString(created.get("cart_id").toString());

    PosCart cart =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            null,
            null,
            null,
            null,
            DiscountType.FLAT_RS,
            null, // null discount value → cartView + recalculate branches
            0,
            0,
            0,
            0,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(1000),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.findById(pharmacy, cartId)).thenReturn(Optional.of(cart));

    UUID productId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    UUID otherBatch = UUID.randomUUID();
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "P",
                    "M",
                    "TABLET",
                    0, // packSize 0 → non-loose unit price path when loose=false; loose uses /pack
                    2000L,
                    10,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    null,
                    List.of())));
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN1", LocalDate.of(2027, 1, 1), 50, 2000L)));
    when(cartStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));
    when(cartStore.listItems(cartId)).thenReturn(List.of());

    assertThatThrownBy(() -> service.addItem(principal, cartId, productId, null, null, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    service.addItem(principal, cartId, productId, null, 1, false);

    PosCartItem existing =
        PosCartItem.compute(
            UUID.randomUUID(),
            cartId,
            productId,
            "P",
            batchId,
            "BN1",
            LocalDate.of(2027, 1, 1),
            1,
            false,
            2000L,
            12,
            false,
            10,
            null,
            NOW);
    when(cartStore.findItem(cartId, existing.id())).thenReturn(Optional.of(existing));
    when(cartStore.updateItem(any())).thenAnswer(inv -> inv.getArgument(0));
    when(cartStore.listItems(cartId)).thenReturn(List.of(existing));
    when(fefo.findBatch(pharmacy, productId, otherBatch))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    otherBatch, productId, "BN2", LocalDate.of(2028, 1, 1), 50, 2000L)));
    when(fefo.findBatch(pharmacy, productId, batchId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN1", LocalDate.of(2027, 1, 1), 50, 2000L)));

    // quantity null → keep existing; batchId override; isLoose null
    service.updateItem(principal, cartId, existing.id(), null, otherBatch, null);
    // loose true with packSize>0
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "P",
                    "M",
                    "TABLET",
                    10,
                    2000L,
                    10,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    null,
                    List.of())));
    service.updateItem(principal, cartId, existing.id(), 1, null, true);

    // recalculate discount > max
    PosCart overDiscount =
        new PosCart(
            cartId,
            pharmacy,
            staff,
            null,
            null,
            null,
            null,
            DiscountType.PERCENTAGE,
            BigDecimal.valueOf(90),
            0,
            10_000,
            0,
            10_000,
            PosCartStatus.ACTIVE,
            NOW.plusSeconds(1000),
            null,
            null,
            NOW,
            NOW);
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    productId,
                    "P",
                    batchId,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    10_000L,
                    12,
                    false,
                    1,
                    null,
                    NOW)));
    service.recalculate(overDiscount, NOW);

    // cartView null discount value
    Map<String, Object> view = service.cartView(cart, List.of());
    assertThat((BigDecimal) view.get("discount_value")).isEqualByComparingTo(BigDecimal.ZERO);

    // barcode with 2 batches → toBatchOptions i>0
    when(productLookup.findByBarcode(pharmacy, "BC"))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "P",
                    "M",
                    "TABLET",
                    10,
                    2000L,
                    10,
                    false,
                    false,
                    BigDecimal.valueOf(12),
                    null,
                    List.of())));
    when(fefo.listEligibleBatches(pharmacy, productId))
        .thenReturn(
            List.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN1", LocalDate.of(2027, 1, 1), 5, 2000L),
                new PosFefoPort.BatchSnapshot(
                    otherBatch, productId, "BN2", LocalDate.of(2028, 1, 1), 5, 2000L)));
    Map<String, Object> search = service.search(principal, cartId, "BC", "BARCODE");
    assertThat(search.get("results")).asList().hasSize(1);

    // SearchHit / ProductSnapshot null list compact constructors
    new ProductLookupPort.SearchHit(
        new ProductLookupPort.ProductSnapshot(
            productId, "P", "M", "T", 1, 1, 1, false, false, BigDecimal.ONE, null, null),
        null,
        false);

    service.search(
        principal, cartId, "B2", "TEXT"); // rack-like A? no — B2 matches simplified pattern
    when(productLookup.searchByRack(pharmacy, "B2", 20)).thenReturn(List.of());
    when(productLookup.searchByText(pharmacy, "B2", 20)).thenReturn(List.of());
    service.search(principal, cartId, "B2", "TEXT");

    // loose=true packSize=0 does not divide
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "P",
                    "M",
                    "TABLET",
                    0,
                    2000L,
                    10,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    null,
                    List.of())));
    service.addItem(principal, cartId, productId, null, 1, true);

    assertThatThrownBy(() -> service.search(principal, cartId, null, "TEXT"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.attachCustomer(principal, cartId, null, "A"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.applyDiscount(principal, cartId, "FLAT_RS", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.addItem(principal, cartId, productId, null, 0, false))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.search(principal, cartId, "x", "  "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // add loose with packSize > 0
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "P",
                    "M",
                    "TABLET",
                    10,
                    2000L,
                    10,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    null,
                    List.of())));
    when(fefo.selectFefoBatch(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN1", LocalDate.of(2027, 1, 1), 50, 2000L)));
    service.addItem(principal, cartId, productId, null, 1, true);

    // FLAT_RS exceeding ₹500 cap
    when(cartStore.listItems(cartId))
        .thenReturn(
            List.of(
                PosCartItem.compute(
                    UUID.randomUUID(),
                    cartId,
                    productId,
                    "P",
                    batchId,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    1,
                    false,
                    200_000L,
                    12,
                    false,
                    1,
                    null,
                    NOW)));
    assertThatThrownBy(
            () -> service.applyDiscount(principal, cartId, "FLAT_RS", BigDecimal.valueOf(600)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISCOUNT_EXCEEDS_LIMIT");
    assertThatThrownBy(() -> service.applyDiscount(principal, cartId, null, BigDecimal.TEN))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // update loose with packSize 0
    when(productLookup.findById(pharmacy, productId))
        .thenReturn(
            Optional.of(
                new ProductLookupPort.ProductSnapshot(
                    productId,
                    "P",
                    "M",
                    "TABLET",
                    0,
                    2000L,
                    10,
                    false,
                    true,
                    BigDecimal.valueOf(12),
                    null,
                    List.of())));
    when(fefo.findBatch(pharmacy, productId, batchId))
        .thenReturn(
            Optional.of(
                new PosFefoPort.BatchSnapshot(
                    batchId, productId, "BN1", LocalDate.of(2027, 1, 1), 50, 2000L)));
    when(cartStore.findItem(cartId, existing.id())).thenReturn(Optional.of(existing));
    service.updateItem(principal, cartId, existing.id(), 1, null, true);

    // checkout path with null discount value
    // (covered via PosCheckoutService with cart discountValue null)

    assertThat(MoneyMath.computeDiscountAmountPaise("FLAT_RS", null, 100)).isZero();
    assertThat(MoneyMath.computeDiscountAmountPaise("PERCENTAGE", BigDecimal.TEN, 100))
        .isEqualTo(10L);
  }
}
