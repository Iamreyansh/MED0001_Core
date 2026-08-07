package com.nammamedmate.pharmacy.application;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore;
import com.nammamedmate.pharmacy.application.port.out.ZoneStore.AdminZoneRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminZoneService {

  private static final int LIST_LIMIT = 60;
  private static final int WINDOW = 60;

  private final ZoneStore zones;
  private final RateLimiter rateLimiter;
  private final int lowPharmacyWarningThreshold;

  public AdminZoneService(
      ZoneStore zones,
      RateLimiter rateLimiter,
      @Value("${medmate.pharmacy.zones.low-pharmacy-warning-threshold:3}") int threshold) {
    this.zones = zones;
    this.rateLimiter = rateLimiter;
    this.lowPharmacyWarningThreshold = threshold;
  }

  @Transactional(readOnly = true)
  public ZoneListResult list(MedmatePrincipal principal, String city, Boolean isActive) {
    requireZoneListRole(principal);
    rateLimit("admin:zones:list:" + principal.subject(), LIST_LIMIT);

    boolean activeFilter = isActive == null ? true : isActive;
    List<AdminZoneRow> rows = zones.listForAdmin(city, activeFilter);

    List<Map<String, Object>> zoneMaps = new ArrayList<>();
    for (AdminZoneRow row : rows) {
      Map<String, Object> z = new LinkedHashMap<>();
      z.put("zone_id", row.zoneId().toString());
      z.put("zone_name", row.zoneName());
      z.put("city", row.city());
      z.put("state", row.state());
      z.put("is_active", row.active());
      z.put("pharmacy_count", row.pharmacyCount());
      z.put("online_pharmacy_count", row.onlinePharmacyCount());
      z.put("coverage_area_sqkm", row.coverageAreaSqkm());
      z.put("has_low_pharmacy_warning", row.pharmacyCount() < lowPharmacyWarningThreshold);
      z.put("created_at", row.createdAt() == null ? null : row.createdAt().toString());
      zoneMaps.add(z);
    }

    Map<String, Object> data = Map.of("zones", zoneMaps);
    PaginationMeta meta = PaginationMeta.of(1, zoneMaps.size(), zoneMaps.size());
    return new ZoneListResult(data, meta);
  }

  public record ZoneListResult(Map<String, Object> data, PaginationMeta meta) {
    public ZoneListResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requireZoneListRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_SUPPORT
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }
}
