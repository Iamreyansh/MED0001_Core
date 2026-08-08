package com.nammamedmate.order.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort.AddressRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.MedicineDetails;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.application.port.out.ReorderAttemptLogStore;
import com.nammamedmate.order.application.port.out.ReorderAttemptLogStore.ReorderAttemptLog;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import com.nammamedmate.order.domain.Cart;
import com.nammamedmate.order.domain.CartItem;
import com.nammamedmate.order.domain.CartPricing;
import com.nammamedmate.order.domain.Haversine;
import com.nammamedmate.order.domain.Order;
import com.nammamedmate.order.domain.OrderItemSnapshot;
import com.nammamedmate.order.domain.PharmacyScorer;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReorderService {

  private final OrderStore orders;
  private final CartService cartService;
  private final InventoryAvailabilityPort inventory;
  private final PharmacyCandidatePort pharmacies;
  private final CustomerAddressPort addresses;
  private final RiderLookupPort riders;
  private final ReorderAttemptLogStore reorderLogs;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public ReorderService(
      OrderStore orders,
      CartService cartService,
      InventoryAvailabilityPort inventory,
      PharmacyCandidatePort pharmacies,
      CustomerAddressPort addresses,
      RiderLookupPort riders,
      ReorderAttemptLogStore reorderLogs,
      RateLimiter rateLimiter,
      Clock clock) {
    this.orders = orders;
    this.cartService = cartService;
    this.inventory = inventory;
    this.pharmacies = pharmacies;
    this.addresses = addresses;
    this.riders = riders;
    this.reorderLogs = reorderLogs;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record HistoryResult(List<Map<String, Object>> data, PaginationMeta meta) {}

  @Transactional
  public Map<String, Object> reorder(
      MedmatePrincipal principal, UUID pastOrderId, Boolean confirmPharmacyChange) {
    requireCustomer(principal);
    rateLimit("order:reorder:" + principal.subject(), 10, 60);
    if (pastOrderId == null) {
      throw new AppException("ORDER_NOT_FOUND", "Past order not found", 404);
    }
    Order order =
        orders
            .findByCustomerAndId(principal.subject(), pastOrderId)
            .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Past order not found", 404));
    List<OrderItemSnapshot> requested = order.items();
    if (requested.isEmpty()) {
      throw new AppException("NO_ITEMS_AVAILABLE", "No items available to reorder", 422);
    }

    double[] coords = resolveCoords(principal.subject(), order.deliveryAddressId());
    PharmacyRow original = pharmacies.findById(order.pharmacyId()).orElse(null);
    boolean samePharmacyOk =
        original != null && original.isOpen() && allItemsInStock(original.id(), requested);

    PharmacyRow selected;
    boolean pharmacyChanged;
    String pharmacyNote = null;
    List<Map<String, Object>> excluded = new ArrayList<>();
    List<CartItem> toAdd;

    if (samePharmacyOk) {
      selected = original;
      pharmacyChanged = false;
      toAdd = buildCartItems(selected.id(), requested, excluded);
    } else {
      FillPick pick = pickHighestFill(coords[0], coords[1], requested);
      if (pick.available().isEmpty()) {
        throw new AppException(
            "NO_ITEMS_AVAILABLE", "All items from the original order are unavailable", 422);
      }
      selected = pick.pharmacy();
      pharmacyChanged = !selected.id().equals(order.pharmacyId());
      excluded.addAll(pick.excluded());
      toAdd = pick.available();
      if (pharmacyChanged && !Boolean.TRUE.equals(confirmPharmacyChange)) {
        Map<String, Object> suggested = pharmacyMini(selected);
        String origName = original == null ? "Original pharmacy" : original.name();
        String reason =
            original == null || !original.isOpen()
                ? "Original pharmacy " + origName + " is currently closed"
                : "Original pharmacy " + origName + " cannot fulfill all items";
        throw new AppException(
            "PHARMACY_CHANGE_REQUIRED",
            reason
                + ". Cart will be created at "
                + selected.name()
                + ", "
                + nullToEmpty(selected.area())
                + ".",
            409,
            null,
            Map.of("suggested_pharmacy", suggested));
      }
      if (pharmacyChanged) {
        String origName = original == null ? "original pharmacy" : original.name();
        pharmacyNote =
            original == null || !original.isOpen()
                ? "Original pharmacy " + origName + " is currently closed"
                : "Original pharmacy " + origName + " could not fulfill all items";
      }
    }

    if (toAdd.isEmpty()) {
      throw new AppException(
          "NO_ITEMS_AVAILABLE", "All items from the original order are unavailable", 422);
    }

    Cart cart =
        cartService.createActiveForReorder(
            principal.subject(), selected.id(), order.deliveryAddressId(), toAdd);

    Instant now = clock.instant();
    reorderLogs.insert(
        new ReorderAttemptLog(
            UUID.randomUUID(),
            principal.subject(),
            order.id(),
            cart.id(),
            pharmacyChanged,
            requested.size(),
            toAdd.size(),
            excluded.size(),
            now));

    return reorderView(cart, selected, toAdd, excluded, pharmacyNote);
  }

  @Transactional(readOnly = true)
  public HistoryResult history(
      MedmatePrincipal principal, Integer page, Integer limit, String status) {
    requireCustomer(principal);
    rateLimit("order:history:" + principal.subject(), 30, 60);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 50);
    String filter = normalizeHistoryStatus(status);
    long total = orders.countCustomerHistory(principal.subject(), filter);
    List<Order> rows = orders.listCustomerHistory(principal.subject(), filter, (p - 1) * lim, lim);
    List<Map<String, Object>> data = new ArrayList<>();
    for (Order o : rows) {
      data.add(historyRow(o));
    }
    return new HistoryResult(data, PaginationMeta.of(p, lim, total));
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> active(MedmatePrincipal principal) {
    requireCustomer(principal);
    rateLimit("order:active:" + principal.subject(), 60, 60);
    Instant now = clock.instant();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Order o : orders.listCustomerActive(principal.subject())) {
      out.add(activeRow(o, now));
    }
    return out;
  }

  private FillPick pickHighestFill(double lat, double lng, List<OrderItemSnapshot> requested) {
    List<PharmacyRow> candidates =
        pharmacies.findOpenNear(lat, lng, SmartPharmacySelectionService.SMART_SELECT_RADIUS_KM);
    if (candidates.isEmpty()) {
      List<Map<String, Object>> excluded = new ArrayList<>();
      for (OrderItemSnapshot item : requested) {
        excluded.add(excludedItem(item, "PHARMACY_UNAVAILABLE", "No open pharmacies nearby"));
      }
      return new FillPick(null, List.of(), excluded);
    }

    List<ScoredFill> scored = new ArrayList<>();
    for (PharmacyRow row : candidates) {
      if (!row.isOpen() || row.latitude() == null || row.longitude() == null) {
        continue;
      }
      List<Map<String, Object>> excluded = new ArrayList<>();
      List<CartItem> available = buildCartItems(row.id(), requested, excluded);
      double distance = Haversine.distanceKm(lat, lng, row.latitude(), row.longitude());
      double score =
          PharmacyScorer.score(
                  row.id(),
                  distance,
                  SmartPharmacySelectionService.SMART_SELECT_RADIUS_KM,
                  row.fillRatePct(),
                  row.rating(),
                  row.avgPrepMinutes())
              .totalScore();
      scored.add(new ScoredFill(row, available, excluded, available.size(), score, distance));
    }
    if (scored.isEmpty()) {
      List<Map<String, Object>> excluded = new ArrayList<>();
      for (OrderItemSnapshot item : requested) {
        excluded.add(excludedItem(item, "PHARMACY_UNAVAILABLE", "No open pharmacies nearby"));
      }
      return new FillPick(null, List.of(), excluded);
    }
    scored.sort(
        Comparator.comparingInt(ScoredFill::fillCount)
            .reversed()
            .thenComparing((a, b) -> Double.compare(b.score(), a.score()))
            .thenComparingDouble(ScoredFill::distanceKm));
    ScoredFill best = scored.getFirst();
    if (best.fillCount() == 0) {
      List<Map<String, Object>> excluded = new ArrayList<>();
      for (OrderItemSnapshot item : requested) {
        excluded.add(excludedItem(item, "OUT_OF_STOCK", "Not available at nearby pharmacies"));
      }
      return new FillPick(best.pharmacy(), List.of(), excluded);
    }
    return new FillPick(best.pharmacy(), best.available(), best.excluded());
  }

  private boolean allItemsInStock(UUID pharmacyId, List<OrderItemSnapshot> requested) {
    for (OrderItemSnapshot item : requested) {
      if (item.productId() == null) {
        return false;
      }
      StockLine stock = stockAt(pharmacyId, item.productId());
      if (!stock.inStock() || stock.quantityAvailable() < item.quantity()) {
        return false;
      }
    }
    return true;
  }

  private List<CartItem> buildCartItems(
      UUID pharmacyId, List<OrderItemSnapshot> requested, List<Map<String, Object>> excluded) {
    List<CartItem> out = new ArrayList<>();
    for (OrderItemSnapshot item : requested) {
      if (item.productId() == null) {
        excluded.add(excludedItem(item, "OUT_OF_STOCK", "Not available at nearby pharmacies"));
        continue;
      }
      StockLine stock = stockAt(pharmacyId, item.productId());
      if (!stock.inStock() || stock.quantityAvailable() < item.quantity()) {
        excluded.add(excludedItem(item, "OUT_OF_STOCK", "Not available at nearby pharmacies"));
        continue;
      }
      MedicineDetails medicine =
          inventory
              .findMedicine(item.productId())
              .orElse(
                  new MedicineDetails(
                      item.productId(), item.name(), null, null, item.rxRequired(), null, false));
      if (medicine.banned()) {
        excluded.add(excludedItem(item, "OUT_OF_STOCK", "Not available at nearby pharmacies"));
        continue;
      }
      out.add(
          new CartItem(
              UUID.randomUUID(),
              medicine.id(),
              item.quantity(),
              stock.pricePaise(),
              medicine.rxRequired(),
              medicine.name(),
              medicine.brand(),
              medicine.packSize(),
              medicine.imageUrl()));
    }
    return out;
  }

  private StockLine stockAt(UUID pharmacyId, UUID medicineId) {
    List<StockLine> lines = inventory.checkAvailability(pharmacyId, List.of(medicineId));
    if (lines.isEmpty()) {
      return new StockLine(medicineId, "Unknown", 0, 0, 0, false, "NOT_FOUND");
    }
    return lines.getFirst();
  }

  private Map<String, Object> reorderView(
      Cart cart,
      PharmacyRow pharmacy,
      List<CartItem> added,
      List<Map<String, Object>> excluded,
      String pharmacyNote) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("cart_id", cart.id());
    Map<String, Object> ph = pharmacyMini(pharmacy);
    if (pharmacyNote != null) {
      ph.put("note", pharmacyNote);
    }
    data.put("pharmacy", ph);
    List<Map<String, Object>> itemsAdded = new ArrayList<>();
    boolean rxRequired = false;
    for (CartItem item : added) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", item.name());
      row.put("quantity", item.quantity());
      row.put("price", CartPricing.paiseToRupees(item.unitPricePaise()));
      itemsAdded.add(row);
      if (item.rxRequired()) {
        rxRequired = true;
      }
    }
    data.put("items_added", itemsAdded);
    data.put("excluded_items", excluded);
    data.put("prescription_required", rxRequired);
    data.put("prescription_attached", false);
    data.put("message", reorderMessage(added.size(), excluded.size(), rxRequired));
    return data;
  }

  private static String reorderMessage(int added, int excluded, boolean rxRequired) {
    String base =
        excluded == 0
            ? "Cart created with " + added + " item" + (added == 1 ? "" : "s") + "."
            : "Cart created with "
                + added
                + " item"
                + (added == 1 ? "" : "s")
                + ". "
                + excluded
                + " item"
                + (excluded == 1 ? " was" : "s were")
                + " unavailable and excluded.";
    if (rxRequired) {
      return base + " Please attach a prescription to proceed.";
    }
    return base.trim();
  }

  private Map<String, Object> historyRow(Order order) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("order_id", order.id());
    row.put("order_number", order.orderNumber());
    PharmacyRow pharmacy = pharmacies.findById(order.pharmacyId()).orElse(null);
    Map<String, Object> ph = new LinkedHashMap<>();
    ph.put("name", pharmacy == null ? null : pharmacy.name());
    ph.put("logo", pharmacy == null ? null : pharmacy.logoUrl());
    row.put("pharmacy", ph);
    List<OrderItemSnapshot> items = order.items();
    row.put("items_count", items.size());
    List<String> preview = new ArrayList<>();
    for (int i = 0; i < Math.min(3, items.size()); i++) {
      OrderItemSnapshot item = items.get(i);
      preview.add(item.name() + " - " + item.quantity());
    }
    row.put("items_preview", preview);
    row.put("status", order.status().name());
    row.put("total", CartPricing.paiseToRupees(order.totalPayablePaise()));
    row.put("payment_method", order.paymentMethod().name());
    row.put("has_rx_items", items.stream().anyMatch(OrderItemSnapshot::rxRequired));
    row.put("created_at", order.createdAt().toString());
    row.put("delivered_at", order.deliveredAt() == null ? null : order.deliveredAt().toString());
    return row;
  }

  private Map<String, Object> activeRow(Order order, Instant now) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("order_id", order.id());
    row.put("order_number", order.orderNumber());
    PharmacyRow pharmacy = pharmacies.findById(order.pharmacyId()).orElse(null);
    Map<String, Object> ph = new LinkedHashMap<>();
    ph.put("name", pharmacy == null ? null : pharmacy.name());
    row.put("pharmacy", ph);
    row.put("status", order.status().name());
    row.put("items_count", order.items().size());
    row.put("total", CartPricing.paiseToRupees(order.totalPayablePaise()));
    row.put("eta_minutes", order.etaMinutes(now));
    if (order.riderId() != null) {
      riders
          .findById(order.riderId())
          .ifPresentOrElse(
              r -> {
                Map<String, Object> rider = new LinkedHashMap<>();
                rider.put("name", r.name());
                rider.put("phone", r.phone());
                rider.put("vehicle_plate", r.vehiclePlate());
                row.put("rider", rider);
              },
              () -> row.put("rider", null));
    } else {
      row.put("rider", null);
    }
    row.put("created_at", order.createdAt().toString());
    row.put(
        "estimated_delivery_at",
        order.estimatedDeliveryAt() == null ? null : order.estimatedDeliveryAt().toString());
    return row;
  }

  private double[] resolveCoords(UUID customerId, UUID addressId) {
    if (addressId != null) {
      Optional<AddressRow> addr = addresses.findForCustomer(addressId, customerId);
      if (addr.isPresent()) {
        return new double[] {addr.get().lat(), addr.get().lng()};
      }
    }
    Optional<AddressRow> def = addresses.findDefault(customerId);
    if (def.isPresent()) {
      return new double[] {def.get().lat(), def.get().lng()};
    }
    throw new AppException("VALIDATION_ERROR", "Delivery address required to reorder", 400);
  }

  private static String normalizeHistoryStatus(String status) {
    if (status == null || status.isBlank()) {
      return "ALL";
    }
    String s = status.trim().toUpperCase(Locale.ROOT);
    if ("ALL".equals(s) || "DELIVERED".equals(s) || "CANCELLED".equals(s)) {
      return s;
    }
    throw new AppException("VALIDATION_ERROR", "status must be DELIVERED, CANCELLED, or ALL", 400);
  }

  private static Map<String, Object> pharmacyMini(PharmacyRow pharmacy) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", pharmacy.id());
    m.put("name", pharmacy.name());
    m.put("area", pharmacy.area());
    return m;
  }

  private static Map<String, Object> excludedItem(
      OrderItemSnapshot item, String reason, String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", item.name());
    m.put("quantity", item.quantity());
    m.put("reason", reason);
    m.put("message", message);
    return m;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("FORBIDDEN", "Customer access required", 403);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429, windowSeconds);
    }
  }

  private record FillPick(
      PharmacyRow pharmacy, List<CartItem> available, List<Map<String, Object>> excluded) {}

  private record ScoredFill(
      PharmacyRow pharmacy,
      List<CartItem> available,
      List<Map<String, Object>> excluded,
      int fillCount,
      double score,
      double distanceKm) {}
}
