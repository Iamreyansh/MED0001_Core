package com.nammamedmate.pos.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pos.application.port.out.PosCartStore;
import com.nammamedmate.pos.application.port.out.PosCustomerPort;
import com.nammamedmate.pos.application.port.out.PosFefoPort;
import com.nammamedmate.pos.application.port.out.PosKhataPort;
import com.nammamedmate.pos.application.port.out.PosPlanPort;
import com.nammamedmate.pos.application.port.out.ProductLookupPort;
import com.nammamedmate.pos.domain.MoneyMath;
import com.nammamedmate.pos.domain.PosCart;
import com.nammamedmate.pos.domain.PosCartItem;
import com.nammamedmate.pos.domain.PosCartStatus;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCartService {

  static final Duration CART_TTL = Duration.ofHours(2);
  private static final int WINDOW = 60;

  private final PosCartStore cartStore;
  private final ProductLookupPort productLookup;
  private final PosFefoPort fefo;
  private final PosCustomerPort customers;
  private final PosKhataPort khata;
  private final OfferService offerService;
  private final PosPlanPort plan;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public PosCartService(
      PosCartStore cartStore,
      ProductLookupPort productLookup,
      PosFefoPort fefo,
      PosCustomerPort customers,
      PosKhataPort khata,
      OfferService offerService,
      PosPlanPort plan,
      RateLimiter rateLimiter,
      Clock clock) {
    this.cartStore = cartStore;
    this.productLookup = productLookup;
    this.fefo = fefo;
    this.customers = customers;
    this.khata = khata;
    this.offerService = offerService;
    this.plan = plan;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> createCart(MedmatePrincipal principal, UUID createdByStaffId) {
    requireStaff(principal);
    rateLimit("pos:cart:create:" + principal.pharmacyId(), 60);
    Instant now = clock.instant();
    UUID staffId = createdByStaffId != null ? createdByStaffId : principal.subject();
    PosCart cart =
        new PosCart(
            Ids.newId(),
            principal.pharmacyId(),
            staffId,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            0L,
            0L,
            0L,
            0L,
            PosCartStatus.ACTIVE,
            now.plus(CART_TTL),
            null,
            null,
            now,
            now);
    cartStore.insert(cart);
    return cartView(cart, List.of());
  }

  @Transactional
  public Map<String, Object> getCart(MedmatePrincipal principal, UUID cartId) {
    requireStaff(principal);
    rateLimit("pos:cart:get:" + principal.pharmacyId(), 120);
    PosCart cart = loadActiveOrExpired(principal.pharmacyId(), cartId);
    Instant now = clock.instant();
    recalculate(cart, now);
    cart =
        cartStore
            .findById(principal.pharmacyId(), cartId)
            .orElseThrow(() -> new AppException("CART_NOT_FOUND", "Cart not found", 404));
    return cartView(cart, cartStore.listItems(cartId));
  }

  @Transactional
  public Map<String, Object> addItem(
      MedmatePrincipal principal,
      UUID cartId,
      UUID productId,
      UUID batchId,
      Integer quantity,
      Boolean isLoose) {
    requireStaff(principal);
    rateLimit("pos:cart:add:" + principal.pharmacyId(), 120);
    if (productId == null) {
      throw new AppException("VALIDATION_ERROR", "product_id is required", 400);
    }
    if (quantity == null) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    if (quantity <= 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    boolean loose = Boolean.TRUE.equals(isLoose);
    PosCart cart = requireMutableCart(principal.pharmacyId(), cartId);
    var product =
        productLookup
            .findById(principal.pharmacyId(), productId)
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));
    if (loose && !product.isLooseSellingEnabled()) {
      throw new AppException("VALIDATION_ERROR", "Loose selling not enabled for product", 400);
    }

    PosFefoPort.BatchSnapshot batch;
    if (batchId != null) {
      batch =
          fefo.findBatch(principal.pharmacyId(), productId, batchId)
              .orElseThrow(() -> new AppException("PRODUCT_EXPIRED", "Batch not available", 400));
    } else {
      batch =
          fefo.selectFefoBatch(principal.pharmacyId(), productId)
              .orElseThrow(
                  () -> new AppException("PRODUCT_EXPIRED", "All batches are expired", 400));
    }
    if (batch.quantityCurrent() < quantity) {
      throw new AppException("INSUFFICIENT_STOCK", "Requested quantity exceeds batch stock", 400);
    }

    long unitPrice = batch.mrpPaise();
    if (loose && product.packSize() > 0) {
      unitPrice = batch.mrpPaise() / product.packSize();
    }
    Instant now = clock.instant();
    PosCartItem item =
        PosCartItem.compute(
            Ids.newId(),
            cartId,
            product.productId(),
            product.name(),
            batch.batchId(),
            batch.batchNumber(),
            batch.expiryDate(),
            quantity,
            loose,
            unitPrice,
            product.gstPct().intValue(),
            product.isRxOnly(),
            product.packSize(),
            product.hsnCode(),
            now);
    cartStore.insertItem(item);
    Totals totals = recalculate(cart, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("item_id", item.id().toString());
    data.put("product_name", item.productName());
    data.put("batch_number", item.batchNumber());
    data.put("expiry_date", item.expiryDate().toString());
    data.put("quantity", item.quantity());
    data.put("is_loose", item.isLoose());
    data.put("unit_price", MoneyMath.paiseToRupees(item.unitPricePaise()));
    data.put("gst_pct", item.gstPct());
    data.put("line_total", MoneyMath.paiseToRupees(item.lineTotalPaise()));
    data.put("gst_amount", MoneyMath.paiseToRupees(item.gstAmountPaise()));
    data.put("cart_grand_total", MoneyMath.paiseToRupees(totals.grandTotalPaise()));
    return data;
  }

  @Transactional
  public Map<String, Object> updateItem(
      MedmatePrincipal principal,
      UUID cartId,
      UUID itemId,
      Integer quantity,
      UUID batchId,
      Boolean isLoose) {
    requireStaff(principal);
    rateLimit("pos:cart:patch:" + principal.pharmacyId(), 120);
    PosCart cart = requireMutableCart(principal.pharmacyId(), cartId);
    PosCartItem existing =
        cartStore
            .findItem(cartId, itemId)
            .orElseThrow(() -> new AppException("VALIDATION_ERROR", "Cart item not found", 404));

    int qty = quantity != null ? quantity : existing.quantity();
    if (qty <= 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    boolean loose = isLoose != null ? isLoose : existing.isLoose();
    UUID productId = existing.productId();
    var product =
        productLookup
            .findById(principal.pharmacyId(), productId)
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));

    PosFefoPort.BatchSnapshot batch;
    UUID useBatchId = batchId != null ? batchId : existing.batchId();
    batch =
        fefo.findBatch(principal.pharmacyId(), productId, useBatchId)
            .orElseThrow(() -> new AppException("PRODUCT_EXPIRED", "Batch not available", 400));
    if (batch.quantityCurrent() < qty) {
      throw new AppException("INSUFFICIENT_STOCK", "Requested quantity exceeds batch stock", 400);
    }

    long unitPrice = batch.mrpPaise();
    if (loose && product.packSize() > 0) {
      unitPrice = batch.mrpPaise() / product.packSize();
    }
    Instant now = clock.instant();
    PosCartItem updated =
        PosCartItem.compute(
            existing.id(),
            cartId,
            productId,
            product.name(),
            batch.batchId(),
            batch.batchNumber(),
            batch.expiryDate(),
            qty,
            loose,
            unitPrice,
            product.gstPct().intValue(),
            product.isRxOnly(),
            product.packSize(),
            product.hsnCode(),
            existing.createdAt());
    cartStore.updateItem(updated);
    Totals totals = recalculate(cart, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("item_id", updated.id().toString());
    data.put("quantity", updated.quantity());
    data.put("line_total", MoneyMath.paiseToRupees(updated.lineTotalPaise()));
    data.put("cart_grand_total", MoneyMath.paiseToRupees(totals.grandTotalPaise()));
    return data;
  }

  @Transactional
  public Map<String, Object> removeItem(MedmatePrincipal principal, UUID cartId, UUID itemId) {
    requireStaff(principal);
    rateLimit("pos:cart:del-item:" + principal.pharmacyId(), 60);
    PosCart cart = requireMutableCart(principal.pharmacyId(), cartId);
    cartStore
        .findItem(cartId, itemId)
        .orElseThrow(() -> new AppException("VALIDATION_ERROR", "Cart item not found", 404));
    cartStore.deleteItem(cartId, itemId);
    Instant now = clock.instant();
    Totals totals = recalculate(cart, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("item_id", itemId.toString());
    data.put("cart_grand_total", MoneyMath.paiseToRupees(totals.grandTotalPaise()));
    return data;
  }

  @Transactional
  public Map<String, Object> clearCart(MedmatePrincipal principal, UUID cartId) {
    requireStaff(principal);
    rateLimit("pos:cart:clear:" + principal.pharmacyId(), 30);
    PosCart cart = requireMutableCart(principal.pharmacyId(), cartId);
    int removed = cartStore.deleteAllItems(cartId);
    Instant now = clock.instant();
    recalculate(cart, now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cart_id", cartId.toString());
    data.put("items_removed", removed);
    data.put("status", PosCartStatus.ACTIVE.name());
    return data;
  }

  @Transactional
  public Map<String, Object> search(
      MedmatePrincipal principal, UUID cartId, String query, String mode) {
    requireStaff(principal);
    rateLimit("pos:cart:search:" + principal.pharmacyId(), 120);
    requireMutableCart(principal.pharmacyId(), cartId);
    if (query == null || query.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "query is required", 400);
    }
    if (mode == null) {
      throw new AppException("VALIDATION_ERROR", "mode is required", 400);
    }
    if (mode.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "mode is required", 400);
    }
    String m = mode.trim().toUpperCase(Locale.ROOT);
    List<ProductLookupPort.SearchHit> hits;
    if ("BARCODE".equals(m)) {
      hits =
          productLookup
              .findByBarcode(principal.pharmacyId(), query.trim())
              .map(
                  p -> {
                    List<PosFefoPort.BatchSnapshot> batches =
                        fefo.listEligibleBatches(principal.pharmacyId(), p.productId());
                    List<ProductLookupPort.BatchOption> opts = toBatchOptions(batches);
                    return new ProductLookupPort.SearchHit(p, opts, true);
                  })
              .map(List::of)
              .orElse(List.of());
    } else if ("TEXT".equals(m)) {
      String q = query.trim();
      // Rack codes often look like A1-03
      if (looksLikeRack(q)) {
        hits = productLookup.searchByRack(principal.pharmacyId(), q, 20);
        if (hits.isEmpty()) {
          hits = productLookup.searchByText(principal.pharmacyId(), q, 20);
        }
      } else {
        hits = productLookup.searchByText(principal.pharmacyId(), q, 20);
      }
    } else {
      throw new AppException("VALIDATION_ERROR", "mode must be BARCODE or TEXT", 400);
    }

    List<Map<String, Object>> results = new ArrayList<>();
    for (ProductLookupPort.SearchHit hit : hits) {
      results.add(searchHitView(hit));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("results", results);
    data.put("mode", m);
    data.put("query", query);
    return data;
  }

  @Transactional
  public Map<String, Object> attachCustomer(
      MedmatePrincipal principal, UUID cartId, String phone, String name) {
    requireStaff(principal);
    rateLimit("pos:cart:customer:" + principal.pharmacyId(), 60);
    requireMutableCart(principal.pharmacyId(), cartId);
    if (phone == null || phone.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "customer_phone is required", 400);
    }
    PosCustomerPort.CustomerRef ref = customers.findOrCreate(phone.trim(), name);
    Instant now = clock.instant();
    cartStore.attachCustomer(
        cartId, ref.customerId(), ref.name(), ref.phone(), now, now.plus(CART_TTL));
    khata.ensureCustomerKnown(principal.pharmacyId(), ref.customerId());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("customer_id", ref.customerId().toString());
    data.put("name", ref.name());
    data.put("phone", ref.phone());
    data.put("is_new_customer", ref.isNew());
    data.put(
        "outstanding_khata",
        MoneyMath.paiseToRupees(khata.outstandingPaise(principal.pharmacyId(), ref.customerId())));
    return data;
  }

  @Transactional
  public Map<String, Object> applyDiscount(
      MedmatePrincipal principal, UUID cartId, String type, BigDecimal value) {
    requireStaff(principal);
    rateLimit("pos:cart:discount:" + principal.pharmacyId(), 30);
    requireMutableCart(principal.pharmacyId(), cartId);
    List<PosCartItem> items = cartStore.listItems(cartId);
    if (items.isEmpty()) {
      throw new AppException("EMPTY_CART", "Cart has no items", 400);
    }
    if (type == null) {
      throw new AppException("VALIDATION_ERROR", "type and value > 0 required", 400);
    }
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "type and value > 0 required", 400);
    }
    String t = type.trim().toUpperCase(Locale.ROOT);
    if (!"FLAT_RS".equals(t) && !"PERCENTAGE".equals(t)) {
      throw new AppException("VALIDATION_ERROR", "Invalid discount type", 400);
    }
    long subtotal = items.stream().mapToLong(PosCartItem::lineTotalPaise).sum();
    long amount = MoneyMath.computeDiscountAmountPaise(t, value, subtotal);
    long max = MoneyMath.maxDiscountPaise(subtotal);
    if ("PERCENTAGE".equals(t) && value.compareTo(MoneyMath.MAX_DISCOUNT_PCT) > 0) {
      throw new AppException("DISCOUNT_EXCEEDS_LIMIT", "Discount exceeds 30% or ₹500 cap", 400);
    }
    if (amount > max) {
      throw new AppException("DISCOUNT_EXCEEDS_LIMIT", "Discount exceeds 30% or ₹500 cap", 400);
    }
    Instant now = clock.instant();
    long gstTotal = items.stream().mapToLong(PosCartItem::gstAmountPaise).sum();
    long grand = Math.max(0L, subtotal - amount);
    cartStore.updateTotals(
        cartId, subtotal, gstTotal, amount, grand, t, value, null, now, now.plus(CART_TTL));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("discount_type", t);
    data.put("discount_value", value);
    data.put("discount_amount", MoneyMath.paiseToRupees(amount));
    data.put("grand_total", MoneyMath.paiseToRupees(grand));
    return data;
  }

  PosCart requireMutableCart(UUID pharmacyId, UUID cartId) {
    PosCart cart = loadActiveOrExpired(pharmacyId, cartId);
    if (cart.status() == PosCartStatus.COMPLETED) {
      throw new AppException("CART_COMPLETED", "Cart is already checked out", 400);
    }
    return cart;
  }

  PosCart loadActiveOrExpired(UUID pharmacyId, UUID cartId) {
    PosCart cart =
        cartStore
            .findById(pharmacyId, cartId)
            .orElseThrow(() -> new AppException("CART_NOT_FOUND", "Cart not found", 404));
    Instant now = clock.instant();
    if (cart.status() == PosCartStatus.ACTIVE && cart.expiresAt().isBefore(now)) {
      PosCart abandoned =
          new PosCart(
              cart.id(),
              cart.pharmacyId(),
              cart.staffId(),
              cart.customerId(),
              cart.customerName(),
              cart.customerPhone(),
              cart.prescribingDoctor(),
              cart.discountType(),
              cart.discountValue(),
              cart.discountAmountPaise(),
              cart.subtotalPaise(),
              cart.gstTotalPaise(),
              cart.grandTotalPaise(),
              PosCartStatus.ABANDONED,
              cart.expiresAt(),
              cart.invoiceId(),
              cart.appliedOfferId(),
              cart.createdAt(),
              now);
      cartStore.update(abandoned);
      throw new AppException("CART_EXPIRED", "Cart has expired", 410);
    }
    if (cart.status() == PosCartStatus.ABANDONED) {
      throw new AppException("CART_EXPIRED", "Cart has expired", 410);
    }
    return cart;
  }

  Totals recalculate(PosCart cart, Instant now) {
    List<PosCartItem> items = cartStore.listItems(cart.id());
    long subtotal = items.stream().mapToLong(PosCartItem::lineTotalPaise).sum();
    long gstTotal = items.stream().mapToLong(PosCartItem::gstAmountPaise).sum();

    boolean manual = cart.appliedOfferId() == null && cart.discountType() != null;
    String dtype = null;
    BigDecimal dval = BigDecimal.ZERO;
    long discount = 0L;
    UUID appliedOfferId = null;

    if (manual) {
      dtype = cart.discountType().name();
      if (cart.discountValue() != null) {
        dval = cart.discountValue();
      }
      discount =
          Math.min(
              MoneyMath.computeDiscountAmountPaise(dtype, dval, subtotal),
              MoneyMath.maxDiscountPaise(subtotal));
    } else {
      Optional<OfferService.AppliedOffer> best =
          offerService.bestCounterOffer(cart.pharmacyId(), items, plan.growthFeaturesEnabled());
      if (best.isPresent()) {
        OfferService.AppliedOffer applied = best.get();
        appliedOfferId = applied.offer().id();
        dtype = applied.offer().discountType().name();
        dval =
            MoneyMath.offerDiscountValueForApi(
                applied.offer().discountType(), applied.offer().discountValue());
        discount = applied.discountAmountPaise();
      }
    }

    long grand = Math.max(0L, subtotal - discount);
    cartStore.updateTotals(
        cart.id(),
        subtotal,
        gstTotal,
        discount,
        grand,
        dtype,
        dval,
        appliedOfferId,
        now,
        now.plus(CART_TTL));
    return new Totals(subtotal, gstTotal, discount, grand, appliedOfferId);
  }

  Map<String, Object> cartView(PosCart cart, List<PosCartItem> items) {
    boolean rx = items.stream().anyMatch(PosCartItem::isRxOnly);
    List<Map<String, Object>> itemViews = new ArrayList<>();
    for (PosCartItem item : items) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("item_id", item.id().toString());
      row.put("product_id", item.productId().toString());
      row.put("product_name", item.productName());
      row.put("batch_id", item.batchId().toString());
      row.put("batch_number", item.batchNumber());
      row.put("expiry_date", item.expiryDate().toString());
      row.put("quantity", item.quantity());
      row.put("is_loose", item.isLoose());
      row.put("unit_price", MoneyMath.paiseToRupees(item.unitPricePaise()));
      row.put("gst_pct", item.gstPct());
      row.put("line_subtotal", MoneyMath.paiseToRupees(item.lineSubtotalPaise()));
      row.put("gst_amount", MoneyMath.paiseToRupees(item.gstAmountPaise()));
      row.put("line_total", MoneyMath.paiseToRupees(item.lineTotalPaise()));
      row.put("is_rx_only", item.isRxOnly());
      itemViews.add(row);
    }
    Map<String, Object> customer = null;
    if (cart.customerId() != null) {
      customer = new LinkedHashMap<>();
      customer.put("customer_id", cart.customerId().toString());
      customer.put("name", cart.customerName());
      customer.put("phone", cart.customerPhone());
    }
    List<Map<String, Object>> appliedOffers = new ArrayList<>();
    if (cart.appliedOfferId() != null) {
      offerService
          .findById(cart.pharmacyId(), cart.appliedOfferId())
          .ifPresent(
              o -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("offer_id", o.id().toString());
                row.put("title", o.title());
                row.put("coupon_code", o.couponCode());
                row.put("discount_type", o.discountType().name());
                row.put(
                    "discount_value",
                    MoneyMath.offerDiscountValueForApi(o.discountType(), o.discountValue()));
                row.put("discount_amount", MoneyMath.paiseToRupees(cart.discountAmountPaise()));
                appliedOffers.add(row);
              });
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cart_id", cart.id().toString());
    data.put("status", cart.status().name());
    data.put("customer", customer);
    data.put("prescribing_doctor", cart.prescribingDoctor());
    data.put("items", itemViews);
    data.put("rx_items_present", rx);
    data.put("discount_type", cart.discountType() == null ? null : cart.discountType().name());
    data.put(
        "discount_value", cart.discountValue() == null ? BigDecimal.ZERO : cart.discountValue());
    data.put("subtotal", MoneyMath.paiseToRupees(cart.subtotalPaise()));
    data.put("gst_total", MoneyMath.paiseToRupees(cart.gstTotalPaise()));
    data.put("discount_amount", MoneyMath.paiseToRupees(cart.discountAmountPaise()));
    data.put("grand_total", MoneyMath.paiseToRupees(cart.grandTotalPaise()));
    data.put("applied_offers", appliedOffers);
    data.put("expires_at", cart.expiresAt().toString());
    data.put("created_at", cart.createdAt().toString());
    return data;
  }

  private static Map<String, Object> searchHitView(ProductLookupPort.SearchHit hit) {
    var p = hit.product();
    List<Map<String, Object>> batches = new ArrayList<>();
    for (ProductLookupPort.BatchOption b : hit.batches()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("batch_id", b.batchId().toString());
      row.put("batch_number", b.batchNumber());
      row.put("expiry_date", b.expiryDate().toString());
      row.put("quantity_current", b.quantityCurrent());
      row.put("is_fefo_first", b.fefoFirst());
      batches.add(row);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("product_id", p.productId().toString());
    data.put("name", p.name());
    data.put("manufacturer", p.manufacturer());
    data.put("form", p.form());
    data.put("pack_size", p.packSize());
    data.put("mrp", MoneyMath.paiseToRupees(p.mrpPaise()));
    data.put("total_stock_units", p.totalStockUnits());
    data.put("is_rx_only", p.isRxOnly());
    data.put("is_loose_selling_enabled", p.isLooseSellingEnabled());
    data.put("rack_locations", p.rackLocations());
    data.put("available_batches", batches);
    data.put("auto_add", hit.autoAdd());
    return data;
  }

  private static List<ProductLookupPort.BatchOption> toBatchOptions(
      List<PosFefoPort.BatchSnapshot> batches) {
    List<ProductLookupPort.BatchOption> opts = new ArrayList<>();
    for (int i = 0; i < batches.size(); i++) {
      var b = batches.get(i);
      opts.add(
          new ProductLookupPort.BatchOption(
              b.batchId(), b.batchNumber(), b.expiryDate(), b.quantityCurrent(), i == 0));
    }
    return opts;
  }

  private static boolean looksLikeRack(String q) {
    return q.matches("(?i)^[A-Z]\\d+(-\\d+)?$");
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  static void requireStaff(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.PHARMACY_OWNER && role != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
  }

  record Totals(
      long subtotalPaise,
      long gstTotalPaise,
      long discountAmountPaise,
      long grandTotalPaise,
      UUID appliedOfferId) {}
}
