package com.nammamedmate.catalogue.application;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListFilter;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminMappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingListRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MedicineRef;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.PharmacyListFilter;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MappingService {

  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 60;
  private static final int CREATE_LIMIT = 60;
  private static final int PATCH_LIMIT = 120;
  private static final int DELETE_LIMIT = 30;
  private static final int BULK_LIMIT = 5;
  private static final int MAX_BULK_PHARMACIES = 200;
  private static final Set<String> SORTS =
      Set.of("name", "pharmacy_price", "stock_quantity", "created_at");

  private final MedicineMappingStore store;
  private final MedicineService medicineService;
  private final ObjectProvider<BulkMapJobProcessor> processor;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public MappingService(
      MedicineMappingStore store,
      MedicineService medicineService,
      ObjectProvider<BulkMapJobProcessor> processor,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.medicineService = medicineService;
    this.processor = processor;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record PageResult(Map<String, Object> data, PaginationMeta meta) {
    public PageResult {
      // LinkedHashMap copy: Map.copyOf rejects null values (mrp_ceiling may be null)
      data =
          data == null
              ? Map.of()
              : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
  }

  @Transactional(readOnly = true)
  public PageResult list(
      MedmatePrincipal principal,
      Boolean isVisible,
      Boolean inStock,
      UUID categoryId,
      String search,
      String sort,
      String order,
      Integer page,
      Integer limit) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:catalogue-mapping:list:" + principal.pharmacyId(), LIST_LIMIT);

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

    ListResult result =
        store.listForPharmacy(
            new PharmacyListFilter(
                principal.pharmacyId(),
                isVisible,
                inStock,
                categoryId,
                search,
                sortKey,
                ord,
                p,
                l));

    List<Map<String, Object>> mappings = new ArrayList<>();
    for (MappingListRow row : result.rows()) {
      mappings.add(toPharmacyListMap(row));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", principal.pharmacyId().toString());
    data.put("mappings", mappings);
    return new PageResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      UUID masterMedicineId,
      Object pharmacyPrice,
      Integer stockQuantity) {
    requirePharmacyOwner(principal);
    rateLimit("pharmacy:catalogue-mapping:create:" + principal.pharmacyId(), CREATE_LIMIT);

    requireActivePharmacy(principal.pharmacyId());
    if (masterMedicineId == null) {
      throw new AppException("VALIDATION_ERROR", "master_medicine_id is required", 400);
    }
    if (stockQuantity == null || stockQuantity < 0) {
      throw new AppException("NEGATIVE_STOCK", "stock_quantity must be non-negative", 400);
    }

    MedicineRef medicine =
        store
            .findMedicine(masterMedicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (medicine.banned()) {
      throw new AppException("MEDICINE_IS_BANNED", "Cannot map a banned medicine", 409);
    }
    medicineService.assertOnlineStorefrontAllowed(masterMedicineId);

    long pricePaise = parsePositivePricePaise(pharmacyPrice);
    assertPriceAllowed(pricePaise, medicine);

    if (store.exists(principal.pharmacyId(), masterMedicineId)) {
      throw new AppException(
          "MAPPING_ALREADY_EXISTS", "Pharmacy already has a mapping for this medicine", 409);
    }

    Instant now = clock.instant();
    UUID id = Ids.newId();
    MappingRow row =
        new MappingRow(
            id,
            principal.pharmacyId(),
            masterMedicineId,
            pricePaise,
            stockQuantity,
            true,
            now,
            now);
    try {
      store.insert(row);
    } catch (DuplicateKeyException ex) {
      throw new AppException(
          "MAPPING_ALREADY_EXISTS", "Pharmacy already has a mapping for this medicine", 409);
    }
    store.incrementMappedCount(masterMedicineId, 1);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("mapping_id", id.toString());
    data.put("pharmacy_id", principal.pharmacyId().toString());
    data.put("master_medicine_id", masterMedicineId.toString());
    data.put("medicine_name", medicine.name());
    data.put("pharmacy_price", paiseToRupees(pricePaise));
    data.put("master_mrp", paiseToRupees(medicine.mrpPaise()));
    data.put(
        "mrp_ceiling",
        medicine.mrpCeilingPaise() == null ? null : paiseToRupees(medicine.mrpCeilingPaise()));
    data.put("stock_quantity", stockQuantity);
    data.put("is_visible", true);
    data.put("created_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal,
      UUID mappingId,
      Object pharmacyPrice,
      Integer stockQuantity,
      Boolean isVisible) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:catalogue-mapping:patch:" + principal.pharmacyId(), PATCH_LIMIT);

    MappingRow existing =
        store
            .findById(mappingId)
            .orElseThrow(() -> new AppException("MAPPING_NOT_FOUND", "Mapping not found", 404));
    if (!existing.pharmacyId().equals(principal.pharmacyId())) {
      throw new AppException("FORBIDDEN", "Caller does not own this pharmacy's mapping", 403);
    }

    List<String> updated = new ArrayList<>();
    Long pricePaise = null;
    if (pharmacyPrice != null) {
      long parsed = parsePositivePricePaise(pharmacyPrice);
      MedicineRef medicine =
          store
              .findMedicine(existing.masterMedicineId())
              .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
      assertPriceAllowed(parsed, medicine);
      if (parsed != existing.pharmacyPricePaise()) {
        pricePaise = parsed;
        updated.add("pharmacy_price");
      }
    }
    Integer stock = null;
    if (stockQuantity != null) {
      if (stockQuantity < 0) {
        throw new AppException("NEGATIVE_STOCK", "stock_quantity must be non-negative", 400);
      }
      if (stockQuantity != existing.stockQuantity()) {
        stock = stockQuantity;
        updated.add("stock_quantity");
      }
    }
    Boolean visible = null;
    if (isVisible != null && isVisible != existing.visible()) {
      visible = isVisible;
      updated.add("is_visible");
    }

    Instant now = clock.instant();
    if (!updated.isEmpty()) {
      store.update(mappingId, pricePaise, stock, visible, now);
    }

    MappingRow after = store.findById(mappingId).orElse(existing);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("mapping_id", mappingId.toString());
    data.put("updated_fields", List.copyOf(updated));
    data.put("pharmacy_price", paiseToRupees(after.pharmacyPricePaise()));
    data.put("stock_quantity", after.stockQuantity());
    data.put("is_visible", after.visible());
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> delete(MedmatePrincipal principal, UUID mappingId) {
    requirePharmacyOwner(principal);
    rateLimit("pharmacy:catalogue-mapping:delete:" + principal.pharmacyId(), DELETE_LIMIT);

    MappingRow existing =
        store
            .findById(mappingId)
            .orElseThrow(() -> new AppException("MAPPING_NOT_FOUND", "Mapping not found", 404));
    if (!existing.pharmacyId().equals(principal.pharmacyId())) {
      throw new AppException("FORBIDDEN", "Caller does not own this mapping", 403);
    }

    String medicineName =
        store.findMedicine(existing.masterMedicineId()).map(MedicineRef::name).orElse("Medicine");
    store.delete(mappingId);
    store.incrementMappedCount(existing.masterMedicineId(), -1);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("mapping_id", mappingId.toString());
    data.put("deleted", true);
    data.put("medicine_name", medicineName);
    data.put(
        "message", "Medicine removed from your online store. Physical inventory is not affected.");
    return data;
  }

  @Transactional(readOnly = true)
  public PageResult adminList(
      MedmatePrincipal principal,
      UUID masterId,
      UUID zoneId,
      Boolean isVisible,
      Boolean aboveCeiling,
      Integer page,
      Integer limit) {
    requireAdminReader(principal);
    rateLimit("admin:catalogue:pharmacy-mappings:" + principal.subject(), LIST_LIMIT);

    MedicineRef medicine =
        store
            .findMedicine(masterId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));

    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 20 : Math.min(100, Math.max(1, limit));
    boolean ceilingOnly = Boolean.TRUE.equals(aboveCeiling);

    AdminListResult result =
        store.listForAdmin(new AdminListFilter(masterId, zoneId, isVisible, ceilingOnly, p, l));

    List<Map<String, Object>> pharmacies = new ArrayList<>();
    for (AdminMappingRow row : result.rows()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("mapping_id", row.mappingId().toString());
      m.put("pharmacy_id", row.pharmacyId().toString());
      m.put("pharmacy_name", row.pharmacyName());
      m.put("zone", row.zoneName());
      m.put("pharmacy_price", paiseToRupees(row.pharmacyPricePaise()));
      m.put("stock_quantity", row.stockQuantity());
      m.put("is_visible", row.visible());
      m.put("is_above_ceiling", row.aboveCeiling());
      m.put("created_at", row.createdAt().toString());
      pharmacies.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("master_medicine_id", masterId.toString());
    data.put("medicine_name", medicine.name());
    data.put("master_mrp", paiseToRupees(medicine.mrpPaise()));
    data.put(
        "mrp_ceiling",
        medicine.mrpCeilingPaise() == null ? null : paiseToRupees(medicine.mrpCeilingPaise()));
    data.put("total_pharmacies_stocking", result.totalStocking());
    data.put("pharmacies", pharmacies);
    return new PageResult(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional
  public Map<String, Object> bulkMap(
      MedmatePrincipal principal,
      UUID masterMedicineId,
      List<UUID> pharmacyIds,
      Boolean autoPriceFromMrp,
      Object pharmacyPrice,
      Integer initialStockQuantity) {
    requireBulkRole(principal);
    rateLimit("admin:catalogue:bulk-map:" + principal.subject(), BULK_LIMIT);

    if (pharmacyIds == null || pharmacyIds.isEmpty()) {
      throw new AppException("PHARMACY_IDS_REQUIRED", "pharmacy_ids is required", 400);
    }
    if (pharmacyIds.size() > MAX_BULK_PHARMACIES) {
      throw new AppException("TOO_MANY_PHARMACIES", "Maximum 200 pharmacy IDs per bulk map", 400);
    }
    if (masterMedicineId == null) {
      throw new AppException("VALIDATION_ERROR", "master_medicine_id is required", 400);
    }
    if (autoPriceFromMrp == null) {
      throw new AppException("VALIDATION_ERROR", "auto_price_from_mrp is required", 400);
    }

    MedicineRef medicine =
        store
            .findMedicine(masterMedicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (medicine.banned()) {
      throw new AppException("MEDICINE_IS_BANNED", "Cannot bulk-map a banned medicine", 409);
    }
    medicineService.assertOnlineStorefrontAllowed(masterMedicineId);

    long pricePaise;
    if (Boolean.TRUE.equals(autoPriceFromMrp)) {
      pricePaise = medicine.mrpPaise();
    } else {
      if (pharmacyPrice == null) {
        throw new AppException(
            "VALIDATION_ERROR",
            "pharmacy_price is required when auto_price_from_mrp is false",
            400);
      }
      pricePaise = parsePositivePricePaise(pharmacyPrice);
      assertPriceAllowed(pricePaise, medicine);
    }

    int stock = initialStockQuantity == null ? 0 : initialStockQuantity;
    if (stock < 0) {
      throw new AppException("NEGATIVE_STOCK", "initial_stock_quantity must be non-negative", 400);
    }

    Instant now = clock.instant();
    UUID jobId = Ids.newId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("master_medicine_id", masterMedicineId.toString());
    payload.put("pharmacy_price_paise", pricePaise);
    payload.put("initial_stock_quantity", stock);
    payload.put("auto_price_from_mrp", Boolean.TRUE.equals(autoPriceFromMrp));

    store.insertBulkJob(jobId, List.copyOf(pharmacyIds), payload, principal.subject(), now);
    processor.ifAvailable(p -> p.processJob(jobId));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("job_id", jobId.toString());
    data.put("master_medicine_id", masterMedicineId.toString());
    data.put("medicine_name", medicine.name());
    data.put("total_pharmacies", pharmacyIds.size());
    data.put("status", "QUEUED");
    data.put("estimated_completion_seconds", Math.max(5, pharmacyIds.size() / 3));
    data.put("poll_url", "/api/v1/admin/bulk-jobs/" + jobId);
    return data;
  }

  /** Creates one mapping for bulk job; skips existing. */
  void createForBulk(UUID pharmacyId, UUID medicineId, long pricePaise, int stock) {
    String status =
        store
            .pharmacyStatus(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    if (!"ACTIVE".equals(status)) {
      throw new AppException("PHARMACY_NOT_ACTIVE", "Pharmacy is not ACTIVE", 403);
    }
    if (store.exists(pharmacyId, medicineId)) {
      throw new AppException("MAPPING_ALREADY_EXISTS", "Mapping already exists", 409);
    }
    Instant now = clock.instant();
    store.insert(
        new MappingRow(Ids.newId(), pharmacyId, medicineId, pricePaise, stock, true, now, now));
    store.incrementMappedCount(medicineId, 1);
  }

  private void requireActivePharmacy(UUID pharmacyId) {
    String status =
        store
            .pharmacyStatus(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    if (!"ACTIVE".equals(status)) {
      throw new AppException("PHARMACY_NOT_ACTIVE", "Pharmacy is not in ACTIVE status", 403);
    }
  }

  private static void assertPriceAllowed(long pricePaise, MedicineRef medicine) {
    if (pricePaise > medicine.mrpPaise()) {
      throw new AppException("PRICE_ABOVE_MRP", "pharmacy_price exceeds master MRP", 400);
    }
    if (medicine.mrpCeilingPaise() != null && pricePaise > medicine.mrpCeilingPaise()) {
      throw new AppException(
          "PRICE_ABOVE_CEILING", "pharmacy_price exceeds active MRP ceiling", 400);
    }
  }

  private Map<String, Object> toPharmacyListMap(MappingListRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mapping_id", row.id().toString());
    m.put("master_medicine_id", row.masterMedicineId().toString());
    m.put("name", row.name());
    m.put("salt_composition", row.saltComposition());
    m.put("manufacturer", row.manufacturer());
    m.put("category", Map.of("name", row.categoryName() == null ? "" : row.categoryName()));
    m.put("form", row.form());
    m.put("pack_size", row.packSize());
    m.put("schedule", row.schedule());
    m.put("is_rx_only", row.rxOnly());
    m.put("master_mrp", paiseToRupees(row.masterMrpPaise()));
    m.put(
        "mrp_ceiling", row.mrpCeilingPaise() == null ? null : paiseToRupees(row.mrpCeilingPaise()));
    m.put("pharmacy_price", paiseToRupees(row.pharmacyPricePaise()));
    m.put("stock_quantity", row.stockQuantity());
    m.put("is_visible", row.visible());
    m.put("created_at", row.createdAt().toString());
    m.put("updated_at", row.updatedAt().toString());
    return m;
  }

  static long parsePositivePricePaise(Object amount) {
    if (amount == null) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_price is required", 400);
    }
    BigDecimal value;
    if (amount instanceof BigDecimal bd) {
      value = bd;
    } else if (amount instanceof Number n) {
      value = BigDecimal.valueOf(n.doubleValue());
    } else if (amount instanceof String s) {
      try {
        value = new BigDecimal(s.trim());
      } catch (NumberFormatException ex) {
        throw new AppException("VALIDATION_ERROR", "pharmacy_price must be a positive number", 400);
      }
    } else {
      throw new AppException("VALIDATION_ERROR", "pharmacy_price must be a positive number", 400);
    }
    if (value.scale() > 2) {
      throw new AppException(
          "VALIDATION_ERROR", "pharmacy_price may have at most 2 decimal places", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "pharmacy_price must be positive", 400);
    }
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
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

  private static void requireAdminReader(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Caller not an admin role", 403);
    }
  }

  private static void requireBulkRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException("FORBIDDEN", "Only admin_super or admin_operations may bulk-map", 403);
    }
  }
}
