package com.nammamedmate.order.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort.PharmacyRow;
import com.nammamedmate.order.domain.Haversine;
import com.nammamedmate.order.domain.PharmacyScore;
import com.nammamedmate.order.domain.PharmacyScorer;
import com.nammamedmate.security.AuthRole;
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
public class SmartPharmacySelectionService {

  public static final double SMART_SELECT_RADIUS_KM = 5.0;

  private final PharmacyCandidatePort pharmacies;
  private final InventoryAvailabilityPort inventory;
  private final RateLimiter rateLimiter;

  public SmartPharmacySelectionService(
      PharmacyCandidatePort pharmacies,
      InventoryAvailabilityPort inventory,
      RateLimiter rateLimiter) {
    this.pharmacies = pharmacies;
    this.inventory = inventory;
    this.rateLimiter = rateLimiter;
  }

  public Map<String, Object> smartSelect(
      MedmatePrincipal principal, UUID medicineId, Double lat, Double lng) {
    requireCustomer(principal);
    rateLimit("order:smart-select:" + principal.subject(), 30, 60);
    if (medicineId == null) {
      throw new AppException("VALIDATION_ERROR", "medicine_id is required", 400);
    }
    requireCoords(lat, lng);

    List<PharmacyRow> candidates = pharmacies.findOpenNear(lat, lng, SMART_SELECT_RADIUS_KM);
    List<ScoredPharmacy> scored = new ArrayList<>();
    for (PharmacyRow row : candidates) {
      if (!row.isOpen()) {
        continue;
      }
      if (!inventory.stocksMedicine(row.id(), medicineId)) {
        continue;
      }
      if (row.latitude() == null || row.longitude() == null) {
        continue;
      }
      double distance = Haversine.distanceKm(lat, lng, row.latitude(), row.longitude());
      PharmacyScore score =
          PharmacyScorer.score(
              row.id(),
              distance,
              SMART_SELECT_RADIUS_KM,
              row.fillRatePct(),
              row.rating(),
              row.avgPrepMinutes());
      scored.add(new ScoredPharmacy(row, score));
    }
    scored.sort(
        Comparator.comparingDouble((ScoredPharmacy s) -> s.score().totalScore())
            .reversed()
            .thenComparingDouble(s -> s.score().distanceKm()));

    Map<String, Object> data = new LinkedHashMap<>();
    if (scored.isEmpty()) {
      data.put("available", false);
      data.put("message", "Currently unavailable near you");
      data.put("selected_pharmacy", null);
      data.put("alternatives", List.of());
      return data;
    }
    data.put("available", true);
    data.put("selected_pharmacy", toSelectedView(scored.getFirst()));
    List<Map<String, Object>> alts = new ArrayList<>();
    for (int i = 1; i < scored.size(); i++) {
      alts.add(toSelectedView(scored.get(i)));
    }
    data.put("alternatives", alts);
    return data;
  }

  private static Map<String, Object> toSelectedView(ScoredPharmacy sp) {
    PharmacyRow row = sp.row();
    PharmacyScore score = sp.score();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", row.id());
    m.put("name", row.name());
    m.put("area", row.area());
    m.put("distance_km", round(score.distanceKm(), 1));
    m.put("delivery_eta_minutes", score.deliveryEtaMinutes());
    m.put("is_open", row.isOpen());
    m.put("rating", round(row.rating(), 1));
    m.put("score", round(score.totalScore(), 3));
    return m;
  }

  static void requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
  }

  static void requireCoords(Double lat, Double lng) {
    if (lat == null || lng == null) {
      throw new AppException("VALIDATION_ERROR", "lat and lng are required", 400);
    }
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
      throw new AppException("VALIDATION_ERROR", "lat/lng out of range", 400);
    }
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  static double round(double v, int scale) {
    return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  private record ScoredPharmacy(PharmacyRow row, PharmacyScore score) {}
}
