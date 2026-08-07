package com.nammamedmate.catalogue.application;

import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore.AuditLogRecord;
import com.nammamedmate.catalogue.application.port.out.BanMappingHidePort;
import com.nammamedmate.catalogue.application.port.out.MedicineBanJobStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListFilter;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminMappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.ListFilter;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.SubstituteRef;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.SummaryStats;
import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MedicineService {

  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 60;
  private static final int MUTATE_LIMIT = 30;
  private static final int BAN_LIMIT = 10;
  private static final Set<String> FORMS =
      Set.of(
          "TABLET",
          "CAPSULE",
          "SYRUP",
          "INJECTION",
          "OINTMENT",
          "DROPS",
          "INHALER",
          "PATCH",
          "POWDER",
          "SUPPOSITORY",
          "OTHER");
  private static final Set<String> PACK_UNITS =
      Set.of(
          "TABLET", "CAPSULE", "ML", "MG", "G", "STRIP", "VIAL", "AMPOULE", "SACHET", "TUBE",
          "BOTTLE");
  private static final Set<String> SCHEDULES = Set.of("OTC", "H", "H1", "X");
  private static final Set<Integer> GST_RATES = Set.of(5, 12, 18);
  private static final Set<String> SORTS =
      Set.of("name", "monthly_demand", "mapped_pharmacy_count", "mrp", "created_at");

  private final MedicineStore store;
  private final AuditLogStore auditLog;
  private final BanMappingHidePort banHide;
  private final MedicineBanJobStore banJobs;
  private final MedicineMappingStore mappings;
  private final OrderDemandPort orderDemand;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public MedicineService(
      MedicineStore store,
      AuditLogStore auditLog,
      BanMappingHidePort banHide,
      MedicineBanJobStore banJobs,
      MedicineMappingStore mappings,
      OrderDemandPort orderDemand,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.auditLog = auditLog;
    this.banHide = banHide;
    this.banJobs = banJobs;
    this.mappings = mappings;
    this.orderDemand = orderDemand;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record PageResult(Map<String, Object> data, PaginationMeta meta) {
    public PageResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public PageResult list(
      MedmatePrincipal principal,
      UUID categoryId,
      String schedule,
      Integer gstPct,
      Boolean isRxOnly,
      Boolean isBanned,
      String search,
      String sort,
      String order,
      Integer page,
      Integer limit) {
    requireAdminReader(principal);
    rateLimit("admin:catalogue:list:" + principal.subject(), LIST_LIMIT);

    String sched = schedule == null || schedule.isBlank() ? null : requireSchedule(schedule);
    Integer gst = gstPct;
    if (gst != null) {
      requireGst(gst);
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
    boolean bannedOnly = Boolean.TRUE.equals(isBanned);

    ListResult result =
        store.list(
            new ListFilter(
                categoryId, sched, gst, isRxOnly, bannedOnly, search, sortKey, ord, p, l));

    List<Map<String, Object>> medicines = new ArrayList<>();
    for (MedicineRow row : result.rows()) {
      medicines.add(toListMap(row));
    }
    return new PageResult(Map.of("medicines", medicines), PaginationMeta.of(p, l, result.total()));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal) {
    requireAdminReader(principal);
    rateLimit("admin:catalogue:summary:" + principal.subject(), LIST_LIMIT);
    Instant now = clock.instant();
    SummaryStats s = store.summary(now);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total_skus", s.totalSkus());
    data.put("category_count", s.categoryCount());
    data.put("rx_only_count", s.rxOnlyCount());
    data.put("otc_count", s.otcCount());
    data.put("banned_count", s.bannedCount());
    data.put("schedule_h_count", s.scheduleHCount());
    data.put("schedule_h1_count", s.scheduleH1Count());
    data.put("schedule_x_count", s.scheduleXCount());
    data.put("avg_mrp", s.avgMrpPaise() == null ? BigDecimal.ZERO : paiseToRupees(s.avgMrpPaise()));
    data.put("total_pharmacy_mappings", s.totalPharmacyMappings());
    data.put("data_as_of", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    requireCreateRole(principal);
    rateLimit("admin:catalogue:create:" + principal.subject(), MUTATE_LIMIT);

    String name = requireName(cmd.name());
    String salt = requireSalt(cmd.saltComposition());
    String manufacturer = requireManufacturer(cmd.manufacturer());
    UUID categoryId = requireCategoryId(cmd.categoryId());
    String form = requireForm(cmd.form());
    BigDecimal packSize = requirePackSize(cmd.packSize());
    String packUnit = requirePackUnit(cmd.packUnit());
    String schedule = requireSchedule(cmd.schedule());
    String hsn = requireHsn(cmd.hsnCode());
    int gst = requireGst(cmd.gstPct());
    long mrpPaise = parsePositiveAmountPaise(cmd.mrp());
    boolean rxOnly = Boolean.TRUE.equals(cmd.isRxOnly()) || forcesRx(schedule);
    String description = optionalDescription(cmd.description());
    List<UUID> substitutes = requireSubstitutes(cmd.substitutes());

    Instant now = clock.instant();
    UUID id = Ids.newId();
    MedicineRow row =
        new MedicineRow(
            id,
            name,
            salt,
            manufacturer,
            categoryId,
            null,
            form,
            packSize,
            packUnit,
            schedule,
            hsn,
            gst,
            mrpPaise,
            null,
            rxOnly,
            false,
            null,
            0,
            0,
            substitutes,
            description,
            principal.subject(),
            now,
            now);
    try {
      store.insert(row);
    } catch (DuplicateKeyException ex) {
      throw new AppException(
          "DUPLICATE_MEDICINE",
          "salt_composition + manufacturer + form + pack_size + pack_unit already exists",
          409);
    }

    audit(
        principal,
        id,
        "MEDICINE_CREATED",
        Map.of(
            "name", name,
            "schedule", schedule,
            "hsn_code", hsn,
            "gst_pct", gst,
            "mrp_paise", mrpPaise));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", id.toString());
    data.put("name", name);
    data.put("salt_composition", salt);
    data.put("manufacturer", manufacturer);
    data.put("category_id", categoryId.toString());
    data.put("form", form);
    data.put("pack_size", packSize);
    data.put("pack_unit", packUnit);
    data.put("schedule", schedule);
    data.put("hsn_code", hsn);
    data.put("gst_pct", gst);
    data.put("mrp", paiseToRupees(mrpPaise));
    data.put("is_rx_only", rxOnly);
    data.put("is_banned", false);
    data.put("monthly_demand", 0);
    data.put("mapped_pharmacy_count", 0);
    data.put("substitutes", substitutes.stream().map(UUID::toString).toList());
    data.put("created_by", principal.subject().toString());
    data.put("created_at", now.toString());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requireAdminReader(principal);
    rateLimit("admin:catalogue:get:" + principal.subject(), LIST_LIMIT);
    MedicineRow row =
        store
            .findById(id)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    return toDetailMap(row);
  }

  @Transactional
  public Map<String, Object> update(MedmatePrincipal principal, UUID id, UpdateCommand cmd) {
    requireUpdateRole(principal);
    rateLimit("admin:catalogue:update:" + principal.subject(), MUTATE_LIMIT);

    MedicineRow existing =
        store
            .findById(id)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (existing.banned()) {
      throw new AppException(
          "MEDICINE_IS_BANNED", "Cannot update a banned medicine; unban first", 409);
    }

    if (principal.role() == AuthRole.ADMIN_COMPLIANCE) {
      boolean nonSchedule =
          cmd.name() != null
              || cmd.description() != null
              || cmd.categoryId() != null
              || cmd.gstPct() != null
              || cmd.mrp() != null
              || cmd.isRxOnly() != null
              || cmd.substitutes() != null;
      if (nonSchedule) {
        throw new AppException(
            "FORBIDDEN", "admin_compliance may only update schedule classification", 403);
      }
      if (cmd.schedule() == null) {
        throw new AppException(
            "VALIDATION_ERROR", "schedule is required for compliance update", 400);
      }
    }

    List<String> updated = new ArrayList<>();
    String name = null;
    if (cmd.name() != null) {
      name = requireName(cmd.name());
      if (!name.equals(existing.name())) {
        updated.add("name");
      } else {
        name = null;
      }
    }
    String description = null;
    boolean clearDescription = false;
    if (cmd.description() != null) {
      description = optionalDescription(cmd.description());
      String existingDesc = existing.description() == null ? "" : existing.description();
      String newDesc = description == null ? "" : description;
      if (!newDesc.equals(existingDesc)) {
        updated.add("description");
        if (description == null) {
          clearDescription = true;
          description = "";
        }
      } else {
        description = null;
      }
    }
    UUID categoryId = null;
    if (cmd.categoryId() != null) {
      categoryId = requireCategoryId(cmd.categoryId());
      if (!categoryId.equals(existing.categoryId())) {
        updated.add("category_id");
      } else {
        categoryId = null;
      }
    }
    String schedule = null;
    if (cmd.schedule() != null) {
      schedule = requireSchedule(cmd.schedule());
      if (!schedule.equals(existing.schedule())) {
        updated.add("schedule");
      } else {
        schedule = null;
      }
    }
    Integer gst = null;
    if (cmd.gstPct() != null) {
      gst = requireGst(cmd.gstPct());
      if (gst != existing.gstPct()) {
        updated.add("gst_pct");
      } else {
        gst = null;
      }
    }
    Long mrpPaise = null;
    if (cmd.mrp() != null) {
      long parsed = parsePositiveAmountPaise(cmd.mrp());
      if (existing.mrpCeilingPaise() != null && parsed < existing.mrpCeilingPaise()) {
        throw new AppException(
            "MRP_BELOW_CEILING", "New MRP is below the existing price ceiling", 400);
      }
      if (parsed != existing.mrpPaise()) {
        mrpPaise = parsed;
        updated.add("mrp");
      }
    }
    Boolean rxOnly = null;
    String effectiveSchedule = schedule != null ? schedule : existing.schedule();
    if (cmd.isRxOnly() != null || schedule != null) {
      boolean desired = existing.rxOnly();
      if (cmd.isRxOnly() != null) {
        desired = Boolean.TRUE.equals(cmd.isRxOnly());
      }
      if (forcesRx(effectiveSchedule)) {
        desired = true;
      }
      if (desired != existing.rxOnly()) {
        rxOnly = desired;
        updated.add("is_rx_only");
      }
    }
    List<UUID> substitutes = null;
    if (cmd.substitutes() != null) {
      substitutes = requireSubstitutes(cmd.substitutes());
      if (!substitutes.equals(existing.substitutes())) {
        updated.add("substitutes");
      } else {
        substitutes = null;
      }
    }

    Instant now = clock.instant();
    if (!updated.isEmpty()) {
      store.update(
          id, name, description, categoryId, schedule, gst, mrpPaise, rxOnly, substitutes, now);
      Map<String, Object> before = new LinkedHashMap<>();
      Map<String, Object> after = new LinkedHashMap<>();
      for (String field : updated) {
        before.put(field, fieldValue(existing, field));
        Object afterVal = null;
        if ("name".equals(field)) {
          afterVal = name;
        }
        if ("description".equals(field)) {
          afterVal = clearDescription ? null : description;
        }
        if ("category_id".equals(field)) {
          afterVal = categoryId.toString();
        }
        if ("schedule".equals(field)) {
          afterVal = schedule;
        }
        if ("gst_pct".equals(field)) {
          afterVal = gst;
        }
        if ("mrp".equals(field)) {
          afterVal = mrpPaise;
        }
        if ("is_rx_only".equals(field)) {
          afterVal = rxOnly;
        }
        if ("substitutes".equals(field)) {
          afterVal = substitutes.stream().map(UUID::toString).toList();
        }
        after.put(field, afterVal);
      }
      audit(
          principal,
          id,
          "MEDICINE_UPDATED",
          Map.of("updated_fields", updated, "before", before, "after", after));
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", id.toString());
    data.put("updated_fields", List.copyOf(updated));
    data.put("updated_at", now.toString());
    return data;
  }

  @Transactional
  public Map<String, Object> ban(MedmatePrincipal principal, UUID id, String reason) {
    requireBanRole(principal);
    rateLimit("admin:catalogue:ban:" + principal.subject(), BAN_LIMIT);

    MedicineRow existing =
        store
            .findById(id)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (existing.banned()) {
      throw new AppException("ALREADY_BANNED", "Medicine is already banned", 409);
    }
    String trimmed = requireBanReason(reason);
    Instant now = clock.instant();
    store.setBanned(id, true, trimmed, now);
    UUID jobId = Ids.newId();
    banJobs.insertQueued(jobId, id, trimmed, principal.subject(), now);
    banJobs.markRunning(jobId, now);
    int hidden = banHide.hideAllForMedicine(id);
    Instant done = clock.instant();
    banJobs.markCompleted(jobId, hidden, done);
    audit(
        principal,
        id,
        "MEDICINE_BANNED",
        Map.of("reason", trimmed, "pharmacy_mappings_hidden", hidden, "job_id", jobId.toString()));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", id.toString());
    data.put("is_banned", true);
    data.put("ban_reason", trimmed);
    data.put("banned_at", now.toString());
    data.put("pharmacy_mappings_hidden", hidden);
    data.put("storefront_removal_job_id", jobId.toString());
    data.put("storefront_removal_job_status", "COMPLETED");
    return data;
  }

  @Transactional
  public Map<String, Object> unban(MedmatePrincipal principal, UUID id, String reason) {
    requireBanRole(principal);
    rateLimit("admin:catalogue:unban:" + principal.subject(), BAN_LIMIT);

    MedicineRow existing =
        store
            .findById(id)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (!existing.banned()) {
      throw new AppException("VALIDATION_ERROR", "Medicine is not banned", 400);
    }
    String trimmed = requireBanReason(reason);
    Instant now = clock.instant();
    store.setBanned(id, false, null, now);
    audit(principal, id, "MEDICINE_UNBANNED", Map.of("reason", trimmed));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", id.toString());
    data.put("is_banned", false);
    data.put("unbanned_at", now.toString());
    data.put(
        "note",
        "Pharmacy mappings remain hidden. Pharmacies must manually re-enable items on their storefront.");
    return data;
  }

  /** Used by pharmacy mapping (STORY-005). Schedule X cannot be added to online storefronts. */
  public void assertOnlineStorefrontAllowed(UUID medicineId) {
    MedicineRow row =
        store
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if ("X".equals(row.schedule())) {
      throw new AppException(
          "SCHEDULE_X_NOT_AVAILABLE_ONLINE",
          "Schedule X medicines are not available for online storefront",
          409);
    }
  }

  @Transactional
  public void refreshMonthlyDemand() {
    Instant now = clock.instant();
    for (UUID id : store.listAllIds()) {
      store.updateMonthlyDemand(id, orderDemand.trailing30DayOrderCount(id), now);
    }
  }

  private Map<String, Object> toListMap(MedicineRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", row.id().toString());
    m.put("name", row.name());
    m.put("salt_composition", row.saltComposition());
    m.put("manufacturer", row.manufacturer());
    Map<String, Object> category = new LinkedHashMap<>();
    category.put("category_id", row.categoryId().toString());
    category.put("name", row.categoryName());
    m.put("category", category);
    m.put("form", row.form());
    m.put("pack_size", row.packSize());
    m.put("pack_unit", row.packUnit());
    m.put("schedule", row.schedule());
    m.put("hsn_code", row.hsnCode());
    m.put("gst_pct", row.gstPct());
    m.put("mrp", paiseToRupees(row.mrpPaise()));
    m.put("is_rx_only", row.rxOnly());
    m.put("is_banned", row.banned());
    if (row.banned()) {
      m.put("ban_reason", row.banReason());
    }
    m.put("monthly_demand", row.monthlyDemand());
    m.put("mapped_pharmacy_count", row.mappedPharmacyCount());
    m.put("created_at", row.createdAt().toString());
    return m;
  }

  private Map<String, Object> toDetailMap(MedicineRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", row.id().toString());
    m.put("name", row.name());
    m.put("salt_composition", row.saltComposition());
    m.put("manufacturer", row.manufacturer());
    Map<String, Object> category = new LinkedHashMap<>();
    category.put("category_id", row.categoryId().toString());
    category.put("name", row.categoryName());
    m.put("category", category);
    m.put("form", row.form());
    m.put("pack_size", row.packSize());
    m.put("pack_unit", row.packUnit());
    m.put("schedule", row.schedule());
    m.put("hsn_code", row.hsnCode());
    m.put("gst_pct", row.gstPct());
    m.put("mrp", paiseToRupees(row.mrpPaise()));
    m.put(
        "mrp_ceiling", row.mrpCeilingPaise() == null ? null : paiseToRupees(row.mrpCeilingPaise()));
    m.put("is_rx_only", row.rxOnly());
    m.put("is_banned", row.banned());
    m.put("ban_reason", row.banReason());
    m.put("description", row.description());
    m.put("monthly_demand", row.monthlyDemand());
    m.put("mapped_pharmacy_count", row.mappedPharmacyCount());
    List<Map<String, Object>> subs = new ArrayList<>();
    for (SubstituteRef ref : store.findSubstituteRefs(row.substitutes())) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("medicine_id", ref.medicineId().toString());
      s.put("name", ref.name());
      s.put("manufacturer", ref.manufacturer());
      subs.add(s);
    }
    m.put("substitutes", subs);
    m.put("stocking_pharmacies", stockingPharmacies(row.id()));
    Map<String, Object> demand = new LinkedHashMap<>();
    demand.put("monthly_demand", row.monthlyDemand());
    demand.put("monthly_demand_trend", "STABLE");
    demand.put("top_zone", null);
    m.put("demand_stats", demand);
    m.put("created_by", row.createdBy() == null ? null : row.createdBy().toString());
    m.put("created_at", row.createdAt().toString());
    m.put("updated_at", row.updatedAt().toString());
    return m;
  }

  private List<Map<String, Object>> stockingPharmacies(UUID medicineId) {
    var result = mappings.listForAdmin(new AdminListFilter(medicineId, null, null, false, 1, 100));
    List<Map<String, Object>> out = new ArrayList<>();
    for (AdminMappingRow row : result.rows()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("pharmacy_id", row.pharmacyId().toString());
      m.put("pharmacy_name", row.pharmacyName());
      m.put("pharmacy_price", paiseToRupees(row.pharmacyPricePaise()));
      m.put("stock_quantity", row.stockQuantity());
      m.put("is_visible", row.visible());
      out.add(m);
    }
    return out;
  }

  Object fieldValue(MedicineRow row, String field) {
    if ("name".equals(field)) {
      return row.name();
    }
    if ("description".equals(field)) {
      return row.description();
    }
    if ("category_id".equals(field)) {
      return row.categoryId().toString();
    }
    if ("schedule".equals(field)) {
      return row.schedule();
    }
    if ("gst_pct".equals(field)) {
      return row.gstPct();
    }
    if ("mrp".equals(field)) {
      return row.mrpPaise();
    }
    if ("is_rx_only".equals(field)) {
      return row.rxOnly();
    }
    if ("substitutes".equals(field)) {
      return row.substitutes().stream().map(UUID::toString).toList();
    }
    return null;
  }

  private void audit(
      MedmatePrincipal principal, UUID entityId, String action, Map<String, Object> payload) {
    auditLog.append(
        new AuditLogRecord(
            Ids.newId(),
            "MEDICINE",
            entityId,
            action,
            principal.subject(),
            principal.role().name(),
            payload,
            null,
            clock.instant()));
  }

  private UUID requireCategoryId(UUID categoryId) {
    if (categoryId == null) {
      throw new AppException("INVALID_CATEGORY", "category_id is required", 400);
    }
    if (!store.categoryActive(categoryId)) {
      throw new AppException("INVALID_CATEGORY", "category_id not found or inactive", 400);
    }
    return categoryId;
  }

  private List<UUID> requireSubstitutes(List<UUID> substitutes) {
    if (substitutes == null || substitutes.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = substitutes.stream().distinct().toList();
    if (ids.size() != store.countExistingIds(ids)) {
      throw new AppException("INVALID_SUBSTITUTE_ID", "One or more substitute IDs not found", 400);
    }
    return ids;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "name is required", 400);
    }
    String trimmed = name.trim();
    if (trimmed.length() < 2 || trimmed.length() > 255) {
      throw new AppException("VALIDATION_ERROR", "name must be 2-255 characters", 400);
    }
    return trimmed;
  }

  private static String requireSalt(String salt) {
    if (salt == null || salt.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "salt_composition is required", 400);
    }
    String trimmed = salt.trim();
    if (trimmed.length() > 500) {
      throw new AppException(
          "VALIDATION_ERROR", "salt_composition must be at most 500 characters", 400);
    }
    return trimmed;
  }

  private static String requireManufacturer(String manufacturer) {
    if (manufacturer == null || manufacturer.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "manufacturer is required", 400);
    }
    String trimmed = manufacturer.trim();
    if (trimmed.length() > 200) {
      throw new AppException(
          "VALIDATION_ERROR", "manufacturer must be at most 200 characters", 400);
    }
    return trimmed;
  }

  private static String requireForm(String form) {
    if (form == null || form.isBlank()) {
      throw new AppException("INVALID_FORM", "form is required", 400);
    }
    String normalized = form.trim().toUpperCase(Locale.ROOT);
    if (!FORMS.contains(normalized)) {
      throw new AppException("INVALID_FORM", "form is not an allowed value", 400);
    }
    return normalized;
  }

  private static BigDecimal requirePackSize(Object packSize) {
    if (packSize == null) {
      throw new AppException("VALIDATION_ERROR", "pack_size is required", 400);
    }
    BigDecimal value;
    try {
      if (packSize instanceof BigDecimal bd) {
        value = bd;
      } else if (packSize instanceof Number n) {
        value = BigDecimal.valueOf(n.doubleValue());
      } else {
        value = new BigDecimal(packSize.toString().trim());
      }
    } catch (NumberFormatException ex) {
      throw new AppException("VALIDATION_ERROR", "pack_size must be a positive number", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "pack_size must be positive", 400);
    }
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static String requirePackUnit(String packUnit) {
    if (packUnit == null || packUnit.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "pack_unit is required", 400);
    }
    String normalized = packUnit.trim().toUpperCase(Locale.ROOT);
    if (!PACK_UNITS.contains(normalized)) {
      throw new AppException("VALIDATION_ERROR", "pack_unit is not an allowed value", 400);
    }
    return normalized;
  }

  private String requireSchedule(String schedule) {
    if (schedule == null || schedule.isBlank()) {
      throw new AppException("INVALID_SCHEDULE", "schedule is required", 400);
    }
    String normalized = schedule.trim().toUpperCase(Locale.ROOT);
    if (!SCHEDULES.contains(normalized)) {
      throw new AppException("INVALID_SCHEDULE", "schedule must be OTC, H, H1, or X", 400);
    }
    return normalized;
  }

  private String requireHsn(String hsnCode) {
    if (hsnCode == null || hsnCode.isBlank() || !hsnCode.trim().matches("\\d{8}")) {
      throw new AppException("INVALID_HSN_CODE", "HSN must be exactly 8 numeric digits", 400);
    }
    String code = hsnCode.trim();
    if (!store.hsnExists(code)) {
      throw new AppException("INVALID_HSN_CODE", "HSN code not found in pharma HSN reference", 400);
    }
    return code;
  }

  private static int requireGst(Integer gstPct) {
    if (gstPct == null || !GST_RATES.contains(gstPct)) {
      throw new AppException("INVALID_GST_RATE", "gst_pct must be 5, 12, or 18", 400);
    }
    return gstPct;
  }

  private static String optionalDescription(String description) {
    if (description == null) {
      return null;
    }
    String trimmed = description.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > 2000) {
      throw new AppException(
          "VALIDATION_ERROR", "description must be at most 2000 characters", 400);
    }
    return trimmed;
  }

  private static String requireBanReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new AppException("REASON_REQUIRED", "reason is required", 400);
    }
    String trimmed = reason.trim();
    if (trimmed.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason must be at most 500 characters", 400);
    }
    return trimmed;
  }

  private static boolean forcesRx(String schedule) {
    return "H".equals(schedule) || "H1".equals(schedule) || "X".equals(schedule);
  }

  static long parsePositiveAmountPaise(Object amount) {
    if (amount == null) {
      throw new AppException("VALIDATION_ERROR", "mrp is required", 400);
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
        throw new AppException("VALIDATION_ERROR", "mrp must be a positive number", 400);
      }
    } else {
      throw new AppException("VALIDATION_ERROR", "mrp must be a positive number", 400);
    }
    if (value.scale() > 2) {
      throw new AppException("VALIDATION_ERROR", "mrp may have at most 2 decimal places", 400);
    }
    if (value.compareTo(BigDecimal.ZERO) <= 0) {
      throw new AppException("VALIDATION_ERROR", "mrp must be positive", 400);
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

  private static void requireCreateRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super or admin_operations may create medicines", 403);
    }
  }

  private static void requireUpdateRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Caller may not update medicines", 403);
    }
  }

  private static void requireBanRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super or admin_compliance may ban/unban medicines", 403);
    }
  }

  public record CreateCommand(
      String name,
      String saltComposition,
      String manufacturer,
      UUID categoryId,
      String form,
      Object packSize,
      String packUnit,
      String schedule,
      String hsnCode,
      Integer gstPct,
      Object mrp,
      Boolean isRxOnly,
      String description,
      List<UUID> substitutes,
      Object monthlyDemandIgnored) {
    public CreateCommand {
      substitutes = substitutes == null ? null : List.copyOf(substitutes);
    }
  }

  public record UpdateCommand(
      String name,
      String description,
      UUID categoryId,
      String schedule,
      Integer gstPct,
      Object mrp,
      Boolean isRxOnly,
      List<UUID> substitutes,
      Object monthlyDemandIgnored) {
    public UpdateCommand {
      substitutes = substitutes == null ? null : List.copyOf(substitutes);
    }
  }
}
