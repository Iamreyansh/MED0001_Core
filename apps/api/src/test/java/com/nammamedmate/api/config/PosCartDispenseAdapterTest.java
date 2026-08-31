package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pos.application.PosCartService;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort.ProductSnapshot;
import com.nammamedmate.pos.application.port.out.ProductLookupPort.SearchHit;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PosCartDispenseAdapterTest {

  private PosCartService carts;
  private ProductLookupPort products;
  private PosCartDispenseAdapter adapter;
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID staff = UUID.randomUUID();
  private final UUID cartId = UUID.randomUUID();
  private final UUID productId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    carts = mock(PosCartService.class);
    products = mock(ProductLookupPort.class);
    adapter = new PosCartDispenseAdapter(carts, products);
    when(carts.createCart(any(), eq(staff))).thenReturn(Map.of("cart_id", cartId.toString()));
  }

  @Test
  void availableAndEmptyMedicinesCreateCart() {
    assertThat(adapter.available()).isTrue();
    assertThat(adapter.pushToBillingCart(pharmacy, staff, List.of())).isEqualTo(cartId);
    assertThat(adapter.pushToBillingCart(pharmacy, staff, null)).isEqualTo(cartId);
    verify(carts, never()).addItem(any(), any(), any(), any(), anyInt(), anyBoolean());
  }

  @Test
  void addsExactNameMatchAndSkipsBlankUnmatchedAndFailedLines() {
    UUID other = UUID.randomUUID();
    ProductSnapshot exact =
        new ProductSnapshot(
            productId,
            "Metformin",
            "Cipla",
            "TAB",
            10,
            1000,
            5,
            true,
            false,
            BigDecimal.ZERO,
            "3004",
            List.of());
    ProductSnapshot fuzzy =
        new ProductSnapshot(
            other,
            "Other",
            "Cipla",
            "TAB",
            10,
            1000,
            5,
            false,
            false,
            BigDecimal.ZERO,
            "3004",
            List.of());
    when(products.searchByText(pharmacy, "Metformin", 5))
        .thenReturn(
            List.of(
                new SearchHit(fuzzy, List.of(), false), new SearchHit(exact, List.of(), false)));
    when(products.searchByText(pharmacy, "Unknown", 5)).thenReturn(List.of());
    when(products.searchByText(pharmacy, "Stocked", 5))
        .thenReturn(List.of(new SearchHit(exact, List.of(), false)));
    doThrow(new AppException("PRODUCT_EXPIRED", "Batch not available", 400))
        .when(carts)
        .addItem(any(), eq(cartId), eq(productId), isNull(), eq(2), eq(false));

    List<ApprovedMedicine> medicines = new ArrayList<>();
    medicines.add(null);
    medicines.add(new ApprovedMedicine("  ", 1, BigDecimal.ONE, null));
    medicines.add(new ApprovedMedicine("Unknown", 1, BigDecimal.ONE, null));
    medicines.add(new ApprovedMedicine("Metformin", 3, new BigDecimal("10.50"), "H1"));
    medicines.add(new ApprovedMedicine("Stocked", 2, BigDecimal.TEN, null));
    UUID sale = adapter.createSaleRecord(pharmacy, staff, null, medicines);
    assertThat(sale).isEqualTo(cartId);
    verify(carts).addItem(any(), eq(cartId), eq(productId), isNull(), eq(3), eq(false));
  }

  @Test
  void rejectsMissingIdsAndInvalidCartPayload() {
    assertThatThrownBy(() -> adapter.pushToBillingCart(null, staff, List.of()))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(carts.createCart(any(), eq(staff))).thenReturn(Map.of());
    assertThatThrownBy(() -> adapter.pushToBillingCart(pharmacy, staff, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INTERNAL_ERROR");
    when(carts.createCart(any(), eq(staff))).thenReturn(Map.of("cart_id", "not-a-uuid"));
    assertThatThrownBy(() -> adapter.pushToBillingCart(pharmacy, staff, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INTERNAL_ERROR");
    when(carts.createCart(any(), eq(staff))).thenReturn(Map.of("cart_id", cartId));
    assertThat(adapter.pushToBillingCart(pharmacy, staff, List.of())).isEqualTo(cartId);
  }

  @Test
  void usesFirstHitWhenNoExactName() {
    ProductSnapshot snap =
        new ProductSnapshot(
            productId,
            "Metformin SR",
            "Cipla",
            "TAB",
            10,
            1000,
            5,
            true,
            false,
            BigDecimal.ZERO,
            "3004",
            List.of());
    when(products.searchByText(pharmacy, "Metformin", 5))
        .thenReturn(
            List.of(new SearchHit(null, List.of(), false), new SearchHit(snap, List.of(), false)));
    assertThat(
            adapter.pushToBillingCart(
                pharmacy,
                staff,
                List.of(new ApprovedMedicine("Metformin", 0, BigDecimal.ONE, null))))
        .isEqualTo(cartId);
    verify(carts).addItem(any(), eq(cartId), eq(productId), isNull(), eq(1), eq(false));
    when(products.searchByText(pharmacy, "Ghost", 5))
        .thenReturn(List.of(new SearchHit(null, List.of(), false)));
    assertThat(
            adapter.pushToBillingCart(
                pharmacy, staff, List.of(new ApprovedMedicine("Ghost", 1, BigDecimal.ONE, null))))
        .isEqualTo(cartId);
  }

  @Test
  void coversRemainingCartAndResolveGuards() {
    assertThatThrownBy(() -> adapter.pushToBillingCart(pharmacy, null, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    when(carts.createCart(any(), eq(staff))).thenReturn(null);
    assertThatThrownBy(() -> adapter.pushToBillingCart(pharmacy, staff, List.of()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INTERNAL_ERROR");
    when(carts.createCart(any(), eq(staff))).thenReturn(Map.of("cart_id", cartId.toString()));
    when(products.searchByText(pharmacy, "NullHits", 5)).thenReturn(null);
    ProductSnapshot noId =
        new ProductSnapshot(
            null,
            "Named",
            "Cipla",
            "TAB",
            10,
            1000,
            5,
            true,
            false,
            BigDecimal.ZERO,
            "3004",
            List.of());
    ProductSnapshot unnamed =
        new ProductSnapshot(
            productId,
            null,
            "Cipla",
            "TAB",
            10,
            1000,
            5,
            true,
            false,
            BigDecimal.ZERO,
            "3004",
            List.of());
    List<SearchHit> namedHits = new ArrayList<>();
    namedHits.add(null);
    namedHits.add(new SearchHit(noId, List.of(), false));
    namedHits.add(new SearchHit(unnamed, List.of(), false));
    when(products.searchByText(pharmacy, "Named", 5)).thenReturn(namedHits);
    List<ApprovedMedicine> medicines = new ArrayList<>();
    medicines.add(new ApprovedMedicine(null, 1, BigDecimal.ONE, null));
    medicines.add(new ApprovedMedicine("NullHits", 1, BigDecimal.ONE, null));
    medicines.add(new ApprovedMedicine("Named", 1, BigDecimal.ONE, null));
    assertThat(adapter.pushToBillingCart(pharmacy, staff, medicines)).isEqualTo(cartId);
    verify(carts).addItem(any(), eq(cartId), eq(productId), isNull(), eq(1), eq(false));
  }
}
