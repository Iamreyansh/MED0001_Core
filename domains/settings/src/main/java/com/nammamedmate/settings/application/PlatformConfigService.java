package com.nammamedmate.settings.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.application.port.out.PlatformConfigCachePort;
import com.nammamedmate.settings.application.port.out.PlatformConfigReadPort;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.ConfigRow;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.HistoryRow;
import com.nammamedmate.settings.domain.ConfigValueTypes;
import com.nammamedmate.settings.domain.FeatureFlagEnvironments;
import com.nammamedmate.settings.domain.PlatformConfigDomains;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformConfigService implements PlatformConfigReadPort {

  private static final int LIST_LIMIT = 30;
  private static final int PATCH_LIMIT = 10;
  private static final int GET_LIMIT = 60;
  private static final int MINUTE = 60;

  private final PlatformConfigStore store;
  private final PlatformConfigCachePort cache;
  private final AdminAuditAppendPort audit;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final String environment;

  public PlatformConfigService(
      PlatformConfigStore store,
      PlatformConfigCachePort cache,
      AdminAuditAppendPort audit,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.platform-config.environment:production}") String environment) {
    this.store = store;
    this.cache = cache;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.environment =
        environment == null || environment.isBlank()
            ? FeatureFlagEnvironments.PRODUCTION
            : environment.trim().toLowerCase(Locale.ROOT);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> list(MedmatePrincipal principal, String domain) {
    requireAnyAdmin(principal);
    rateLimit("admin:config:list:" + principal.subject(), LIST_LIMIT, MINUTE);

    String filter = normalizeDomain(domain);
    List<ConfigRow> rows = loadAll();
    Map<String, Object> data = new LinkedHashMap<>();
    for (ConfigRow row : rows) {
      if (filter != null && !filter.equals(row.domain())) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> domainMap =
          (Map<String, Object>) data.computeIfAbsent(row.domain(), d -> new LinkedHashMap<>());
      domainMap.put(shortKey(row.key()), toListItem(row));
    }
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, String key) {
    requireAnyAdmin(principal);
    rateLimit("admin:config:get:" + principal.subject(), GET_LIMIT, MINUTE);

    String fullKey = requireKey(key);
    ConfigRow row =
        findCached(fullKey)
            .orElseThrow(
                () -> new AppException("CONFIG_KEY_NOT_FOUND", "Config key does not exist", 404));

    List<HistoryRow> history = store.listHistory(fullKey);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("key", row.key());
    data.put("value", typedValue(row));
    data.put("type", row.type());
    data.put("unit", row.unit());
    data.put("immutable", row.immutable());
    data.put("description", row.description());
    List<Map<String, Object>> historyOut = new ArrayList<>(history.size());
    for (HistoryRow h : history) {
      historyOut.add(toHistoryItem(h, row.type()));
    }
    data.put("history", historyOut);
    return data;
  }

  @Transactional
  public Map<String, Object> bulkUpdate(MedmatePrincipal principal, Map<String, Object> updates) {
    requireAnyAdmin(principal);
    rateLimit("admin:config:patch:" + principal.subject(), PATCH_LIMIT, MINUTE);
    if (principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "Only admin_super can modify platform config", 403);
    }
    if (updates == null || updates.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "At least one config key is required", 400);
    }

    List<ConfigRow> current = loadAll();
    Map<String, ConfigRow> byKey = new LinkedHashMap<>();
    for (ConfigRow row : current) {
      byKey.put(row.key(), row);
    }

    List<PendingUpdate> pending = new ArrayList<>();
    boolean hasImmutable = false;
    boolean hasMissing = false;
    for (Map.Entry<String, Object> entry : updates.entrySet()) {
      String key = requireKey(entry.getKey());
      ConfigRow row = byKey.get(key);
      if (row == null) {
        hasMissing = true;
        continue;
      }
      if (row.immutable() && FeatureFlagEnvironments.PRODUCTION.equals(environment)) {
        hasImmutable = true;
        continue;
      }
      String serialized = ConfigValueTypes.validateAndSerialize(key, row.type(), entry.getValue());
      pending.add(new PendingUpdate(row, serialized));
    }
    if (hasImmutable) {
      throw new AppException(
          "CONFIG_KEY_IMMUTABLE", "One or more keys are immutable in production", 422);
    }
    if (hasMissing) {
      throw new AppException("CONFIG_KEY_NOT_FOUND", "One or more keys do not exist", 422);
    }

    Instant now = clock.instant();
    List<String> updatedKeys = new ArrayList<>(pending.size());
    for (PendingUpdate p : pending) {
      ConfigRow before = p.row();
      store.updateValue(before.key(), p.newValue(), principal.subject(), now);
      store.insertHistory(
          Ids.newId(), before.key(), before.value(), p.newValue(), principal.subject(), now, null);
      Map<String, Object> beforeState = auditState(before);
      Map<String, Object> afterState = new LinkedHashMap<>(beforeState);
      afterState.put("value", p.newValue());
      audit.append(
          "platform_config",
          principal.subject(),
          principal.role().value(),
          entityIdForKey(before.key()),
          "platform_config.updated",
          beforeState,
          afterState);
      updatedKeys.add(before.key());
    }

    cache.invalidate();

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("updated_keys", updatedKeys);
    data.put("updated_count", updatedKeys.size());
    data.put("cache_invalidated", true);
    data.put("effective_at", now);
    return data;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Object> getTyped(String key) {
    return findCached(key).map(this::typedValue);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> getRaw(String key) {
    return findCached(key).map(ConfigRow::value);
  }

  private List<ConfigRow> loadAll() {
    Optional<List<ConfigRow>> cached = cache.getAll();
    if (cached.isPresent()) {
      return cached.get();
    }
    List<ConfigRow> rows = store.listAll();
    cache.putAll(rows);
    return rows;
  }

  private Optional<ConfigRow> findCached(String key) {
    if (key == null || key.isBlank()) {
      return Optional.empty();
    }
    for (ConfigRow row : loadAll()) {
      if (key.equals(row.key())) {
        return Optional.of(row);
      }
    }
    return Optional.empty();
  }

  private static String normalizeDomain(String domain) {
    if (domain == null || domain.isBlank()) {
      return null;
    }
    String d = domain.trim().toLowerCase(Locale.ROOT);
    if (!PlatformConfigDomains.isValid(d)) {
      throw new AppException(
          "VALIDATION_ERROR", "domain must be orders, payments, commissions, kyc, or rider", 400);
    }
    return d;
  }

  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Config key is required", 400);
    }
    String trimmed = key.trim();
    if (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "Config key is required", 400);
    }
    return trimmed;
  }

  private Object typedValue(ConfigRow row) {
    try {
      return ConfigValueTypes.parse(row.type(), row.value());
    } catch (RuntimeException ex) {
      return row.value();
    }
  }

  private static Map<String, Object> toListItem(ConfigRow row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("key", row.key());
    try {
      item.put("value", ConfigValueTypes.parse(row.type(), row.value()));
    } catch (RuntimeException ex) {
      item.put("value", row.value());
    }
    item.put("type", row.type());
    item.put("unit", row.unit());
    item.put("immutable", row.immutable());
    item.put("description", row.description());
    item.put("updated_at", row.updatedAt());
    return item;
  }

  private Map<String, Object> toHistoryItem(HistoryRow h, String type) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("old_value", parseHistoryValue(type, h.oldValue()));
    item.put("new_value", parseHistoryValue(type, h.newValue()));
    Map<String, Object> by = new LinkedHashMap<>();
    by.put("id", h.changedBy());
    by.put("name", h.changedByName() == null ? "" : h.changedByName());
    item.put("changed_by", by);
    item.put("changed_at", h.changedAt());
    item.put("notes", h.notes());
    return item;
  }

  private static Object parseHistoryValue(String type, String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return ConfigValueTypes.parse(type, raw);
    } catch (RuntimeException ex) {
      return raw;
    }
  }

  private static Map<String, Object> auditState(ConfigRow row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("key", row.key());
    state.put("value", row.value());
    state.put("type", row.type());
    state.put("domain", row.domain());
    state.put("immutable", row.immutable());
    return state;
  }

  private static String shortKey(String fullKey) {
    int dot = fullKey.indexOf('.');
    return dot >= 0 && dot < fullKey.length() - 1 ? fullKey.substring(dot + 1) : fullKey;
  }

  static UUID entityIdForKey(String key) {
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
  }

  private void requireAnyAdmin(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    if (!isAdmin(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin access required", 403);
    }
  }

  private static boolean isAdmin(AuthRole role) {
    return role == AuthRole.ADMIN_SUPER
        || role == AuthRole.ADMIN_OPERATIONS
        || role == AuthRole.ADMIN_FINANCE
        || role == AuthRole.ADMIN_SUPPORT
        || role == AuthRole.ADMIN_COMPLIANCE;
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private record PendingUpdate(ConfigRow row, String newValue) {}
}
