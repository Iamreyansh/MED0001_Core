package com.nammamedmate.inventory.application;

import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.DistributorStore.ListResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceCompareResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceOffer;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.PriceProduct;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.SetPreferredResult;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore.SupplyRow;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.DistributorFormats;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DistributorService {

  private static final int WINDOW = 60;
  private static final int LIST_LIMIT = 60;
  private static final int CREATE_LIMIT = 20;
  private static final int PATCH_LIMIT = 30;
  private static final int DELETE_LIMIT = 10;
  private static final int SUPPLY_LIMIT = 30;
  private static final int COMPARE_LIMIT = 20;

  private final DistributorStore store;
  private final DistributorSupplyItemStore supplyStore;
  private final InventoryPlanPort planPort;
  private final RateLimiter rateLimiter;
  private final Clock clock;

  public DistributorService(
      DistributorStore store,
      DistributorSupplyItemStore supplyStore,
      InventoryPlanPort planPort,
      RateLimiter rateLimiter,
      Clock clock) {
    this.store = store;
    this.supplyStore = supplyStore;
    this.planPort = planPort;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public record ListPage(Map<String, Object> data, PaginationMeta meta) {
    public ListPage {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public ListPage list(
      MedmatePrincipal principal, Boolean isActive, String q, Integer page, Integer limit) {
    requireGrowth(principal);
    requirePharmacyReader(principal);
    rateLimit("pharmacy:distributors:list:" + principal.pharmacyId(), LIST_LIMIT);

    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    boolean activeFilter = isActive == null || isActive;

    ListResult result = store.list(principal.pharmacyId(), activeFilter, q, p, lim);
    KpiRow kpi = store.kpi(principal.pharmacyId());

    List<Map<String, Object>> rows = new ArrayList<>();
    for (Distributor d : result.items()) {
      rows.add(toListItem(d));
    }

    Map<String, Object> kpiMap = new LinkedHashMap<>();
    kpiMap.put("distributor_count", kpi.distributorCount());
    kpiMap.put("products_sourced", kpi.productsSourced());
    kpiMap.put("outstanding_payable", paiseToRupees(kpi.outstandingPayablePaise()));
    kpiMap.put("on_credit_count", kpi.onCreditCount());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("kpi", kpiMap);
    data.put("distributors", rows);
    return new ListPage(data, PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> create(
      MedmatePrincipal principal,
      String firmName,
      String contactName,
      String phone,
      String email,
      String gstin,
      String drugLicenceNumber,
      String address,
      Integer paymentTermsDays,
      BigDecimal creditLimit,
      Boolean isActive) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:distributors:create:" + principal.pharmacyId(), CREATE_LIMIT);

    String firm = requireFirmName(firmName);
    String normalizedPhone = requirePhone(phone);
    String normalizedGstin = optionalGstin(gstin);
    String normalizedEmail = optionalEmail(email);
    int terms = paymentTermsDays == null ? 0 : paymentTermsDays;
    if (terms < 0) {
      throw new AppException("VALIDATION_ERROR", "payment_terms_days must be >= 0", 400);
    }
    long creditPaise = creditLimit == null ? 0L : rupeesToPaise(creditLimit, "credit_limit");
    if (creditPaise < 0) {
      throw new AppException("VALIDATION_ERROR", "credit_limit must be >= 0", 400);
    }
    if (store.findActiveByPhone(principal.pharmacyId(), normalizedPhone, null).isPresent()) {
      throw new AppException(
          "DISTRIBUTOR_PHONE_EXISTS", "Active distributor with this phone already exists", 409);
    }

    Instant now = clock.instant();
    Distributor created =
        store.insert(
            new Distributor(
                UUID.randomUUID(),
                principal.pharmacyId(),
                firm,
                blankToNull(contactName, 100),
                normalizedPhone,
                normalizedEmail,
                normalizedGstin,
                blankToNull(drugLicenceNumber, 50),
                blankToNull(address, 500),
                terms,
                creditPaise,
                isActive == null || isActive,
                now,
                now,
                null));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", created.id().toString());
    data.put("firm_name", created.firmName());
    data.put("phone", created.phone());
    data.put("gstin", created.gstin());
    data.put("payment_terms_days", created.paymentTermsDays());
    data.put("credit_limit", paiseToRupees(created.creditLimitPaise()));
    data.put("is_active", created.active());
    data.put("created_at", created.createdAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> patch(
      MedmatePrincipal principal,
      UUID id,
      String firmName,
      String contactName,
      String phone,
      String email,
      String gstin,
      String drugLicenceNumber,
      String address,
      Integer paymentTermsDays,
      BigDecimal creditLimit,
      Boolean isActive) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:distributors:patch:" + principal.pharmacyId(), PATCH_LIMIT);

    Distributor cur =
        store
            .findById(principal.pharmacyId(), id)
            .orElseThrow(
                () -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));

    String firm = firmName == null ? cur.firmName() : requireFirmName(firmName);
    String normalizedPhone = phone == null ? cur.phone() : requirePhone(phone);
    String normalizedGstin = gstin == null ? cur.gstin() : optionalGstin(gstin);
    String normalizedEmail = email == null ? cur.email() : optionalEmail(email);
    int terms = paymentTermsDays == null ? cur.paymentTermsDays() : paymentTermsDays;
    if (terms < 0) {
      throw new AppException("VALIDATION_ERROR", "payment_terms_days must be >= 0", 400);
    }
    long creditPaise =
        creditLimit == null ? cur.creditLimitPaise() : rupeesToPaise(creditLimit, "credit_limit");
    if (creditPaise < 0) {
      throw new AppException("VALIDATION_ERROR", "credit_limit must be >= 0", 400);
    }
    if (store.findActiveByPhone(principal.pharmacyId(), normalizedPhone, id).isPresent()) {
      throw new AppException(
          "DISTRIBUTOR_PHONE_EXISTS", "Active distributor with this phone already exists", 409);
    }

    Instant now = clock.instant();
    Distributor updated =
        store.update(
            new Distributor(
                cur.id(),
                cur.pharmacyId(),
                firm,
                contactName == null ? cur.contactName() : blankToNull(contactName, 100),
                normalizedPhone,
                normalizedEmail,
                normalizedGstin,
                drugLicenceNumber == null
                    ? cur.drugLicenceNumber()
                    : blankToNull(drugLicenceNumber, 50),
                address == null ? cur.address() : blankToNull(address, 500),
                terms,
                creditPaise,
                isActive == null ? cur.active() : isActive,
                cur.createdAt(),
                now,
                cur.deletedAt()));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", updated.id().toString());
    data.put("firm_name", updated.firmName());
    data.put("updated_at", updated.updatedAt().toString());
    return data;
  }

  @Transactional
  public Map<String, Object> deactivate(MedmatePrincipal principal, UUID id) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:distributors:delete:" + principal.pharmacyId(), DELETE_LIMIT);

    Distributor cur =
        store
            .findById(principal.pharmacyId(), id)
            .orElseThrow(
                () -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));

    Instant now = clock.instant();
    store.deactivate(principal.pharmacyId(), id, now);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", cur.id().toString());
    data.put("is_active", false);
    data.put("deactivated_at", now.toString());
    return data;
  }

  public ListPage supplyList(
      MedmatePrincipal principal, UUID id, String q, Integer page, Integer limit) {
    requireGrowth(principal);
    requirePharmacyReader(principal);
    rateLimit("pharmacy:distributors:supply:" + principal.pharmacyId(), SUPPLY_LIMIT);

    Distributor d =
        store
            .findById(principal.pharmacyId(), id)
            .orElseThrow(
                () -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));

    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    var result = supplyStore.listByDistributor(principal.pharmacyId(), id, q, p, lim);

    List<Map<String, Object>> items = new ArrayList<>();
    for (SupplyRow row : result.items()) {
      BigDecimal purchase = paiseToRupees(row.purchasePricePaise());
      BigDecimal landed =
          DistributorFormats.effectiveLandedCostPaise(
              row.purchasePricePaise(), row.schemeDescription());
      BigDecimal mrp = paiseToRupees(row.mrpPaise());
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("product_id", row.productId().toString());
      m.put("product_name", row.productName());
      m.put("manufacturer", row.manufacturer());
      m.put("purchase_price", purchase);
      m.put("scheme_free_qty", row.schemeDescription());
      m.put("effective_landed_cost", landed);
      m.put("mrp", mrp);
      m.put("margin_pct", DistributorFormats.marginPct(mrp, landed));
      m.put("price_rank", row.priceRank());
      m.put("is_preferred_source", row.preferredSource());
      items.add(m);
    }

    Map<String, Object> dist = new LinkedHashMap<>();
    dist.put("id", d.id().toString());
    dist.put("firm_name", d.firmName());

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("distributor", dist);
    data.put("supply_items", items);
    return new ListPage(data, PaginationMeta.of(p, lim, result.total()));
  }

  public ListPage priceCompare(
      MedmatePrincipal principal, Boolean onlyMultiSource, String q, Integer page, Integer limit) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:distributors:compare:" + principal.pharmacyId(), COMPARE_LIMIT);

    int p = page == null || page < 1 ? 1 : page;
    int lim = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    boolean multi = Boolean.TRUE.equals(onlyMultiSource);
    PriceCompareResult result = supplyStore.priceCompare(principal.pharmacyId(), multi, q, p, lim);

    List<Map<String, Object>> products = new ArrayList<>();
    for (PriceProduct prod : result.products()) {
      List<Map<String, Object>> offers = new ArrayList<>();
      for (PriceOffer o : prod.offers()) {
        BigDecimal purchase = paiseToRupees(o.purchasePricePaise());
        BigDecimal landed =
            DistributorFormats.effectiveLandedCostPaise(
                o.purchasePricePaise(), o.schemeDescription());
        Map<String, Object> om = new LinkedHashMap<>();
        om.put("distributor_id", o.distributorId().toString());
        om.put("distributor_name", o.distributorName());
        om.put("purchase_price", purchase);
        om.put("effective_landed_cost", landed);
        om.put("mrp", paiseToRupees(o.mrpPaise()));
        om.put("is_preferred_source", o.preferredSource());
        om.put("price_rank", o.priceRank());
        offers.add(om);
      }
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("product_id", prod.productId().toString());
      pm.put("product_name", prod.productName());
      pm.put("manufacturer", prod.manufacturer());
      pm.put("distributor_prices", offers);
      products.add(pm);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("products", products);
    return new ListPage(data, PaginationMeta.of(p, lim, result.total()));
  }

  @Transactional
  public Map<String, Object> setPreferred(
      MedmatePrincipal principal, UUID distributorId, UUID productId) {
    requireGrowth(principal);
    requireOwner(principal);
    rateLimit("pharmacy:distributors:preferred:" + principal.pharmacyId(), PATCH_LIMIT);

    store
        .findById(principal.pharmacyId(), distributorId)
        .orElseThrow(() -> new AppException("DISTRIBUTOR_NOT_FOUND", "Distributor not found", 404));

    Instant now = clock.instant();
    SetPreferredResult result =
        supplyStore
            .setPreferred(principal.pharmacyId(), distributorId, productId, now)
            .orElseThrow(
                () ->
                    new AppException("SUPPLY_ITEM_NOT_FOUND", "Supply list entry not found", 404));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("distributor_id", distributorId.toString());
    data.put("product_id", productId.toString());
    data.put("is_preferred_source", true);
    data.put(
        "previous_preferred_distributor_id",
        result.previousPreferredId() == null ? null : result.previousPreferredId().toString());
    return data;
  }

  private Map<String, Object> toListItem(Distributor d) {
    long payable = store.outstandingPayablePaise(d.pharmacyId(), d.id());
    LocalDate last = store.lastPurchaseDate(d.pharmacyId(), d.id());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id().toString());
    m.put("firm_name", d.firmName());
    m.put("contact_name", d.contactName());
    m.put("phone", d.phone());
    m.put("email", d.email());
    m.put("gstin", d.gstin());
    m.put("drug_licence_number", d.drugLicenceNumber());
    m.put("outstanding_payable", paiseToRupees(payable));
    m.put("on_credit", d.onCredit());
    m.put("credit_limit", paiseToRupees(d.creditLimitPaise()));
    m.put("payment_terms_days", d.paymentTermsDays());
    m.put("is_active", d.active());
    m.put("last_purchase_date", last == null ? null : last.toString());
    return m;
  }

  private void requireGrowth(MedmatePrincipal principal) {
    if (!planPort.growthFeaturesEnabled()) {
      throw new AppException(
          "PLAN_FEATURE_LOCKED", "Distributor management requires Growth plan or higher", 403);
    }
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMITED", "Too many requests", 429);
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

  private static void requireOwner(MedmatePrincipal principal) {
    requirePharmacyReader(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER) {
      throw new AppException("FORBIDDEN", "Only pharmacy_owner may mutate distributors", 403);
    }
  }

  private static String requireFirmName(String firmName) {
    if (firmName == null || firmName.isBlank() || firmName.trim().length() > 200) {
      throw new AppException("VALIDATION_ERROR", "firm_name required (max 200)", 400);
    }
    return firmName.trim();
  }

  private static String requirePhone(String phone) {
    if (phone == null || !DistributorFormats.isValidPhone(phone)) {
      throw new AppException("INVALID_PHONE", "Phone must be E.164 Indian mobile (+91...)", 400);
    }
    return phone.trim();
  }

  private static String optionalGstin(String gstin) {
    if (gstin == null || gstin.isBlank()) {
      return null;
    }
    String trimmed = gstin.trim().toUpperCase();
    if (!DistributorFormats.isValidGstin(trimmed)) {
      throw new AppException("INVALID_GSTIN_FORMAT", "GSTIN format is invalid", 400);
    }
    return trimmed;
  }

  private static String optionalEmail(String email) {
    if (email == null || email.isBlank()) {
      return null;
    }
    String trimmed = email.trim();
    if (!DistributorFormats.isValidEmail(trimmed) || trimmed.length() > 255) {
      throw new AppException("VALIDATION_ERROR", "email is invalid", 400);
    }
    return trimmed;
  }

  private static String blankToNull(String value, int max) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw new AppException("VALIDATION_ERROR", "field exceeds max length " + max, 400);
    }
    return trimmed;
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  static long rupeesToPaise(BigDecimal value, String field) {
    if (value == null) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    if (value.scale() > 2) {
      throw new AppException("VALIDATION_ERROR", field + " max 2 decimal places", 400);
    }
    return value.movePointRight(2).longValueExact();
  }
}
