package com.nammamedmate.pharmacy.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore;
import com.nammamedmate.pharmacy.application.port.out.AdminPharmacyStore.AdminDetailRow;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore;
import com.nammamedmate.pharmacy.application.port.out.PerformanceAlertStore.AlertRow;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.AdminOrderDetail;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.OrderListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.PharmacyRating;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort.RatingListResult;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore;
import com.nammamedmate.pharmacy.application.port.out.PharmacyPerformanceSnapshotStore.SnapshotRow;
import com.nammamedmate.pharmacy.domain.CustomerNameMasker;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPharmacyPerformanceService {

  /** Redis key: pharmacy:perf:snapshot:{pharmacyId}:{period} — TTL 4 hours. */
  static final String CACHE_KEY_PREFIX = "pharmacy:perf:snapshot:";

  static final Duration CACHE_TTL = Duration.ofHours(4);
  static final Duration ALERT_THROTTLE = Duration.ofHours(24);

  private static final int READ_LIMIT = 60;
  private static final int ALERT_LIMIT = 10;
  private static final int WINDOW = 60;
  private static final int DEFAULT_PAGE_LIMIT = 20;
  private static final int MAX_PAGE_LIMIT = 100;
  private static final BigDecimal FILL_WARNING = new BigDecimal("85.00");
  private static final BigDecimal FILL_SUSPENSION = new BigDecimal("70.00");
  private static final BigDecimal CANCEL_WARNING = new BigDecimal("15.00");
  private static final BigDecimal ON_TIME_PREP_WARNING = new BigDecimal("80.00");
  private static final BigDecimal ZERO = new BigDecimal("0.00");
  private static final BigDecimal ZERO_PREP = new BigDecimal("0.0");

  private static final Set<String> PERIODS = Set.of("7d", "30d", "90d");
  private static final Set<String> ALERT_TYPES =
      Set.of(
          "LOW_FILL_RATE",
          "HIGH_CANCEL_RATE",
          "OFFLINE_PEAK_HOURS",
          "LOW_RATING",
          "HIGH_OOS_RATE",
          "SLOW_PREP_TIME");
  private static final Set<String> RATING_SORTS = Set.of("created_at", "rating");
  private static final List<String> ALERT_CHANNELS = List.of("WHATSAPP", "IN_APP");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final AdminPharmacyStore pharmacies;
  private final PharmacyPerformanceSnapshotStore snapshots;
  private final PerformanceAlertStore alerts;
  private final PharmacyOrderMetricsPort orderMetrics;
  private final NotificationDispatchPort notifications;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, LocalCacheEntry> localCache = new ConcurrentHashMap<>();

  public AdminPharmacyPerformanceService(
      AdminPharmacyStore pharmacies,
      PharmacyPerformanceSnapshotStore snapshots,
      PerformanceAlertStore alerts,
      PharmacyOrderMetricsPort orderMetrics,
      NotificationDispatchPort notifications,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper,
      ObjectProvider<StringRedisTemplate> redis) {
    this.pharmacies = pharmacies;
    this.snapshots = snapshots;
    this.alerts = alerts;
    this.orderMetrics = orderMetrics;
    this.notifications = notifications;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.redis = redis;
  }

  private record LocalCacheEntry(String json, Instant expiresAt) {}

  public record PagedResult(Map<String, Object> data, PaginationMeta meta) {
    public PagedResult {
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  @Transactional(readOnly = true)
  public Map<String, Object> performance(
      MedmatePrincipal principal, UUID pharmacyId, String period) {
    requirePerformanceRole(principal);
    rateLimit("admin:pharmacies:performance:" + principal.subject(), READ_LIMIT);

    String normalisedPeriod = normalisePeriod(period);
    AdminDetailRow pharmacy = requirePharmacy(pharmacyId);

    SnapshotRow snapshot = loadSnapshot(pharmacyId, dbPeriod(normalisedPeriod));
    return buildPerformanceResponse(pharmacy, normalisedPeriod, snapshot, principal.role());
  }

  @Transactional(readOnly = true)
  public PagedResult ratings(
      MedmatePrincipal principal,
      UUID pharmacyId,
      Integer rating,
      String sort,
      String order,
      Integer page,
      Integer limit) {
    requireRatingsRole(principal);
    rateLimit("admin:pharmacies:ratings:" + principal.subject(), READ_LIMIT);
    requirePharmacy(pharmacyId);

    if (rating != null && (rating < 1 || rating > 5)) {
      throw new AppException("INVALID_RATING_FILTER", "Rating filter must be between 1 and 5", 400);
    }

    String sortField = sort == null || sort.isBlank() ? "created_at" : sort.trim();
    if (!RATING_SORTS.contains(sortField)) {
      sortField = "created_at";
    }
    String orderDir = normaliseOrder(order);
    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null || limit < 1 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
    int offset = (p - 1) * l;

    RatingListResult result =
        orderMetrics.listRatings(pharmacyId, rating, sortField, orderDir, l, offset);

    List<Map<String, Object>> ratingRows = new ArrayList<>();
    for (PharmacyRating row : result.ratings()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("rating_id", row.ratingId());
      item.put("order_id", row.orderId());
      item.put("order_number", row.orderNumber());
      item.put("customer_name", CustomerNameMasker.mask(row.customerName()));
      item.put("rating", row.rating());
      item.put("review_text", row.reviewText());
      item.put("created_at", row.createdAt());
      ratingRows.add(item);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId);
    data.put("avg_rating", result.avgRating());
    data.put("review_count", result.reviewCount());
    data.put("rating_distribution", distributionStrings(result.distribution()));
    data.put("ratings", ratingRows);

    PaginationMeta meta = PaginationMeta.of(p, l, result.total());
    return new PagedResult(data, meta);
  }

  @Transactional(readOnly = true)
  public PagedResult orders(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String status,
      LocalDate fromDate,
      LocalDate toDate,
      Integer page,
      Integer limit) {
    requireRatingsRole(principal);
    rateLimit("admin:pharmacies:orders:" + principal.subject(), READ_LIMIT);
    requirePharmacy(pharmacyId);

    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LocalDate from = fromDate == null ? today.minusDays(30) : fromDate;
    LocalDate to = toDate == null ? today : toDate;
    if (from.isAfter(to)) {
      throw new AppException("VALIDATION_ERROR", "from_date must be on or before to_date", 400);
    }

    int p = page == null || page < 1 ? 1 : page;
    int l = limit == null || limit < 1 ? DEFAULT_PAGE_LIMIT : Math.min(limit, MAX_PAGE_LIMIT);
    int offset = (p - 1) * l;
    String statusFilter = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();

    OrderListResult result = orderMetrics.listOrders(pharmacyId, statusFilter, from, to, l, offset);

    List<Map<String, Object>> orderRows = new ArrayList<>();
    for (AdminOrderDetail row : result.orders()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("order_id", row.orderId());
      item.put("order_number", row.orderNumber());
      item.put("status", row.status());
      item.put("customer_name", CustomerNameMasker.mask(row.customerName()));
      item.put("item_count", row.itemCount());
      item.put("total_amount", paiseToRupees(row.totalAmountPaise()));
      item.put("prep_minutes", row.prepMinutes());
      item.put("prep_on_time", row.prepOnTime());
      item.put("has_rx", row.hasRx());
      item.put("created_at", row.createdAt());
      item.put("delivered_at", row.deliveredAt());
      orderRows.add(item);
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId);
    data.put("orders", orderRows);
    PaginationMeta meta = PaginationMeta.of(p, l, result.total());
    return new PagedResult(data, meta);
  }

  @Transactional
  public Map<String, Object> sendAlert(
      MedmatePrincipal principal,
      UUID pharmacyId,
      String alertType,
      BigDecimal thresholdValue,
      String message) {
    requireAlertRole(principal);
    rateLimit("admin:pharmacies:performance:alert:" + principal.subject(), ALERT_LIMIT);
    requirePharmacy(pharmacyId);

    if (alertType == null || alertType.isBlank() || !ALERT_TYPES.contains(alertType.trim())) {
      throw new AppException("INVALID_ALERT_TYPE", "alert_type is not valid", 400);
    }
    String type = alertType.trim();
    if (thresholdValue == null) {
      throw new AppException("THRESHOLD_VALUE_REQUIRED", "threshold_value is required", 400);
    }
    if (message != null && message.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "message must be at most 500 characters", 400);
    }

    Instant now = clock.instant();
    Instant throttleSince = now.minus(ALERT_THROTTLE);
    alerts
        .lastSentAt(pharmacyId, type, throttleSince)
        .ifPresent(
            lastSent -> {
              Instant nextAllowed = lastSent.plus(ALERT_THROTTLE);
              int retrySeconds = (int) Math.max(1, ChronoUnit.SECONDS.between(now, nextAllowed));
              throw new AppException(
                  "ALERT_THROTTLED",
                  "Same alert type already sent within 24 hours",
                  429,
                  retrySeconds,
                  Map.of("next_alert_allowed_at", nextAllowed));
            });

    String body =
        message == null || message.isBlank()
            ? defaultAlertMessage(type, thresholdValue)
            : message.trim();
    Instant nextAllowed = now.plus(ALERT_THROTTLE);

    AlertRow row =
        new AlertRow(
            Ids.newId(),
            pharmacyId,
            type,
            principal.subject(),
            thresholdValue,
            body,
            ALERT_CHANNELS,
            now);
    alerts.insert(row);
    notifications.dispatchPerformanceAlert(pharmacyId, type, body, ALERT_CHANNELS);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacyId);
    data.put("alert_type", type);
    data.put("threshold_value", thresholdValue);
    data.put("channels_notified", ALERT_CHANNELS);
    data.put("sent_at", now);
    data.put("next_alert_allowed_at", nextAllowed);
    return data;
  }

  SnapshotRow loadSnapshot(UUID pharmacyId, String dbPeriod) {
    Map<String, Object> cached = readCache(pharmacyId, dbPeriod);
    if (cached != null) {
      return snapshotFromCache(cached);
    }
    SnapshotRow row = snapshots.find(pharmacyId, dbPeriod).orElse(null);
    if (row != null) {
      writeCache(pharmacyId, dbPeriod, row);
    }
    return row;
  }

  void writeCache(UUID pharmacyId, String dbPeriod, SnapshotRow row) {
    try {
      String json = objectMapper.writeValueAsString(snapshotToMap(row));
      String key = cacheKey(pharmacyId, dbPeriod);
      StringRedisTemplate template = redisTemplate();
      if (template != null) {
        template.opsForValue().set(key, json, CACHE_TTL);
        return;
      }
      localCache.put(key, new LocalCacheEntry(json, clock.instant().plus(CACHE_TTL)));
    } catch (IOException | RuntimeException ignored) {
      // cache is best-effort
    }
  }

  private Map<String, Object> buildPerformanceResponse(
      AdminDetailRow pharmacy, String period, SnapshotRow snapshot, AuthRole role) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("pharmacy_id", pharmacy.pharmacyId());
    data.put("business_name", pharmacy.businessName());
    data.put("period", period);

    if (snapshot == null) {
      LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
      data.put("period_start", today.minusDays(periodDays(period) - 1L));
      data.put("period_end", today);
      data.put("metrics", emptyMetrics());
      data.put("alerts", emptyAlerts());
      data.put("thresholds", thresholds());
      data.put("trend", stableTrend());
      data.put("computed_at", null);
    } else {
      data.put("period_start", snapshot.periodStart());
      data.put("period_end", snapshot.periodEnd());
      data.put("metrics", metricsFromSnapshot(snapshot));
      data.put("alerts", alertsFromSnapshot(snapshot));
      data.put("thresholds", thresholds());
      Map<String, Object> trend = new LinkedHashMap<>();
      trend.put("fill_rate_trend", snapshot.fillRateTrend());
      trend.put("cancel_rate_trend", snapshot.cancelRateTrend());
      data.put("trend", trend);
      data.put("computed_at", snapshot.computedAt());
    }

    if (!canSeeFullPerformance(role)) {
      applyCompliancePerformanceView(data);
    }
    return data;
  }

  /** Full KPI payload: not for admin_compliance (BR7 / STORY-004-002). */
  static boolean canSeeFullPerformance(AuthRole role) {
    return role != AuthRole.ADMIN_COMPLIANCE;
  }

  private static void applyCompliancePerformanceView(Map<String, Object> data) {
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) data.get("metrics");
    Map<String, Object> reduced = new LinkedHashMap<>();
    reduced.put("fill_rate_pct", metrics.get("fill_rate_pct"));
    reduced.put("out_of_stock_rate_pct", metrics.get("out_of_stock_rate_pct"));
    data.put("metrics", reduced);
    data.remove("business_name");
    data.remove("alerts");
    data.remove("thresholds");
    data.remove("trend");
  }

  private Map<String, Object> metricsFromSnapshot(SnapshotRow snapshot) {
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("fill_rate_pct", snapshot.fillRatePct());
    metrics.put("on_time_prep_pct", snapshot.onTimePrepPct());
    metrics.put("cancel_rate_pct", snapshot.cancelRatePct());
    metrics.put("out_of_stock_rate_pct", snapshot.outOfStockRatePct());
    metrics.put("avg_prep_minutes", snapshot.avgPrepMinutes());
    metrics.put("complaint_count", snapshot.complaintCount());
    metrics.put("avg_rating", snapshot.avgRating());
    metrics.put("review_count", snapshot.reviewCount());
    metrics.put("orders_received", snapshot.ordersReceived());
    metrics.put("orders_fulfilled", snapshot.ordersFulfilled());
    metrics.put("orders_cancelled", snapshot.ordersCancelled());
    metrics.put("gmv_period", paiseToRupees(snapshot.gmvPeriodPaise()));
    return metrics;
  }

  private Map<String, Object> alertsFromSnapshot(SnapshotRow snapshot) {
    boolean autoWarning =
        snapshot.fillRatePct().compareTo(FILL_WARNING) < 0
            || snapshot.cancelRatePct().compareTo(CANCEL_WARNING) > 0;
    boolean suspensionRisk =
        snapshot.consecutiveLowFillDays() >= 3
            && snapshot.fillRatePct().compareTo(FILL_SUSPENSION) < 0;
    Map<String, Object> alerts = new LinkedHashMap<>();
    alerts.put("auto_warning_triggered", autoWarning);
    alerts.put("auto_suspension_risk", suspensionRisk);
    alerts.put("consecutive_low_fill_rate_days", (int) snapshot.consecutiveLowFillDays());
    return alerts;
  }

  static Map<String, Object> emptyMetrics() {
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("fill_rate_pct", ZERO);
    metrics.put("on_time_prep_pct", ZERO);
    metrics.put("cancel_rate_pct", ZERO);
    metrics.put("out_of_stock_rate_pct", ZERO);
    metrics.put("avg_prep_minutes", ZERO_PREP);
    metrics.put("complaint_count", 0);
    metrics.put("avg_rating", ZERO);
    metrics.put("review_count", 0);
    metrics.put("orders_received", 0);
    metrics.put("orders_fulfilled", 0);
    metrics.put("orders_cancelled", 0);
    metrics.put("gmv_period", ZERO);
    return metrics;
  }

  static Map<String, Object> emptyAlerts() {
    Map<String, Object> alerts = new LinkedHashMap<>();
    alerts.put("auto_warning_triggered", false);
    alerts.put("auto_suspension_risk", false);
    alerts.put("consecutive_low_fill_rate_days", 0);
    return alerts;
  }

  static Map<String, Object> thresholds() {
    Map<String, Object> thresholds = new LinkedHashMap<>();
    thresholds.put("fill_rate_warning_pct", 85);
    thresholds.put("fill_rate_suspension_pct", 70);
    thresholds.put("cancel_rate_warning_pct", 15);
    thresholds.put("on_time_prep_warning_pct", 80);
    return thresholds;
  }

  static Map<String, Object> stableTrend() {
    Map<String, Object> trend = new LinkedHashMap<>();
    trend.put("fill_rate_trend", "STABLE");
    trend.put("cancel_rate_trend", "STABLE");
    return trend;
  }

  static String normalisePeriod(String period) {
    if (period == null || period.isBlank()) {
      return "30d";
    }
    String p = period.trim().toLowerCase();
    if (!PERIODS.contains(p)) {
      throw new AppException("INVALID_PERIOD", "period must be 7d, 30d, or 90d", 400);
    }
    return p;
  }

  static String dbPeriod(String period) {
    return switch (period) {
      case "7d" -> "7D";
      case "90d" -> "90D";
      default -> "30D";
    };
  }

  static int periodDays(String period) {
    return switch (period) {
      case "7d" -> 7;
      case "90d" -> 90;
      default -> 30;
    };
  }

  static String computeTrend(BigDecimal current, BigDecimal previous) {
    if (current == null || previous == null) {
      return "STABLE";
    }
    BigDecimal delta = current.subtract(previous);
    if (delta.compareTo(new BigDecimal("2.00")) >= 0) {
      return "IMPROVING";
    }
    if (delta.compareTo(new BigDecimal("-2.00")) <= 0) {
      return "DECLINING";
    }
    return "STABLE";
  }

  private AdminDetailRow requirePharmacy(UUID pharmacyId) {
    return pharmacies
        .findDetail(pharmacyId)
        .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));
  }

  private static String normaliseOrder(String order) {
    if (order == null || order.isBlank()) {
      return "desc";
    }
    return "asc".equalsIgnoreCase(order.trim()) ? "asc" : "desc";
  }

  private static Map<String, Integer> distributionStrings(Map<Integer, Integer> distribution) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (int star = 5; star >= 1; star--) {
      out.put(String.valueOf(star), distribution.getOrDefault(star, 0));
    }
    return out;
  }

  private static String defaultAlertMessage(String alertType, BigDecimal threshold) {
    return "Performance alert " + alertType + " triggered at " + threshold;
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  private Map<String, Object> readCache(UUID pharmacyId, String dbPeriod) {
    try {
      String key = cacheKey(pharmacyId, dbPeriod);
      StringRedisTemplate template = redisTemplate();
      String raw;
      if (template != null) {
        raw = template.opsForValue().get(key);
      } else {
        LocalCacheEntry entry = localCache.get(key);
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
          return null;
        }
        raw = entry.json();
      }
      if (raw == null || raw.isBlank()) {
        return null;
      }
      return objectMapper.readValue(raw, MAP_TYPE);
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }

  static String cacheKey(UUID pharmacyId, String dbPeriod) {
    return CACHE_KEY_PREFIX + pharmacyId + ":" + dbPeriod;
  }

  private StringRedisTemplate redisTemplate() {
    return redis == null ? null : redis.getIfAvailable();
  }

  private static Map<String, Object> snapshotToMap(SnapshotRow row) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", row.id().toString());
    map.put("pharmacy_id", row.pharmacyId().toString());
    map.put("period", row.period());
    map.put("period_start", row.periodStart().toString());
    map.put("period_end", row.periodEnd().toString());
    map.put("orders_received", row.ordersReceived());
    map.put("orders_fulfilled", row.ordersFulfilled());
    map.put("orders_cancelled", row.ordersCancelled());
    map.put("fill_rate_pct", row.fillRatePct());
    map.put("on_time_prep_pct", row.onTimePrepPct());
    map.put("cancel_rate_pct", row.cancelRatePct());
    map.put("out_of_stock_rate_pct", row.outOfStockRatePct());
    map.put("avg_prep_minutes", row.avgPrepMinutes());
    map.put("complaint_count", row.complaintCount());
    map.put("avg_rating", row.avgRating());
    map.put("review_count", row.reviewCount());
    map.put("gmv_period_paise", row.gmvPeriodPaise());
    map.put("consecutive_low_fill_days", row.consecutiveLowFillDays());
    map.put("fill_rate_trend", row.fillRateTrend());
    map.put("cancel_rate_trend", row.cancelRateTrend());
    map.put("computed_at", row.computedAt().toString());
    return map;
  }

  @SuppressWarnings("unchecked")
  static SnapshotRow snapshotFromCache(Map<String, Object> map) {
    return new SnapshotRow(
        UUID.fromString((String) map.get("id")),
        UUID.fromString((String) map.get("pharmacy_id")),
        (String) map.get("period"),
        LocalDate.parse((String) map.get("period_start")),
        LocalDate.parse((String) map.get("period_end")),
        ((Number) map.get("orders_received")).intValue(),
        ((Number) map.get("orders_fulfilled")).intValue(),
        ((Number) map.get("orders_cancelled")).intValue(),
        new BigDecimal(map.get("fill_rate_pct").toString()),
        new BigDecimal(map.get("on_time_prep_pct").toString()),
        new BigDecimal(map.get("cancel_rate_pct").toString()),
        new BigDecimal(map.get("out_of_stock_rate_pct").toString()),
        new BigDecimal(map.get("avg_prep_minutes").toString()),
        ((Number) map.get("complaint_count")).intValue(),
        new BigDecimal(map.get("avg_rating").toString()),
        ((Number) map.get("review_count")).intValue(),
        ((Number) map.get("gmv_period_paise")).longValue(),
        ((Number) map.get("consecutive_low_fill_days")).shortValue(),
        (String) map.get("fill_rate_trend"),
        (String) map.get("cancel_rate_trend"),
        Instant.parse((String) map.get("computed_at")));
  }

  private void rateLimit(String key, int limit) {
    if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }

  private static void requirePerformanceRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_SUPPORT
        && role != AuthRole.ADMIN_COMPLIANCE) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireRatingsRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_SUPPORT) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireAlertRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER && role != AuthRole.ADMIN_OPERATIONS) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super or admin_operations may send alerts", 403);
    }
  }
}
