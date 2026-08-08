package com.nammamedmate.order.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.ProductPage;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.ProductRow;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort.StockLine;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.domain.Haversine;
import com.nammamedmate.order.domain.PharmacyScorer;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PharmacyDiscoveryService {

  public static final double NEARBY_DEFAULT_RADIUS_KM = 3.0;
  public static final double NEARBY_MAX_RADIUS_KM = 10.0;
  public static final int NEARBY_DEFAULT_LIMIT = 10;
  public static final int NEARBY_MAX_LIMIT = 30;

  private final PharmacyCandidatePort pharmacies;
  private final InventoryAvailabilityPort inventory;
  private final RateLimiter rateLimiter;

  public PharmacyDiscoveryService(
      PharmacyCandidatePort pharmacies,
      InventoryAvailabilityPort inventory,
      RateLimiter rateLimiter) {
    this.pharmacies = pharmacies;
    this.inventory = inventory;
    this.rateLimiter = rateLimiter;
  }

  public record NearbyResult(List<Map<String, Object>> data, Map<String, Object> meta) {
    public NearbyResult {
      data = data == null ? List.of() : List.copyOf(data);
      meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
  }

  public record ProductsResult(List<Map<String, Object>> data, Map<String, Object> meta) {
    public ProductsResult {
      data = data == null ? List.of() : List.copyOf(data);
      meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
  }

  public NearbyResult nearby(
      MedmatePrincipal principal, Double lat, Double lng, Double radiusKm, Integer limit) {
    SmartPharmacySelectionService.requireCustomer(principal);
    rateLimit("order:nearby:" + principal.subject(), 30, 60);
    SmartPharmacySelectionService.requireCoords(lat, lng);
    double radius =
        clamp(radiusKm == null ? NEARBY_DEFAULT_RADIUS_KM : radiusKm, 0.1, NEARBY_MAX_RADIUS_KM);
    int lim = clampInt(limit == null ? NEARBY_DEFAULT_LIMIT : limit, 1, NEARBY_MAX_LIMIT);

    List<PharmacyRow> rows = pharmacies.findOpenNear(lat, lng, radius);
    List<Ranked> ranked = new ArrayList<>();
    for (PharmacyRow row : rows) {
      if (row.latitude() == null || row.longitude() == null) {
        continue;
      }
      double d = Haversine.distanceKm(lat, lng, row.latitude(), row.longitude());
      ranked.add(new Ranked(row, d));
    }
    ranked.sort(Comparator.comparingDouble(Ranked::distanceKm));
    int total = ranked.size();
    List<Map<String, Object>> data = new ArrayList<>();
    for (int i = 0; i < Math.min(lim, ranked.size()); i++) {
      Ranked r = ranked.get(i);
      data.add(toNearbyCard(r.row(), r.distanceKm()));
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("total", total);
    meta.put("radius_km", radius);
    return new NearbyResult(data, meta);
  }

  public Map<String, Object> storefront(
      MedmatePrincipal principal, UUID pharmacyId, Double lat, Double lng) {
    SmartPharmacySelectionService.requireCustomer(principal);
    rateLimit("order:storefront:" + principal.subject(), 60, 60);
    PharmacyRow row =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    Double distanceKm = null;
    Integer eta = null;
    if (lat != null && lng != null && hasGeo(row)) {
      distanceKm = Haversine.distanceKm(lat, lng, row.latitude(), row.longitude());
      eta = PharmacyScorer.deliveryEtaMinutes(distanceKm, row.avgPrepMinutes());
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", row.id());
    data.put("name", row.name());
    data.put("area", row.area());
    data.put(
        "distance_km",
        distanceKm == null ? null : SmartPharmacySelectionService.round(distanceKm, 1));
    data.put("delivery_eta_minutes", eta);
    data.put("is_open", row.isOpen());
    data.put("rating", SmartPharmacySelectionService.round(row.rating(), 1));
    data.put("review_count", row.reviewCount());
    data.put("logo_url", row.logoUrl());
    data.put("current_offer", row.currentOffer());
    data.put("categories_available", pharmacies.categoriesAvailable(row.id()));
    data.put("items_count", pharmacies.visibleItemsCount(row.id()));
    data.put("open_hours", pharmacies.openHoursSummary(row.id()).orElse(null));
    data.put("address", blankToNull(row.addressLine()));
    return data;
  }

  public ProductsResult products(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String category,
      String search,
      Integer page,
      Integer limit) {
    SmartPharmacySelectionService.requireCustomer(principal);
    rateLimit("order:products:" + principal.subject(), 30, 60);
    pharmacies
        .findById(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    int p = page == null ? 1 : page;
    int lim = limit == null ? 20 : limit;
    ProductPage result = inventory.listVisibleProducts(pharmacyId, category, search, p, lim);
    List<Map<String, Object>> data = new ArrayList<>();
    for (ProductRow row : result.items()) {
      data.add(toProductView(row));
    }
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("page", result.page());
    meta.put("limit", result.limit());
    meta.put("total", result.total());
    meta.put(
        "total_pages",
        result.limit() == 0 ? 0 : (int) Math.ceil(result.total() / (double) result.limit()));
    return new ProductsResult(data, meta);
  }

  public Map<String, Object> availabilityCheck(
      MedmatePrincipal principal, UUID pharmacyId, List<UUID> medicineIds) {
    SmartPharmacySelectionService.requireCustomer(principal);
    rateLimit("order:availability:" + principal.subject(), 20, 60);
    if (pharmacyId == null) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_id is required", 400);
    }
    if (medicineIds == null || medicineIds.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "medicine_ids is required", 400);
    }
    PharmacyRow row =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    List<StockLine> lines = inventory.checkAvailability(pharmacyId, medicineIds);
    List<Map<String, Object>> available = new ArrayList<>();
    List<Map<String, Object>> unavailable = new ArrayList<>();
    for (StockLine line : lines) {
      if (line.inStock()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medicine_id", line.medicineId());
        m.put("name", line.name());
        m.put("quantity_available", line.quantityAvailable());
        m.put("price", paiseToRupees(line.pricePaise()));
        available.add(m);
      } else {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medicine_id", line.medicineId());
        m.put("name", line.name());
        m.put(
            "reason", line.unavailableReason() == null ? "OUT_OF_STOCK" : line.unavailableReason());
        unavailable.add(m);
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", row.id());
    data.put("pharmacy_name", row.name());
    data.put("is_open", row.isOpen());
    data.put("available", available);
    data.put("unavailable", unavailable);
    return data;
  }

  private Map<String, Object> toNearbyCard(PharmacyRow row, double distanceKm) {
    int eta = PharmacyScorer.deliveryEtaMinutes(distanceKm, row.avgPrepMinutes());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", row.id());
    m.put("name", row.name());
    m.put("area", row.area());
    m.put("distance_km", SmartPharmacySelectionService.round(distanceKm, 1));
    m.put("delivery_eta_minutes", eta);
    m.put("is_open", row.isOpen());
    m.put("rating", SmartPharmacySelectionService.round(row.rating(), 1));
    m.put("review_count", row.reviewCount());
    m.put("current_offer", row.currentOffer());
    m.put("logo_url", row.logoUrl());
    m.put("categories_available", pharmacies.categoriesAvailable(row.id()));
    m.put("items_count", pharmacies.visibleItemsCount(row.id()));
    return m;
  }

  private static Map<String, Object> toProductView(ProductRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("product_id", row.productId());
    m.put("name", row.name());
    m.put("brand", row.brand());
    m.put("category", row.category());
    m.put("pack_size", row.packSize());
    m.put("mrp", paiseToRupees(row.mrpPaise()));
    m.put("selling_price", paiseToRupees(row.sellingPricePaise()));
    m.put("discount_pct", discountPct(row.mrpPaise(), row.sellingPricePaise()));
    m.put("is_rx_required", row.rxRequired());
    m.put("quantity_available", row.quantityAvailable());
    m.put("image_url", row.imageUrl());
    return m;
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  static double discountPct(long mrpPaise, long sellingPaise) {
    if (mrpPaise <= 0 || sellingPaise >= mrpPaise) {
      return 0;
    }
    return BigDecimal.valueOf((mrpPaise - sellingPaise) * 100.0 / mrpPaise)
        .setScale(0, RoundingMode.HALF_UP)
        .doubleValue();
  }

  private static boolean hasGeo(PharmacyRow row) {
    return row.latitude() != null && row.longitude() != null;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static double clamp(double v, double min, double max) {
    return Math.max(min, Math.min(max, v));
  }

  private static int clampInt(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private record Ranked(PharmacyRow row, double distanceKm) {}
}
