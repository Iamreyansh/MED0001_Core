package com.nammamedmate.inventory.application;

import com.nammamedmate.inventory.adapter.out.export.SimplePdfExporter;
import com.nammamedmate.inventory.adapter.out.export.SimpleXlsxExporter;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.AdjustmentLogRow;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryAlertRow;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryReportRow;
import com.nammamedmate.inventory.domain.ExpiryStatus;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.ProductBatch;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryBatchService {

  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 120;
  private static final int MUTATE_LIMIT = 30;
  private static final int WRITE_OFF_LIMIT = 10;
  private static final int ALERTS_LIMIT = 30;
  private static final int REPORT_LIMIT = 10;
  private static final Set<String> ADJUST_REASONS =
      Set.of("DAMAGE", "RETURN", "AUDIT_CORRECTION", "EXPIRY_WRITE_OFF");
  private static final Set<String> WRITE_OFF_REASONS = Set.of("EXPIRED", "DAMAGED", "REGULATORY");
  private static final String[] REPORT_HEADERS = {
    "product_name",
    "batch_number",
    "expiry_date",
    "days_to_expiry",
    "quantity_current",
    "purchase_price_per_unit",
    "value_at_risk",
    "rack_location"
  };

  private final ProductBatchStore batchStore;
  private final PharmacyProductStore productStore;
  private final SimpleXlsxExporter xlsxExporter;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public InventoryBatchService(
      ProductBatchStore batchStore,
      PharmacyProductStore productStore,
      SimpleXlsxExporter xlsxExporter,
      RateLimiter rateLimiter,
      Clock clock) {
    this.batchStore = batchStore;
    this.productStore = productStore;
    this.xlsxExporter = xlsxExporter;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record FileExport(byte[] bytes, String filename, String contentType) {
    public FileExport {
      bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> listBatches(
      MedmatePrincipal principal, UUID productId, boolean includeInactive) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:batches:list:" + principal.pharmacyId(), LIST_LIMIT);

    PharmacyProduct product =
        productStore
            .findById(principal.pharmacyId(), productId)
            .orElseThrow(() -> new AppException("PRODUCT_NOT_FOUND", "Product not found", 404));

    LocalDate today = today();
    List<ProductBatch> batches =
        batchStore.listByProduct(principal.pharmacyId(), productId, includeInactive);
    int totalActive = 0;
    List<Map<String, Object>> mapped = new ArrayList<>(batches.size());
    for (ProductBatch b : batches) {
      if (b.isActive()) {
        totalActive += b.quantityCurrent();
      }
      mapped.add(toBatchMap(b, today));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("product_id", product.id().toString());
    data.put("product_name", product.name());
    data.put("batches", mapped);
    data.put("total_active_units", totalActive);
    return data;
  }

  @Transactional
  public Map<String, Object> addBatch(
      MedmatePrincipal principal,
      UUID productId,
      String batchNumber,
      LocalDate expiryDate,
      LocalDate manufacturedDate,
      Integer quantity,
      Integer freeQuantity,
      BigDecimal purchasePricePerUnit,
      BigDecimal mrpPerUnit) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:batches:add:" + principal.pharmacyId(), MUTATE_LIMIT);

    if (!productStore.findById(principal.pharmacyId(), productId).isPresent()) {
      throw new AppException("PRODUCT_NOT_FOUND", "Product not found", 404);
    }
    if (batchNumber == null || batchNumber.isBlank() || batchNumber.length() > 50) {
      throw new AppException("VALIDATION_ERROR", "batch_number required (max 50)", 400);
    }
    LocalDate today = today();
    if (expiryDate == null || expiryDate.isBefore(today)) {
      throw new AppException("EXPIRY_DATE_IN_PAST", "expiry_date must be today or later", 400);
    }
    if (quantity == null || quantity <= 0) {
      throw new AppException("VALIDATION_ERROR", "quantity must be > 0", 400);
    }
    int free = freeQuantity == null ? 0 : freeQuantity;
    if (free < 0) {
      throw new AppException("VALIDATION_ERROR", "free_quantity must be >= 0", 400);
    }
    long purchasePaise = rupeesToPaise(purchasePricePerUnit, "purchase_price_per_unit");
    long mrpPaise = rupeesToPaise(mrpPerUnit, "mrp_per_unit");

    Instant now = clock.instant();
    String number = batchNumber.trim();
    var existing = batchStore.findByBatchNumber(principal.pharmacyId(), productId, number);
    if (existing.isPresent()) {
      ProductBatch cur = existing.get();
      int received = cur.quantityReceived() + quantity + free;
      int current = cur.quantityCurrent() + quantity + free;
      ProductBatch updated = batchStore.updateQuantities(cur.id(), received, current, true, now);
      batchStore.insertStockMovement(
          UUID.randomUUID(),
          principal.pharmacyId(),
          productId,
          cur.id(),
          "RECEIPT",
          quantity + free,
          "TOP_UP",
          principal.subject(),
          now);
      batchStore.refreshProductDenorm(principal.pharmacyId(), productId, now);
      Map<String, Object> data = toCreatedMap(updated);
      data.put("topped_up", true);
      return data;
    }

    int received = quantity + free;
    ProductBatch created =
        new ProductBatch(
            UUID.randomUUID(),
            productId,
            principal.pharmacyId(),
            number,
            expiryDate,
            manufacturedDate,
            received,
            received,
            purchasePaise,
            mrpPaise,
            true,
            null,
            null,
            null,
            now,
            now);
    batchStore.insert(created);
    batchStore.insertStockMovement(
        UUID.randomUUID(),
        principal.pharmacyId(),
        productId,
        created.id(),
        "RECEIPT",
        received,
        free > 0 ? "RECEIPT_WITH_FREE" : "RECEIPT",
        principal.subject(),
        now);
    batchStore.refreshProductDenorm(principal.pharmacyId(), productId, now);
    Map<String, Object> data = toCreatedMap(created);
    data.put("topped_up", false);
    return data;
  }

  @Transactional
  public Map<String, Object> adjustBatch(
      MedmatePrincipal principal, UUID productId, UUID batchId, Integer adjustment, String reason) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:batches:adjust:" + principal.pharmacyId(), MUTATE_LIMIT);

    if (reason == null || reason.isBlank()) {
      throw new AppException("MISSING_REASON", "reason is required", 400);
    }
    String reasonKey = reason.trim().toUpperCase(Locale.ROOT);
    if (!ADJUST_REASONS.contains(reasonKey)) {
      throw new AppException("VALIDATION_ERROR", "Invalid adjustment reason", 400);
    }
    if (adjustment == null || adjustment == 0) {
      throw new AppException("VALIDATION_ERROR", "adjustment must be non-zero", 400);
    }

    ProductBatch batch =
        batchStore
            .findById(principal.pharmacyId(), productId, batchId)
            .orElseThrow(() -> new AppException("BATCH_NOT_FOUND", "Batch not found", 404));
    if (!batch.isActive()) {
      throw new AppException("BATCH_INACTIVE", "Batch is archived", 400);
    }
    int before = batch.quantityCurrent();
    int after = before + adjustment;
    if (after < 0) {
      throw new AppException(
          "INSUFFICIENT_BATCH_QUANTITY", "Adjustment would make quantity negative", 400);
    }

    Instant now = clock.instant();
    boolean active = after > 0;
    ProductBatch updated =
        batchStore.updateQuantities(batch.id(), batch.quantityReceived(), after, active, now);
    UUID logId = UUID.randomUUID();
    batchStore.insertAdjustmentLog(
        new AdjustmentLogRow(
            logId,
            batch.id(),
            principal.pharmacyId(),
            principal.subject(),
            adjustment,
            reasonKey,
            before,
            after,
            now));
    batchStore.insertStockMovement(
        UUID.randomUUID(),
        principal.pharmacyId(),
        productId,
        batch.id(),
        "ADJUSTMENT",
        adjustment,
        reasonKey,
        principal.subject(),
        now);
    batchStore.refreshProductDenorm(principal.pharmacyId(), productId, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("batch_id", updated.id().toString());
    data.put("batch_number", updated.batchNumber());
    data.put("before_qty", before);
    data.put("adjustment", adjustment);
    data.put("after_qty", after);
    data.put("reason", reasonKey);
    data.put("adjusted_by", principal.subject().toString());
    data.put("adjusted_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> writeOffBatch(
      MedmatePrincipal principal,
      UUID productId,
      UUID batchId,
      String writeOffReason,
      String notes) {
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException(
          "STAFF_CANNOT_WRITE_OFF", "Only pharmacy_owner may write off batches", 403);
    }
    rateLimit("pharmacy:inventory:batches:writeoff:" + principal.pharmacyId(), WRITE_OFF_LIMIT);

    if (writeOffReason == null || writeOffReason.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "write_off_reason is required", 400);
    }
    String reasonKey = writeOffReason.trim().toUpperCase(Locale.ROOT);
    if (!WRITE_OFF_REASONS.contains(reasonKey)) {
      throw new AppException("VALIDATION_ERROR", "Invalid write_off_reason", 400);
    }
    if (notes != null && notes.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "notes max 500 characters", 400);
    }

    ProductBatch batch =
        batchStore
            .findById(principal.pharmacyId(), productId, batchId)
            .orElseThrow(() -> new AppException("BATCH_NOT_FOUND", "Batch not found", 404));
    if (!batch.isActive()) {
      throw new AppException("BATCH_ALREADY_INACTIVE", "Batch already written off", 400);
    }

    int units = batch.quantityCurrent();
    long valuePaise = Math.multiplyExact((long) units, batch.purchasePricePaise());
    Instant now = clock.instant();
    ProductBatch updated = batchStore.writeOff(batch.id(), reasonKey, notes, now);
    batchStore.insertAdjustmentLog(
        new AdjustmentLogRow(
            UUID.randomUUID(),
            batch.id(),
            principal.pharmacyId(),
            principal.subject(),
            -units,
            "EXPIRY_WRITE_OFF",
            units,
            0,
            now));
    batchStore.insertStockMovement(
        UUID.randomUUID(),
        principal.pharmacyId(),
        productId,
        batch.id(),
        "WRITE_OFF",
        -units,
        reasonKey,
        principal.subject(),
        now);
    batchStore.refreshProductDenorm(principal.pharmacyId(), productId, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("batch_id", updated.id().toString());
    data.put("batch_number", updated.batchNumber());
    data.put("units_written_off", units);
    data.put("value_written_off", paiseToRupees(valuePaise));
    data.put("is_active", false);
    data.put("written_off_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> expiryAlerts(MedmatePrincipal principal) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:inventory:expiry-alerts:" + principal.pharmacyId(), ALERTS_LIMIT);

    LocalDate today = today();
    List<ExpiryAlertRow> rows =
        batchStore.listExpiringWithinMonths(principal.pharmacyId(), 4, today);

    Map<String, List<Map<String, Object>>> bucketItems = new LinkedHashMap<>();
    bucketItems.put("UNDER_1_MONTH", new ArrayList<>());
    bucketItems.put("1_TO_2_MONTHS", new ArrayList<>());
    bucketItems.put("2_TO_4_MONTHS", new ArrayList<>());
    Map<String, Set<UUID>> bucketProducts = new LinkedHashMap<>();
    bucketProducts.put("UNDER_1_MONTH", new HashSet<>());
    bucketProducts.put("1_TO_2_MONTHS", new HashSet<>());
    bucketProducts.put("2_TO_4_MONTHS", new HashSet<>());
    Map<String, Integer> bucketUnits = new LinkedHashMap<>();
    Map<String, Long> bucketVar = new LinkedHashMap<>();
    for (String b : bucketItems.keySet()) {
      bucketUnits.put(b, 0);
      bucketVar.put(b, 0L);
    }

    long totalUnits = 0;
    long totalVar = 0;
    Set<UUID> allProducts = new HashSet<>();

    for (ExpiryAlertRow row : rows) {
      String bucket = ExpiryStatus.alertBucket(row.expiryDate(), today);
      if (bucket == null) {
        continue;
      }
      long varPaise = Math.multiplyExact((long) row.quantityCurrent(), row.purchasePricePaise());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("product_id", row.productId().toString());
      item.put("product_name", row.productName());
      item.put("batch_number", row.batchNumber());
      item.put("expiry_date", row.expiryDate().toString());
      item.put("days_to_expiry", ExpiryStatus.daysToExpiry(row.expiryDate(), today));
      item.put("quantity_current", row.quantityCurrent());
      item.put("purchase_price_per_unit", paiseToRupees(row.purchasePricePaise()));
      item.put("value_at_risk", paiseToRupees(varPaise));
      bucketItems.get(bucket).add(item);
      bucketProducts.get(bucket).add(row.productId());
      bucketUnits.put(bucket, bucketUnits.get(bucket) + row.quantityCurrent());
      bucketVar.put(bucket, bucketVar.get(bucket) + varPaise);
      allProducts.add(row.productId());
      totalUnits += row.quantityCurrent();
      totalVar += varPaise;
    }

    List<Map<String, Object>> groups = new ArrayList<>(3);
    groups.add(
        group(
            "UNDER_1_MONTH",
            "Expiring in < 1 month",
            bucketProducts,
            bucketUnits,
            bucketVar,
            bucketItems));
    groups.add(
        group(
            "1_TO_2_MONTHS",
            "Expiring in 1-2 months",
            bucketProducts,
            bucketUnits,
            bucketVar,
            bucketItems));
    groups.add(
        group(
            "2_TO_4_MONTHS",
            "Expiring in 2-4 months",
            bucketProducts,
            bucketUnits,
            bucketVar,
            bucketItems));

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total_expiring_products", allProducts.size());
    summary.put("total_expiring_units", totalUnits);
    summary.put("total_value_at_risk", paiseToRupees(totalVar));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("summary", summary);
    data.put("groups", groups);
    return data;
  }

  @Transactional(readOnly = true)
  public Object expiryReport(MedmatePrincipal principal, Integer withinMonths, String export) {
    requirePharmacyOwner(principal);
    rateLimit("pharmacy:inventory:expiry-report:" + principal.pharmacyId(), REPORT_LIMIT);

    int months = withinMonths == null ? 4 : withinMonths;
    if (months < 1 || months > 24) {
      throw new AppException("VALIDATION_ERROR", "within_months must be 1-24", 400);
    }
    String kind =
        export == null || export.isBlank() ? "JSON" : export.trim().toUpperCase(Locale.ROOT);
    LocalDate today = today();
    List<ExpiryReportRow> rows = batchStore.listExpiryReport(principal.pharmacyId(), months, today);

    List<Map<String, Object>> batches = new ArrayList<>(rows.size());
    long totalVar = 0;
    for (ExpiryReportRow row : rows) {
      long varPaise = Math.multiplyExact((long) row.quantityCurrent(), row.purchasePricePaise());
      totalVar += varPaise;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("product_name", row.productName());
      m.put("batch_number", row.batchNumber());
      m.put("expiry_date", row.expiryDate().toString());
      m.put("days_to_expiry", ExpiryStatus.daysToExpiry(row.expiryDate(), today));
      m.put("quantity_current", row.quantityCurrent());
      m.put("purchase_price_per_unit", paiseToRupees(row.purchasePricePaise()));
      m.put("value_at_risk", paiseToRupees(varPaise));
      m.put("rack_location", row.rackLocation());
      batches.add(m);
    }

    if ("EXCEL".equals(kind)) {
      byte[] bytes = xlsxExporter.exportSheet("Expiry", REPORT_HEADERS, batches);
      return new FileExport(
          bytes,
          "expiry-report.xlsx",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }
    if ("PDF".equals(kind)) {
      byte[] bytes = SimplePdfExporter.export("Expiry Report (" + months + " months)", batches);
      return new FileExport(bytes, "expiry-report.pdf", "application/pdf");
    }
    if (!"JSON".equals(kind)) {
      throw new AppException("VALIDATION_ERROR", "export must be JSON, EXCEL, or PDF", 400);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("report_date", today.toString());
    data.put("scope_months", months);
    data.put("total_batches", batches.size());
    data.put("total_value_at_risk", paiseToRupees(totalVar));
    data.put("batches", batches);
    return data;
  }

  List<Map<String, Object>> mapBatchesForDetail(UUID pharmacyId, UUID productId) {
    LocalDate today = today();
    List<ProductBatch> batches = batchStore.listByProduct(pharmacyId, productId, true);
    List<Map<String, Object>> out = new ArrayList<>(batches.size());
    for (ProductBatch b : batches) {
      out.add(toBatchMap(b, today));
    }
    return out;
  }

  private static Map<String, Object> group(
      String bucket,
      String label,
      Map<String, Set<UUID>> products,
      Map<String, Integer> units,
      Map<String, Long> varPaise,
      Map<String, List<Map<String, Object>>> items) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("bucket", bucket);
    g.put("label", label);
    g.put("product_count", products.get(bucket).size());
    g.put("units", units.get(bucket));
    g.put("value_at_risk", paiseToRupees(varPaise.get(bucket)));
    g.put("items", items.get(bucket));
    return g;
  }

  private Map<String, Object> toBatchMap(ProductBatch b, LocalDate today) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", b.id().toString());
    m.put("batch_number", b.batchNumber());
    m.put("expiry_date", b.expiryDate().toString());
    m.put(
        "manufactured_date", b.manufacturedDate() == null ? null : b.manufacturedDate().toString());
    m.put("quantity_current", b.quantityCurrent());
    m.put("quantity_received", b.quantityReceived());
    m.put("purchase_price_per_unit", paiseToRupees(b.purchasePricePaise()));
    m.put("mrp_per_unit", paiseToRupees(b.mrpPaise()));
    m.put("is_active", b.isActive());
    m.put("days_to_expiry", ExpiryStatus.daysToExpiry(b.expiryDate(), today));
    m.put("expiry_status", ExpiryStatus.of(b.expiryDate(), today));
    m.put("received_date", b.createdAt() == null ? null : b.createdAt().toString());
    return m;
  }

  private static Map<String, Object> toCreatedMap(ProductBatch b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", b.id().toString());
    m.put("batch_number", b.batchNumber());
    m.put("expiry_date", b.expiryDate().toString());
    m.put("quantity_received", b.quantityReceived());
    m.put("quantity_current", b.quantityCurrent());
    m.put("purchase_price_per_unit", paiseToRupees(b.purchasePricePaise()));
    m.put("mrp_per_unit", paiseToRupees(b.mrpPaise()));
    m.put("is_active", b.isActive());
    m.put("created_at", b.createdAt().toString());
    return m;
  }

  private LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  static long rupeesToPaise(BigDecimal value, String field) {
    if (value == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    if (value.scale() > 2) {
      throw new AppException("VALIDATION_ERROR", field + " may have at most 2 decimal places", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      if ("mrp_per_unit".equals(field)) {
        throw new AppException("INVALID_MRP", "mrp_per_unit must be > 0", 400);
      }
      throw new AppException("VALIDATION_ERROR", field + " must be > 0", 400);
    }
    return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
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
