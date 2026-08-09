package com.nammamedmate.inventory.application;

import com.nammamedmate.inventory.application.port.out.InventoryExcelExporter;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.DetailsPatch;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListResult;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.SettingsPatch;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.SummaryRow;
import com.nammamedmate.inventory.domain.InventoryFlags;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryProductService {

  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 60;
  private static final int SUMMARY_LIMIT = 30;
  private static final int DETAIL_LIMIT = 120;
  private static final int PATCH_LIMIT = 60;
  private static final int DETAILS_PATCH_LIMIT = 30;
  private static final Set<String> TABS =
      Set.of("ALL", "ALERTS", "LOW_STOCK", "EXPIRING", "RX_ONLY", "OUT_OF_STOCK", "UNALLOCATED");
  private static final Set<String> SORTS = Set.of("name", "stock", "value", "expiry");
  private static final Set<String> FORMS =
      Set.of("TABLET", "SYRUP", "CAPSULE", "DROPS", "INJECTION", "POWDER", "CREAM", "GEL", "OTHER");
  private static final Set<String> SCHEDULES = Set.of("OTC", "H", "H1", "X", "G", "OTHER");
  private static final Set<Integer> GST_SLABS = Set.of(0, 5, 12, 18, 28);

  private final PharmacyProductStore store;
  private final InventoryBatchService batchService;
  private final InventoryPlanPort planPort;
  private final InventoryExcelExporter excelExporter;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public InventoryProductService(
      PharmacyProductStore store,
      InventoryBatchService batchService,
      InventoryPlanPort planPort,
      InventoryExcelExporter excelExporter,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.batchService = batchService;
    this.planPort = planPort;
    this.excelExporter = excelExporter;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListPage(Map<String, Object> data, Map<String, Object> meta) {
    public ListPage {
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

  public record ExcelExport(byte[] bytes, String filename, String contentType) {
    public ExcelExport {
      bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  @Transactional(readOnly = true)
  public ListPage list(
      MedmatePrincipal principal,
      String tab,
      String q,
      String sort,
      String order,
      Integer page,
      Integer limit,
      UUID categoryId) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:list:" + principal.pharmacyId(), LIST_LIMIT);

    ListFilter filter =
        buildFilter(principal.pharmacyId(), tab, q, sort, order, page, limit, categoryId);
    Instant now = clock.instant();
    ListResult result = store.list(filter, now);

    List<Map<String, Object>> products = new ArrayList<>(result.rows().size());
    for (PharmacyProduct row : result.rows()) {
      products.add(toListMap(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("products", products);

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("page", filter.page());
    meta.put("limit", filter.limit());
    meta.put("total", result.total());
    meta.put("has_next", (long) filter.page() * filter.limit() < result.total());
    meta.put("tab_counts", result.tabCounts());
    return new ListPage(data, meta);
  }

  @Transactional(readOnly = true)
  public ExcelExport exportExcel(
      MedmatePrincipal principal,
      String tab,
      String q,
      String sort,
      String order,
      UUID categoryId) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:export:" + principal.pharmacyId(), LIST_LIMIT);

    // ponytail: always sync export (even >500 SKUs). Async job_id path lands with bulk_action_job
    // later.
    ListFilter filter =
        buildFilter(principal.pharmacyId(), tab, q, sort, order, 1, 10_000, categoryId);
    List<PharmacyProduct> rows = store.listAllForExport(filter, clock.instant());
    List<Map<String, Object>> maps = new ArrayList<>(rows.size());
    for (PharmacyProduct row : rows) {
      maps.add(toListMap(row));
    }
    byte[] bytes = excelExporter.export(maps);
    return new ExcelExport(
        bytes,
        "inventory-export.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:summary:" + principal.pharmacyId(), SUMMARY_LIMIT);

    SummaryRow row = store.summary(principal.pharmacyId(), clock.instant());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_skus", row.totalSkus());
    data.put("total_units", row.totalUnits());
    data.put("stock_value_at_cost", paiseToRupees(row.stockValueAtCostPaise()));
    data.put("retail_value_mrp", paiseToRupees(row.retailValueMrpPaise()));
    data.put("low_stock_count", row.lowStockCount());
    data.put("expiring_count", row.expiringCount());
    data.put("dead_stock_count", row.deadStockCount());
    data.put("out_of_stock_count", row.outOfStockCount());
    data.put("unallocated_count", row.unallocatedCount());
    data.put("as_of", clock.instant().toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID productId) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:get:" + principal.pharmacyId(), DETAIL_LIMIT);

    PharmacyProduct row =
        store
            .findById(principal.pharmacyId(), productId)
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));
    return toDetailMap(row);
  }

  @Transactional
  public Map<String, Object> patchSettings(
      MedmatePrincipal principal,
      UUID productId,
      Boolean isLooseSellingEnabled,
      Boolean isOnlineVisible,
      Integer reorderLevel,
      String rackLocationCode) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:patch:" + principal.pharmacyId(), PATCH_LIMIT);

    boolean staff = principal.role() == AuthRole.PHARMACY_STAFF;
    if (staff
        && (isOnlineVisible != null || isLooseSellingEnabled != null || reorderLevel != null)) {
      throw new AppException(
          "FORBIDDEN", "Staff cannot change visibility or pricing settings", 403);
    }

    if (reorderLevel != null && reorderLevel < 0) {
      throw new AppException("INVALID_REORDER_LEVEL", "reorder_level must be >= 0", 400);
    }
    if (Boolean.TRUE.equals(isOnlineVisible) && !planPort.growthFeaturesEnabled()) {
      throw new AppException(
          "PLAN_FEATURE_LOCKED", "Online visibility requires Growth plan or higher", 403);
    }
    if (rackLocationCode != null && rackLocationCode.length() > 20) {
      throw new AppException("VALIDATION_ERROR", "rack_location_code max 20 characters", 400);
    }

    PharmacyProduct updated =
        store
            .updateSettings(
                principal.pharmacyId(),
                productId,
                new SettingsPatch(
                    isLooseSellingEnabled, isOnlineVisible, reorderLevel, rackLocationCode),
                clock.instant())
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("is_loose_selling_enabled", updated.isLooseSellingEnabled());
    data.put("is_online_visible", updated.isOnlineVisible());
    data.put("reorder_level", updated.reorderLevel());
    data.put("rack_locations", updated.rackLocations());
    data.put("updated_at", updated.updatedAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> patchDetails(
      MedmatePrincipal principal,
      UUID productId,
      String name,
      String saltComposition,
      String manufacturer,
      Integer packSize,
      String packUnit,
      UUID categoryId,
      String form,
      String schedule,
      String hsnCode,
      BigDecimal gstPct,
      List<String> rackLocations,
      String productPhotoUrl) {
    requirePharmacyOwner(principal);
    rateLimit("pharmacy:inventory:details:" + principal.pharmacyId(), DETAILS_PATCH_LIMIT);

    if (name != null && (name.isBlank() || name.length() > 200)) {
      throw new AppException("VALIDATION_ERROR", "name max 200 characters", 400);
    }
    if (saltComposition != null && saltComposition.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "salt_composition max 500 characters", 400);
    }
    if (manufacturer != null && manufacturer.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "manufacturer max 200 characters", 400);
    }
    if (packSize != null && packSize <= 0) {
      throw new AppException("VALIDATION_ERROR", "pack_size must be > 0", 400);
    }
    if (form != null && !FORMS.contains(form.trim().toUpperCase(Locale.ROOT))) {
      throw new AppException("VALIDATION_ERROR", "Invalid form", 400);
    }
    if (schedule != null && !SCHEDULES.contains(schedule.trim().toUpperCase(Locale.ROOT))) {
      throw new AppException("VALIDATION_ERROR", "Invalid schedule", 400);
    }
    if (hsnCode != null && !hsnCode.matches("\\d{8}")) {
      throw new AppException("INVALID_HSN_CODE", "HSN code must be exactly 8 digits", 400);
    }
    if (gstPct != null) {
      try {
        int slab = gstPct.stripTrailingZeros().intValueExact();
        if (!GST_SLABS.contains(slab)) {
          throw new AppException("INVALID_GST_PCT", "GST percentage not in allowed slabs", 400);
        }
      } catch (ArithmeticException ex) {
        throw new AppException("INVALID_GST_PCT", "GST percentage not in allowed slabs", 400);
      }
    }

    PharmacyProduct updated =
        store
            .updateDetails(
                principal.pharmacyId(),
                productId,
                new DetailsPatch(
                    name,
                    saltComposition,
                    manufacturer,
                    packSize,
                    packUnit,
                    categoryId,
                    form == null ? null : form.trim().toUpperCase(Locale.ROOT),
                    schedule == null ? null : schedule.trim().toUpperCase(Locale.ROOT),
                    hsnCode,
                    gstPct,
                    rackLocations,
                    productPhotoUrl),
                clock.instant())
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("name", updated.name());
    data.put("hsn_code", updated.hsnCode());
    data.put("gst_pct", updated.gstPct());
    data.put("updated_at", updated.updatedAt().toString());
    return data;
  }

  private ListFilter buildFilter(
      UUID pharmacyId,
      String tab,
      String q,
      String sort,
      String order,
      Integer page,
      Integer limit,
      UUID categoryId) {
    String tabKey = tab == null || tab.isBlank() ? "ALL" : tab.trim().toUpperCase(Locale.ROOT);
    if (!TABS.contains(tabKey)) {
      tabKey = "ALL";
    }
    String sortKey = sort == null || sort.isBlank() ? "name" : sort.trim().toLowerCase(Locale.ROOT);
    if (!SORTS.contains(sortKey)) {
      sortKey = "name";
    }
    String ord = order == null || order.isBlank() ? "asc" : order.trim().toLowerCase(Locale.ROOT);
    if (!ord.equals("asc") && !ord.equals("desc")) {
      ord = "asc";
    }
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 20 : Math.min(100, Math.max(1, limit));
    return new ListFilter(pharmacyId, tabKey, q, sortKey, ord, p, l, categoryId);
  }

  private Map<String, Object> toListMap(PharmacyProduct row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", row.id().toString());
    m.put("name", row.name());
    m.put("manufacturer", row.manufacturer());
    m.put("salt_composition", row.saltComposition());
    m.put("form", row.form());
    m.put("pack_size", row.packSize());
    m.put("pack_unit", row.packUnit());
    m.put("mrp", paiseToRupees(row.mrpPaise()));
    m.put("total_stock_units", row.totalStockUnits());
    m.put("total_stock_packs", row.totalStockPacks());
    m.put("reorder_level", row.reorderLevel());
    m.put("earliest_expiry", row.earliestExpiry() == null ? null : row.earliestExpiry().toString());
    m.put("is_rx_only", row.isRxOnly());
    m.put("is_loose_selling_enabled", row.isLooseSellingEnabled());
    m.put("is_online_visible", row.isOnlineVisible());
    m.put("rack_locations", row.rackLocations());
    m.put(
        "flags",
        InventoryFlags.flags(
            row.totalStockUnits(),
            row.reorderLevel(),
            row.earliestExpiry(),
            row.lastMovementAt(),
            clock));
    m.put("cost_value", paiseToRupees(row.costValuePaise()));
    m.put("mrp_value", paiseToRupees(row.mrpValuePaise()));
    return m;
  }

  private Map<String, Object> toDetailMap(PharmacyProduct row) {
    Map<String, Object> m = new LinkedHashMap<>(toListMap(row));
    m.put("category_id", row.categoryId() == null ? null : row.categoryId().toString());
    m.put("category_name", row.categoryName());
    m.put("schedule", row.schedule());
    m.put("hsn_code", row.hsnCode());
    m.put("gst_pct", row.gstPct());
    m.put("margin_pct", marginPct(row.costValuePaise(), row.mrpValuePaise()));
    // ponytail: sales analytics stub until POS (STORY / EPIC-007)
    m.put("units_sold_30d", 0);
    m.put("units_sold_90d", 0);
    m.put("days_of_cover", null);
    m.put("last_sold_at", row.lastMovementAt() == null ? null : row.lastMovementAt().toString());
    m.put("total_batches", row.totalBatches());
    m.put("product_photo_url", row.productPhotoUrl());
    m.put("batches", batchService.mapBatchesForDetail(row.pharmacyId(), row.id()));
    // ponytail: recent_movements UI stub until dedicated movement feed (stock log exists)
    m.put("recent_movements", List.of());
    return m;
  }

  private static BigDecimal marginPct(long costPaise, long mrpValuePaise) {
    if (costPaise <= 0) {
      return null;
    }
    return BigDecimal.valueOf(mrpValuePaise - costPaise)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(costPaise), 1, RoundingMode.HALF_UP);
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requirePharmacyReader(MedmatePrincipal principal) {
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

  private static void requirePharmacyOwner(MedmatePrincipal principal) {
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Pharmacy owner role required", 403);
    }
  }
}
