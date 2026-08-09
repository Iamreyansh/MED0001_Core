package com.nammamedmate.inventory.application;

import com.nammamedmate.inventory.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.inventory.application.port.out.RackLocationStore;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.Kpi;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListResult;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListRow;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ProductPreview;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.UnlocatedPage;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.RackLocation;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RackLocationService {

  static final Pattern RACK_CODE_PATTERN = Pattern.compile("^[A-Z]{1,2}[0-9]{1,2}-[0-9]{2}$");
  private static final int MAX_LABELS = 120;
  private static final int LIST_LIMIT_RATE = 60;
  private static final int CREATE_LIMIT_RATE = 30;
  private static final int DELETE_LIMIT_RATE = 10;
  private static final int DETAIL_LIMIT_RATE = 60;
  private static final int ASSIGN_LIMIT_RATE = 20;
  private static final int UNLOCATED_LIMIT_RATE = 30;
  private static final int PRINT_LIMIT_RATE = 5;
  private static final int PATCH_RACK_LIMIT_RATE = 60;

  private final RackLocationStore store;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public RackLocationService(RackLocationStore store, RateLimiter rateLimiter, Clock clock) {
    this.store = store;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record PageResult(Map<String, Object> data, Map<String, Object> meta) {
    public PageResult {
      data =
          data == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
      meta =
          meta == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    @Override
    public Map<String, Object> data() {
      return new LinkedHashMap<>(data);
    }

    @Override
    public Map<String, Object> meta() {
      return new LinkedHashMap<>(meta);
    }
  }

  @Transactional(readOnly = true)
  public PageResult list(
      MedmatePrincipal principal, String zone, String q, Integer page, Integer limit) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:rack:list:" + principal.pharmacyId(), LIST_LIMIT_RATE);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 50 : Math.min(limit, 100);
    ListFilter filter = new ListFilter(principal.pharmacyId(), zone, q, p, lim);
    ListResult result = store.list(filter);
    Kpi kpi = store.kpi(principal.pharmacyId());

    List<Map<String, Object>> racks = new ArrayList<>(result.rows().size());
    for (ListRow row : result.rows()) {
      racks.add(toListMap(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi", toKpiMap(kpi));
    data.put("racks", racks);

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("page", p);
    meta.put("limit", lim);
    meta.put("total", result.total());
    meta.put("has_next", (long) p * lim < result.total());
    return new PageResult(data, meta);
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal, String rackCode, String zoneName, String description) {
    requirePharmacyOwner(principal);
    rateLimit("pharmacy:rack:create:" + principal.pharmacyId(), CREATE_LIMIT_RATE);
    String code = normalizeCode(rackCode);
    validateCodeFormat(code);
    if (zoneName == null || zoneName.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "zone_name is required", 400);
    }
    if (zoneName.length() > 100) {
      throw new AppException("VALIDATION_ERROR", "zone_name max 100 characters", 400);
    }
    String desc = description == null || description.isBlank() ? null : description.trim();
    if (desc != null && desc.length() > 300) {
      throw new AppException("VALIDATION_ERROR", "description max 300 characters", 400);
    }
    if (store.findByCode(principal.pharmacyId(), code).isPresent()) {
      throw new AppException("RACK_CODE_EXISTS", "Rack code already exists", 409);
    }
    Instant now = clock.instant();
    RackLocation rack =
        new RackLocation(
            UUID.randomUUID(), principal.pharmacyId(), code, zoneName.trim(), desc, now, now, null);
    try {
      store.insert(rack);
    } catch (DuplicateKeyException e) {
      throw new AppException("RACK_CODE_EXISTS", "Rack code already exists", 409);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rack_code", rack.rackCode());
    data.put("zone_name", rack.zoneName());
    data.put("description", rack.description());
    data.put("medicine_count", 0);
    data.put("created_at", rack.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, String rackCode) {
    requirePharmacyOwner(principal);
    rateLimit("pharmacy:rack:delete:" + principal.pharmacyId(), DELETE_LIMIT_RATE);
    String code = normalizeCode(rackCode);
    RackLocation existing =
        store
            .findByCode(principal.pharmacyId(), code)
            .orElseThrow(() -> new AppException("RACK_NOT_FOUND", "Rack not found", 404));
    List<ProductPreview> blockers = store.blockingProducts(principal.pharmacyId(), code, 50);
    if (!blockers.isEmpty()) {
      List<Map<String, Object>> products = new ArrayList<>(blockers.size());
      for (ProductPreview p : blockers) {
        products.add(Map.of("product_id", p.productId().toString(), "name", p.name()));
      }
      throw new AppException(
          "RACK_NOT_EMPTY",
          "Rack still has medicines assigned",
          400,
          null,
          Map.of("products", products, "medicine_count", blockers.size()));
    }
    Instant now = clock.instant();
    store
        .softDelete(principal.pharmacyId(), code, now)
        .orElseThrow(() -> new AppException("RACK_NOT_FOUND", "Rack not found", 404));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rack_code", existing.rackCode());
    data.put("deleted_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> detail(MedmatePrincipal principal, String rackCode) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:rack:detail:" + principal.pharmacyId(), DETAIL_LIMIT_RATE);
    String code = normalizeCode(rackCode);
    RackLocation rack =
        store
            .findByCode(principal.pharmacyId(), code)
            .orElseThrow(() -> new AppException("RACK_NOT_FOUND", "Rack not found", 404));
    List<PharmacyProduct> medicines = store.medicinesInRack(principal.pharmacyId(), code);
    List<Map<String, Object>> medMaps = new ArrayList<>(medicines.size());
    for (PharmacyProduct p : medicines) {
      medMaps.add(toMedicineDetail(p));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rack_code", rack.rackCode());
    data.put("zone_name", rack.zoneName());
    data.put("description", rack.description());
    data.put("medicine_count", medicines.size());
    data.put("medicines", medMaps);
    return data;
  }

  @Transactional
  public Map<String, Object> assign(
      MedmatePrincipal principal, List<UUID> productIds, String rackCode) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:rack:assign:" + principal.pharmacyId(), ASSIGN_LIMIT_RATE);
    if (productIds == null || productIds.isEmpty()) {
      throw new AppException("EMPTY_PRODUCT_LIST", "product_ids must not be empty", 400);
    }
    String code = normalizeCode(rackCode);
    store
        .findByCode(principal.pharmacyId(), code)
        .orElseThrow(() -> new AppException("RACK_NOT_FOUND", "Rack not found", 404));
    Instant now = clock.instant();
    // Deduplicate while preserving order for assigned/skipped counts.
    List<UUID> unique = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    for (UUID id : productIds) {
      if (id != null && seen.add(id)) {
        unique.add(id);
      }
    }
    List<UUID> assigned = store.assignRack(principal.pharmacyId(), unique, code, now);
    int skipped = unique.size() - assigned.size();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("rack_code", code);
    data.put("assigned_count", assigned.size());
    data.put("skipped_count", skipped);
    data.put(
        "product_ids_assigned", assigned.stream().map(UUID::toString).collect(Collectors.toList()));
    return data;
  }

  @Transactional(readOnly = true)
  public PageResult unlocated(MedmatePrincipal principal, Integer page, Integer limit) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:rack:unlocated:" + principal.pharmacyId(), UNLOCATED_LIMIT_RATE);
    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null || limit < 1 ? 20 : Math.min(limit, 100);
    UnlocatedPage result = store.unlocated(principal.pharmacyId(), p, lim);
    List<Map<String, Object>> products = new ArrayList<>(result.products().size());
    for (PharmacyProduct prod : result.products()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("product_id", prod.id().toString());
      m.put("name", prod.name());
      m.put("form", prod.form());
      m.put("total_stock_units", prod.totalStockUnits());
      m.put("category_name", prod.categoryName());
      products.add(m);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("unlocated_count", result.total());
    data.put("products", products);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("page", p);
    meta.put("limit", lim);
    meta.put("total", result.total());
    meta.put("has_next", (long) p * lim < result.total());
    return new PageResult(data, meta);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> printLabels(MedmatePrincipal principal, List<String> rackCodes) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:rack:print:" + principal.pharmacyId(), PRINT_LIMIT_RATE);
    if (rackCodes == null || rackCodes.isEmpty()) {
      throw new AppException("EMPTY_RACK_CODES", "rack_codes must not be empty", 400);
    }
    if (rackCodes.size() > MAX_LABELS) {
      throw new AppException(
          "TOO_MANY_LABELS", "Maximum " + MAX_LABELS + " labels per request", 400);
    }
    List<String> codes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String raw : rackCodes) {
      String code = normalizeCode(raw);
      if (seen.add(code)) {
        codes.add(code);
      }
    }
    List<RackLocation> found = store.findByCodes(principal.pharmacyId(), codes);
    Set<String> foundCodes = found.stream().map(RackLocation::rackCode).collect(Collectors.toSet());
    List<String> missing = codes.stream().filter(c -> !foundCodes.contains(c)).toList();
    if (!missing.isEmpty()) {
      throw new AppException(
          "RACK_CODES_NOT_FOUND",
          "One or more rack codes not found",
          404,
          null,
          Map.of("invalid_rack_codes", missing));
    }
    StringBuilder text = new StringBuilder("Rack Labels\n");
    for (RackLocation rack : found) {
      long count = store.medicineCount(principal.pharmacyId(), rack.rackCode());
      text.append(rack.rackCode())
          .append(" | ")
          .append(rack.zoneName())
          .append(" | meds=")
          .append(count)
          .append(" | qr=https://app.medmate.in/pharmacy/")
          .append(principal.pharmacyId())
          .append("/rack/")
          .append(rack.rackCode())
          .append('\n');
    }
    // ponytail: minimal PDF bytes + data URL (no WeasyPrint/S3). Ceiling: stub labels only;
    // upgrade to A4 24-up + QR image + signed S3 URL when label printer UX lands.
    byte[] pdf = SimplePdfExporter.buildPdf(text.toString());
    String dataUrl = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf);
    Instant expiresAt = clock.instant().plusSeconds(7200);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pdf_url", dataUrl);
    data.put("expires_at", expiresAt.toString());
    data.put("label_count", found.size());
    return data;
  }

  @Transactional
  public Map<String, Object> patchProductRack(
      MedmatePrincipal principal, UUID productId, String rackCode, String action) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:rack:" + principal.pharmacyId(), PATCH_RACK_LIMIT_RATE);
    String code = normalizeCode(rackCode);
    validateCodeFormat(code);
    String act =
        action == null || action.isBlank() ? "ADD" : action.trim().toUpperCase(Locale.ROOT);
    if (!"ADD".equals(act) && !"REMOVE".equals(act)) {
      throw new AppException("VALIDATION_ERROR", "action must be ADD or REMOVE", 400);
    }
    Instant now = clock.instant();
    PharmacyProduct updated;
    if ("ADD".equals(act)) {
      store
          .findByCode(principal.pharmacyId(), code)
          .orElseThrow(() -> new AppException("RACK_NOT_FOUND", "Rack not found", 404));
      updated =
          store
              .addRackToProduct(principal.pharmacyId(), productId, code, now)
              .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));
    } else {
      updated =
          store
              .removeRackFromProduct(principal.pharmacyId(), productId, code, now)
              .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("product_id", updated.id().toString());
    data.put("rack_locations", updated.rackLocations());
    data.put("updated_at", updated.updatedAt().toString());
    return data;
  }

  private static Map<String, Object> toListMap(ListRow row) {
    RackLocation rack = row.rack();
    List<Map<String, Object>> preview = new ArrayList<>();
    for (PharmacyProduct p : row.preview()) {
      preview.add(Map.of("product_id", p.id().toString(), "name", p.name()));
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rack_code", rack.rackCode());
    m.put("zone_name", rack.zoneName());
    m.put("description", rack.description());
    m.put("medicine_count", row.medicineCount());
    m.put("medicines_preview", preview);
    m.put("created_at", rack.createdAt() == null ? null : rack.createdAt().toString());
    return m;
  }

  private static Map<String, Object> toKpiMap(Kpi kpi) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("racks_count", kpi.racksCount());
    m.put("zones_count", kpi.zonesCount());
    m.put("medicines_mapped_count", kpi.medicinesMappedCount());
    m.put("unlocated_count", kpi.unlocatedCount());
    return m;
  }

  private static Map<String, Object> toMedicineDetail(PharmacyProduct p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("product_id", p.id().toString());
    m.put("name", p.name());
    m.put("form", p.form());
    m.put("pack_size", p.packSize());
    m.put("total_stock_units", p.totalStockUnits());
    m.put("mrp", InventoryProductService.paiseToRupees(p.mrpPaise()));
    m.put("is_rx_only", p.isRxOnly());
    m.put("earliest_expiry", p.earliestExpiry() == null ? null : p.earliestExpiry().toString());
    return m;
  }

  static String normalizeCode(String rackCode) {
    if (rackCode == null || rackCode.isBlank()) {
      throw new AppException("INVALID_RACK_CODE_FORMAT", "rack_code is required", 400);
    }
    return rackCode.trim().toUpperCase(Locale.ROOT);
  }

  static void validateCodeFormat(String code) {
    if (!RACK_CODE_PATTERN.matcher(code).matches()) {
      throw new AppException(
          "INVALID_RACK_CODE_FORMAT", "rack_code must match [A-Z]{1,2}[0-9]{1,2}-[0-9]{2}", 400);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, 60)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requirePharmacyReader(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("UNAUTHORIZED", "Pharmacy context missing", 401);
    }
  }

  private static void requirePharmacyOwner(MedmatePrincipal principal) {
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Pharmacy owner role required", 403);
    }
  }
}
