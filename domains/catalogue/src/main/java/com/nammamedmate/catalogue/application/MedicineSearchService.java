package com.nammamedmate.catalogue.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.CategoryStore;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.AutocompleteHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.AvailabilityHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.PharmacyMasterHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.PharmacyMasterPage;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SearchHit;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SearchPage;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.StockOffer;
import com.nammamedmate.catalogue.application.port.out.MedicineSearchStore.SubstituteHit;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.SearchCachePort;
import com.nammamedmate.catalogue.application.port.out.ZonePharmacyLookupPort;
import com.nammamedmate.catalogue.application.port.out.ZonePharmacyLookupPort.PharmacyRef;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MedicineSearchService {

  private static final int WINDOW = 60;
  private static final int SEARCH_IP = 120;
  private static final int SEARCH_AUTH = 300;
  private static final int DETAIL_IP = 120;
  private static final int SUBSTITUTES_IP = 120;
  private static final int AVAIL_IP = 60;
  private static final int AVAIL_AUTH = 200;
  private static final int PHARMACY_SEARCH = 120;
  private static final int AC_LIMIT = 10;
  private static final int MAX_AVAIL_IDS = 50;
  private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final MedicineSearchStore searchStore;
  private final MedicineStore medicineStore;
  private final CategoryStore categoryStore;
  private final ZonePharmacyLookupPort pharmacies;
  private final SearchCachePort cache;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public MedicineSearchService(
      MedicineSearchStore searchStore,
      MedicineStore medicineStore,
      CategoryStore categoryStore,
      ZonePharmacyLookupPort pharmacies,
      SearchCachePort cache,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper) {
    this.searchStore = searchStore;
    this.medicineStore = medicineStore;
    this.categoryStore = categoryStore;
    this.pharmacies = pharmacies;
    this.cache = cache;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public record Envelope(Map<String, Object> data, Object meta) {
    public Envelope {
      // LinkedHashMap copy — Map.copyOf forbids null values (best_pharmacy / did_you_mean)
      data = data == null ? Map.of() : new LinkedHashMap<>(data);
    }
  }

  @Transactional(readOnly = true)
  public Envelope search(
      MedmatePrincipal principal,
      String q,
      UUID categoryId,
      String schedule,
      Boolean isRxOnly,
      Double lat,
      Double lng,
      UUID pharmacyId,
      UUID zoneId,
      String pincode,
      boolean autocomplete,
      boolean showOos,
      boolean includeBanned,
      Integer page,
      Integer limit,
      String clientIp) {
    rateLimitSearch(principal, clientIp);
    String query = requireQuery(q);

    if (autocomplete) {
      return autocomplete(query);
    }

    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 20 : Math.min(50, Math.max(1, limit));
    String sched = normalizeSchedule(schedule);
    boolean excludeBanned = !(includeBanned && isAdminReader(principal));

    SearchPage result = searchStore.search(query, categoryId, sched, isRxOnly, excludeBanned, p, l);

    UUID resolvedZone = resolveZone(zoneId, pincode, lat, lng);
    List<UUID> ids = result.rows().stream().map(SearchHit::medicineId).toList();
    Map<UUID, StockOffer> best =
        indexBest(searchStore.bestOffers(ids, resolvedZone, pharmacyId, showOos));

    List<Map<String, Object>> results = new ArrayList<>();
    for (SearchHit hit : result.rows()) {
      results.add(toSearchResult(hit, best.get(hit.medicineId())));
    }

    String didYouMean = null;
    if (results.isEmpty()) {
      didYouMean = searchStore.didYouMean(query).orElse(null);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("query", query);
    data.put("results", results);
    data.put("total_results", result.total());
    data.put("did_you_mean", didYouMean);
    return new Envelope(data, PaginationMeta.of(p, l, result.total()));
  }

  @Transactional(readOnly = true)
  public Envelope getDetail(
      Double lat,
      Double lng,
      UUID zoneId,
      String pincode,
      UUID medicineId,
      boolean showOos,
      String clientIp) {
    rateLimit("catalogue:detail:" + normalizeIp(clientIp), DETAIL_IP);
    if (medicineId == null) {
      throw new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404);
    }

    MedicineRow row =
        medicineStore
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));
    if (row.banned()) {
      throw new AppException(
          "MEDICINE_BANNED", "Medicine is banned and not publicly accessible", 410);
    }

    Map<String, Object> data = readDetailCache(medicineId);
    if (data == null) {
      data = toDetailBase(row);
      List<SubstituteHit> subs = searchStore.findSubstitutes(row.substitutes());
      data.put("substitutes", subs.stream().map(this::toSubstituteBrief).toList());
      writeDetailCache(medicineId, data);
    }

    UUID resolvedZone = resolveZone(zoneId, pincode, lat, lng);
    List<StockOffer> offers = searchStore.stockingOffers(medicineId, resolvedZone, showOos);
    data = new LinkedHashMap<>(data);
    data.put("stocking_pharmacies_nearby", offers.stream().map(this::toPharmacyStock).toList());
    return new Envelope(data, Map.of());
  }

  @Transactional(readOnly = true)
  public Envelope substitutes(UUID medicineId, String clientIp) {
    rateLimit("catalogue:substitutes:" + normalizeIp(clientIp), SUBSTITUTES_IP);
    MedicineRow row =
        medicineStore
            .findById(medicineId)
            .orElseThrow(() -> new AppException("MEDICINE_NOT_FOUND", "Medicine not found", 404));

    List<SubstituteHit> hits = searchStore.findSubstitutes(row.substitutes());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("medicine_id", row.id().toString());
    data.put("medicine_name", row.name());
    data.put("substitutes", hits.stream().map(this::toSubstituteFull).toList());
    return new Envelope(data, Map.of());
  }

  @Transactional(readOnly = true)
  public Envelope checkAvailability(
      MedmatePrincipal principal, List<UUID> medicineIds, UUID pharmacyId, String clientIp) {
    rateLimitAvailability(principal, clientIp);
    if (pharmacyId == null) {
      throw new AppException("PHARMACY_NOT_FOUND", "pharmacy_id is required", 404);
    }
    if (medicineIds == null || medicineIds.isEmpty()) {
      throw new AppException("MEDICINE_IDS_REQUIRED", "medicine_ids array is empty", 400);
    }
    if (medicineIds.size() > MAX_AVAIL_IDS) {
      throw new AppException("TOO_MANY_MEDICINES", "At most 50 medicine IDs per request", 400);
    }

    PharmacyRef pharmacy =
        pharmacies
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
    if (!"ACTIVE".equalsIgnoreCase(pharmacy.status())) {
      throw new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found or not ACTIVE", 404);
    }

    List<AvailabilityHit> hits = searchStore.checkAvailability(pharmacyId, medicineIds);
    List<Map<String, Object>> results = new ArrayList<>();
    for (AvailabilityHit hit : hits) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("medicine_id", hit.medicineId().toString());
      m.put("name", hit.name());
      m.put("in_stock", hit.inStock());
      m.put("stock_quantity", hit.stockQuantity());
      m.put(
          "pharmacy_price",
          hit.pharmacyPricePaise() == null ? null : paiseToRupees(hit.pharmacyPricePaise()));
      m.put("is_rx_only", hit.rxOnly());
      results.add(m);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacy.id().toString());
    data.put("pharmacy_name", pharmacy.name());
    data.put("pharmacy_is_online", pharmacy.online() && !pharmacy.adminForcedOffline());
    data.put("checked_at", Instant.now(clock).toString());
    data.put("results", results);
    return new Envelope(data, Map.of());
  }

  @Transactional(readOnly = true)
  public Envelope pharmacySearch(
      MedmatePrincipal principal,
      String q,
      String source,
      Boolean inStockOnly,
      Integer page,
      Integer limit) {
    requirePharmacyReader(principal);
    rateLimit("pharmacy:catalogue:search:" + principal.pharmacyId(), PHARMACY_SEARCH);
    String query = requireQuery(q);
    String src =
        source == null || source.isBlank() ? "ALL" : source.trim().toUpperCase(Locale.ROOT);
    if (!src.equals("ALL") && !src.equals("MASTER") && !src.equals("CUSTOM")) {
      src = "ALL";
    }
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null ? 20 : Math.min(50, Math.max(1, limit));
    boolean stockOnly = Boolean.TRUE.equals(inStockOnly);

    List<Map<String, Object>> results = new ArrayList<>();
    long total = 0L;

    if (!src.equals("CUSTOM")) {
      PharmacyMasterPage master =
          searchStore.searchMasterForPharmacy(principal.pharmacyId(), query, stockOnly, p, l);
      total = master.total();
      for (PharmacyMasterHit hit : master.rows()) {
        results.add(toPharmacyMasterResult(hit));
      }
    }

    // ponytail: CUSTOM POS SKUs empty until EPIC-006 PharmacyInventory
    if (src.equals("CUSTOM")) {
      total = 0L;
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("query", query);
    data.put("pharmacy_id", principal.pharmacyId().toString());
    data.put("results", results);
    return new Envelope(data, PaginationMeta.of(p, l, total));
  }

  private Envelope autocomplete(String query) {
    String key = normalizeQuery(query);
    List<Map<String, Object>> suggestions = readAutocompleteCache(key);
    boolean cached = suggestions != null;
    if (suggestions == null) {
      List<AutocompleteHit> hits = searchStore.autocomplete(query, AC_LIMIT);
      suggestions = new ArrayList<>();
      for (AutocompleteHit hit : hits) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medicine_id", hit.medicineId().toString());
        m.put("name", hit.name());
        m.put("manufacturer", hit.manufacturer());
        suggestions.add(m);
      }
      writeAutocompleteCache(key, suggestions);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("query", query);
    data.put("suggestions", suggestions);
    return new Envelope(data, Map.of("cached", cached));
  }

  /**
   * ponytail: lat/lng accepted for API parity; zone geometry deferred to EPIC-009. Zone filter only
   * when {@code zone_id} or {@code pincode} resolves.
   */
  private UUID resolveZone(UUID zoneId, String pincode, Double lat, Double lng) {
    if (zoneId != null) {
      return zoneId;
    }
    if (pincode != null && !pincode.isBlank()) {
      return pharmacies.zoneIdForPincode(pincode.trim()).orElse(null);
    }
    // ponytail: lat/lng accepted for contract; zone geometry deferred EPIC-009
    if (lat != null && lng != null) {
      return null;
    }
    return null;
  }

  private Map<String, Object> toSearchResult(SearchHit hit, StockOffer best) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", hit.medicineId().toString());
    m.put("name", hit.name());
    m.put("salt_composition", hit.saltComposition());
    m.put("manufacturer", hit.manufacturer());
    Map<String, Object> category = new LinkedHashMap<>();
    category.put("name", hit.categoryName());
    category.put("slug", hit.categorySlug());
    m.put("category", category);
    m.put("form", hit.form());
    m.put("pack_size", hit.packSize());
    m.put("pack_unit", hit.packUnit());
    m.put("schedule", hit.schedule());
    m.put("is_rx_only", hit.rxOnly());
    m.put("rx_required", hit.rxOnly());
    boolean online = !"X".equalsIgnoreCase(hit.schedule());
    m.put("available_online", online);
    if (!online) {
      m.put("note", "Schedule X — visit pharmacy in person; not available for online delivery");
    }
    m.put("typical_mrp", paiseToRupees(hit.mrpPaise()));
    m.put("relevance_score", roundScore(hit.relevanceScore()));
    m.put("best_pharmacy", best == null ? null : toPharmacyStock(best));
    return m;
  }

  private Map<String, Object> toDetailBase(MedicineRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", row.id().toString());
    m.put("name", row.name());
    m.put("salt_composition", row.saltComposition());
    m.put("manufacturer", row.manufacturer());
    Map<String, Object> category = new LinkedHashMap<>();
    category.put("name", row.categoryName());
    category.put(
        "slug",
        categoryStore.findById(row.categoryId()).map(CategoryStore.CategoryRow::slug).orElse(null));
    m.put("category", category);
    m.put("form", row.form());
    m.put("pack_size", row.packSize());
    m.put("pack_unit", row.packUnit());
    m.put("schedule", row.schedule());
    m.put("is_rx_only", row.rxOnly());
    m.put("rx_required", row.rxOnly());
    boolean online = !"X".equalsIgnoreCase(row.schedule());
    m.put("available_online", online);
    if (!online) {
      m.put("note", "Schedule X — visit pharmacy in person; not available for online delivery");
    }
    m.put("description", row.description());
    m.put("typical_mrp", paiseToRupees(row.mrpPaise()));
    return m;
  }

  private Map<String, Object> toPharmacyStock(StockOffer offer) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("pharmacy_id", offer.pharmacyId().toString());
    m.put("pharmacy_name", offer.pharmacyName());
    m.put("price", paiseToRupees(offer.pharmacyPricePaise()));
    m.put("in_stock", offer.inStock());
    // ponytail: distance/ETA until EPIC-009 geometry
    m.put("distance_km", null);
    m.put("estimated_delivery_minutes", null);
    return m;
  }

  private Map<String, Object> toSubstituteBrief(SubstituteHit hit) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", hit.medicineId().toString());
    m.put("name", hit.name());
    m.put("manufacturer", hit.manufacturer());
    m.put("typical_mrp", paiseToRupees(hit.mrpPaise()));
    return m;
  }

  private Map<String, Object> toSubstituteFull(SubstituteHit hit) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("medicine_id", hit.medicineId().toString());
    m.put("name", hit.name());
    m.put("salt_composition", hit.saltComposition());
    m.put("manufacturer", hit.manufacturer());
    m.put("form", hit.form());
    m.put("pack_size", hit.packSize());
    m.put("schedule", hit.schedule());
    m.put("is_rx_only", hit.rxOnly());
    m.put("typical_mrp", paiseToRupees(hit.mrpPaise()));
    return m;
  }

  private Map<String, Object> toPharmacyMasterResult(PharmacyMasterHit hit) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("source", "MASTER");
    m.put("medicine_id", hit.medicineId().toString());
    m.put("name", hit.name());
    m.put("salt_composition", hit.saltComposition());
    m.put("manufacturer", hit.manufacturer());
    m.put("form", hit.form());
    m.put("pack_size", hit.packSize());
    m.put("schedule", hit.schedule());
    m.put("is_rx_only", hit.rxOnly());
    m.put("master_mrp", paiseToRupees(hit.masterMrpPaise()));
    m.put(
        "pharmacy_price",
        hit.pharmacyPricePaise() == null ? null : paiseToRupees(hit.pharmacyPricePaise()));
    m.put("stock_quantity", hit.stockQuantity());
    m.put("mapping_id", hit.mappingId() == null ? null : hit.mappingId().toString());
    m.put("is_mapped", hit.mapped());
    m.put("is_visible", hit.visible());
    return m;
  }

  private static Map<UUID, StockOffer> indexBest(List<StockOffer> offers) {
    Map<UUID, StockOffer> map = new LinkedHashMap<>();
    if (offers == null) {
      return map;
    }
    for (StockOffer offer : offers) {
      map.putIfAbsent(offer.medicineId(), offer);
    }
    return map;
  }

  private List<Map<String, Object>> readAutocompleteCache(String key) {
    return cache
        .getAutocomplete(key)
        .map(
            json -> {
              try {
                return objectMapper.readValue(json, LIST_MAP);
              } catch (Exception e) {
                return null;
              }
            })
        .orElse(null);
  }

  private void writeAutocompleteCache(String key, List<Map<String, Object>> suggestions) {
    try {
      cache.putAutocomplete(key, objectMapper.writeValueAsString(suggestions));
    } catch (Exception ignored) {
      // cache best-effort
    }
  }

  private Map<String, Object> readDetailCache(UUID medicineId) {
    return cache
        .getMedicineDetail(medicineId)
        .map(
            json -> {
              try {
                return objectMapper.readValue(json, MAP_TYPE);
              } catch (Exception e) {
                return null;
              }
            })
        .orElse(null);
  }

  private void writeDetailCache(UUID medicineId, Map<String, Object> data) {
    try {
      cache.putMedicineDetail(medicineId, objectMapper.writeValueAsString(data));
    } catch (Exception ignored) {
      // cache best-effort
    }
  }

  private void rateLimitSearch(MedmatePrincipal principal, String clientIp) {
    int limit = principal != null ? SEARCH_AUTH : SEARCH_IP;
    String key =
        principal != null
            ? "catalogue:search:user:" + principal.subject()
            : "catalogue:search:ip:" + normalizeIp(clientIp);
    rateLimit(key, limit);
  }

  private void rateLimitAvailability(MedmatePrincipal principal, String clientIp) {
    int limit = principal != null ? AVAIL_AUTH : AVAIL_IP;
    String key =
        principal != null
            ? "catalogue:avail:user:" + principal.subject()
            : "catalogue:avail:ip:" + normalizeIp(clientIp);
    rateLimit(key, limit);
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static String requireQuery(String q) {
    if (q == null || q.isBlank()) {
      throw new AppException("QUERY_TOO_SHORT", "q must be at least 2 characters", 400);
    }
    String trimmed = q.trim();
    if (trimmed.length() < 2) {
      throw new AppException("QUERY_TOO_SHORT", "q must be at least 2 characters", 400);
    }
    if (trimmed.length() > 200) {
      throw new AppException("QUERY_TOO_LONG", "q must be at most 200 characters", 400);
    }
    return trimmed;
  }

  private static String normalizeSchedule(String schedule) {
    if (schedule == null || schedule.isBlank()) {
      return null;
    }
    String s = schedule.trim().toUpperCase(Locale.ROOT);
    if (!s.equals("OTC") && !s.equals("H") && !s.equals("H1") && !s.equals("X")) {
      return null;
    }
    return s;
  }

  private static String normalizeQuery(String query) {
    return query.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeIp(String clientIp) {
    return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
  }

  private static boolean isAdminReader(MedmatePrincipal principal) {
    if (principal == null) {
      return false;
    }
    AuthRole role = principal.role();
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_COMPLIANCE;
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
      throw new AppException("FORBIDDEN", "pharmacy_id required in token", 403);
    }
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  static double roundScore(double score) {
    return Math.round(score * 100.0) / 100.0;
  }
}
