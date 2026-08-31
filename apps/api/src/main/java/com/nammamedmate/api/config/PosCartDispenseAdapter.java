package com.nammamedmate.api.config;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.pos.application.PosCartService;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort.SearchHit;
import com.nammamedmate.prescription.application.port.out.PosDispensePort;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wires Rx dispense into a real POS cart. Unmatched catalogue names are skipped so the pharmacist
 * still gets an open cart.
 */
public final class PosCartDispenseAdapter implements PosDispensePort {

  private final PosCartService carts;
  private final ProductLookupPort products;

  public PosCartDispenseAdapter(PosCartService carts, ProductLookupPort products) {
    this.carts = carts;
    this.products = products;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public UUID pushToBillingCart(UUID pharmacyId, UUID staffId, List<ApprovedMedicine> medicines) {
    UUID cartId = createEmptyCart(pharmacyId, staffId);
    if (medicines == null || medicines.isEmpty()) {
      return cartId;
    }
    MedmatePrincipal principal = principal(pharmacyId, staffId);
    for (ApprovedMedicine medicine : medicines) {
      if (medicine == null || medicine.name() == null || medicine.name().isBlank()) {
        continue;
      }
      UUID productId = resolveProduct(pharmacyId, medicine.name().trim());
      if (productId == null) {
        continue;
      }
      int quantity = Math.max(1, medicine.quantity());
      try {
        carts.addItem(principal, cartId, productId, null, quantity, false);
      } catch (AppException ignored) {
        // Stock/batch/product failures leave the cart open for manual add.
      }
    }
    return cartId;
  }

  @Override
  public UUID createSaleRecord(
      UUID pharmacyId, UUID staffId, UUID orderId, List<ApprovedMedicine> medicines) {
    return pushToBillingCart(pharmacyId, staffId, medicines);
  }

  private UUID createEmptyCart(UUID pharmacyId, UUID staffId) {
    if (pharmacyId == null || staffId == null) {
      throw new AppException("VALIDATION_ERROR", "pharmacy and staff are required", 400);
    }
    Map<String, Object> created = carts.createCart(principal(pharmacyId, staffId), staffId);
    Object raw = created == null ? null : created.get("cart_id");
    if (raw == null) {
      throw new AppException("INTERNAL_ERROR", "POS cart did not return cart_id", 500);
    }
    try {
      return raw instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(raw));
    } catch (IllegalArgumentException ex) {
      throw new AppException("INTERNAL_ERROR", "POS cart returned an invalid cart_id", 500);
    }
  }

  private UUID resolveProduct(UUID pharmacyId, String name) {
    List<SearchHit> hits = products.searchByText(pharmacyId, name, 5);
    if (hits == null || hits.isEmpty()) {
      return null;
    }
    String needle = name.toLowerCase(Locale.ROOT);
    UUID fallback = null;
    for (SearchHit hit : hits) {
      if (hit == null || hit.product() == null || hit.product().productId() == null) {
        continue;
      }
      if (fallback == null) {
        fallback = hit.product().productId();
      }
      if (hit.product().name() != null
          && needle.equals(hit.product().name().trim().toLowerCase(Locale.ROOT))) {
        return hit.product().productId();
      }
    }
    return fallback;
  }

  private static MedmatePrincipal principal(UUID pharmacyId, UUID staffId) {
    return new MedmatePrincipal(
        staffId, AuthRole.PHARMACY_STAFF, pharmacyId, TokenScope.FULL, "rx-dispense");
  }
}
