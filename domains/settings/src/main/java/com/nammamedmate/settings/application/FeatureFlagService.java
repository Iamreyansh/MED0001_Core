package com.nammamedmate.settings.application;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.port.out.AdminAuditAppendPort;
import com.nammamedmate.settings.application.port.out.FeatureFlagCachePort;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore.EnvCounts;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore.FeatureFlagRow;
import com.nammamedmate.settings.domain.FeatureFlagEnvironments;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureFlagService {

  private static final int LIST_LIMIT = 30;
  private static final int PATCH_LIMIT = 20;
  private static final int SUMMARY_LIMIT = 30;
  private static final int CHECK_LIMIT = 100;
  private static final int MINUTE = 60;
  private static final int NOTES_MAX = 500;

  private final FeatureFlagStore store;
  private final FeatureFlagCachePort cache;
  private final AdminAuditAppendPort audit;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final String writeRestrictedEnvironment;

  public FeatureFlagService(
      FeatureFlagStore store,
      FeatureFlagCachePort cache,
      AdminAuditAppendPort audit,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.feature-flags.write-environment:production}")
          String writeRestrictedEnvironment) {
    this.store = store;
    this.cache = cache;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.writeRestrictedEnvironment =
        writeRestrictedEnvironment == null || writeRestrictedEnvironment.isBlank()
            ? FeatureFlagEnvironments.PRODUCTION
            : writeRestrictedEnvironment.trim().toLowerCase(Locale.ROOT);
  }

  public record CheckResult(Map<String, Boolean> flags, Instant evaluatedAt) {
    public CheckResult {
      flags = flags == null ? Map.of() : Map.copyOf(flags);
    }
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(MedmatePrincipal principal, String environment) {
    requireAnyAdmin(principal);
    rateLimit("admin:feature-flags:list:" + principal.subject(), LIST_LIMIT, MINUTE);
    String env = normalizeEnvironment(environment);
    List<FeatureFlagRow> rows = store.listByEnvironment(env);
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (FeatureFlagRow row : rows) {
      out.add(toListItem(row));
    }
    return out;
  }

  @Transactional
  public Map<String, Object> update(
      MedmatePrincipal principal,
      String name,
      String environment,
      Boolean enabled,
      Integer rolloutPercentage,
      String notes) {
    requireAnyAdmin(principal);
    rateLimit("admin:feature-flags:patch:" + principal.subject(), PATCH_LIMIT, MINUTE);

    String env = normalizeEnvironment(environment);
    requireWriteAccess(principal, env);

    String flagName = requireFlagName(name);
    if (enabled == null && rolloutPercentage == null && notes == null) {
      throw new AppException("VALIDATION_ERROR", "At least one field is required", 400);
    }
    if (rolloutPercentage != null && (rolloutPercentage < 0 || rolloutPercentage > 100)) {
      throw new AppException(
          "VALIDATION_ERROR", "rollout_percentage must be between 0 and 100", 400);
    }
    String notesValue = notes;
    if (notesValue != null) {
      notesValue = notesValue.trim();
      if (notesValue.length() > NOTES_MAX) {
        throw new AppException("VALIDATION_ERROR", "notes max length is 500", 400);
      }
      if (notesValue.isEmpty()) {
        notesValue = null;
      }
    }

    FeatureFlagRow existing =
        store
            .findByNameAndEnvironment(flagName, env)
            .orElseThrow(
                () ->
                    new AppException("FLAG_NOT_FOUND", "No feature flag with the given name", 404));

    boolean nextEnabled = enabled != null ? enabled : existing.enabled();
    int nextRollout = rolloutPercentage != null ? rolloutPercentage : existing.rolloutPercentage();
    String nextNotes = notes != null ? notesValue : existing.notes();

    Map<String, Object> before = auditState(existing);
    Instant now = clock.instant();
    FeatureFlagRow updated =
        store.update(existing.id(), nextEnabled, nextRollout, nextNotes, principal.subject(), now);
    Map<String, Object> after = auditState(updated);

    audit.append(
        "feature_flag",
        principal.subject(),
        principal.role().value(),
        existing.id(),
        "feature_flag.updated",
        before,
        after);

    cache.invalidate(env);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("name", updated.name());
    data.put("enabled", updated.enabled());
    data.put("rollout_percentage", updated.rolloutPercentage());
    data.put("environment", updated.environment());
    data.put("updated_by", principal.subject().toString());
    data.put("updated_at", updated.updatedAt());
    data.put("notes", updated.notes());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(MedmatePrincipal principal) {
    requireAnyAdmin(principal);
    rateLimit("admin:feature-flags:summary:" + principal.subject(), SUMMARY_LIMIT, MINUTE);

    List<FeatureFlagRow> all = store.listAll();
    long enabled = 0;
    long disabled = 0;
    long partial = 0;
    for (FeatureFlagRow row : all) {
      if (!FeatureFlagEnvironments.PRODUCTION.equals(row.environment())) {
        continue;
      }
      if (row.enabled()) {
        enabled++;
        if (row.rolloutPercentage() >= 1 && row.rolloutPercentage() <= 99) {
          partial++;
        }
      } else {
        disabled++;
      }
    }
    long total = enabled + disabled;

    Map<String, Object> environments = new LinkedHashMap<>();
    for (String env :
        List.of(
            FeatureFlagEnvironments.PRODUCTION,
            FeatureFlagEnvironments.STAGING,
            FeatureFlagEnvironments.DEVELOPMENT)) {
      environments.put(env, Map.of("total", 0L, "enabled", 0L));
    }
    for (EnvCounts c : store.countByEnvironment()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("total", c.total());
      entry.put("enabled", c.enabled());
      environments.put(c.environment(), entry);
    }

    // Prefer production totals for top-level when present; else sum from production loop.
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("total", total);
    data.put("enabled", enabled);
    data.put("disabled", disabled);
    data.put("partial_rollout", partial);
    data.put("environments", environments);
    return data;
  }

  @Transactional(readOnly = true)
  public CheckResult check(String flagsCsv, String environment, String clientIp) {
    String ip = clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp.trim();
    rateLimit("public:feature-flags:check:" + ip, CHECK_LIMIT, MINUTE);

    if (flagsCsv == null || flagsCsv.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "flags param is required", 400);
    }
    String env = normalizeEnvironment(environment);
    String[] names = flagsCsv.split(",");
    List<String> requested = new ArrayList<>();
    for (String raw : names) {
      String trimmed = raw.trim();
      if (!trimmed.isEmpty()) {
        requested.add(trimmed);
      }
    }
    if (requested.isEmpty()) {
      throw new AppException("VALIDATION_ERROR", "flags param is required", 400);
    }

    Map<String, Boolean> enabledByName = loadEnabledMap(env);
    Map<String, Boolean> data = new LinkedHashMap<>();
    for (String name : requested) {
      data.put(name, enabledByName.getOrDefault(name, false));
    }
    return new CheckResult(data, clock.instant());
  }

  private Map<String, Boolean> loadEnabledMap(String environment) {
    Optional<Map<String, Boolean>> cached = cache.get(environment);
    if (cached.isPresent()) {
      return cached.get();
    }
    List<FeatureFlagRow> rows = store.listByEnvironment(environment);
    Map<String, Boolean> map = new LinkedHashMap<>();
    for (FeatureFlagRow row : rows) {
      map.put(row.name(), row.enabled());
    }
    cache.put(environment, map);
    return map;
  }

  private void requireWriteAccess(MedmatePrincipal principal, String environment) {
    if (writeRestrictedEnvironment.equals(environment)
        && principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException(
          "FORBIDDEN", "Only admin_super can modify flags in " + environment, 403);
    }
  }

  private static Map<String, Object> toListItem(FeatureFlagRow row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", row.name());
    item.put("description", row.description());
    item.put("enabled", row.enabled());
    item.put("environment", row.environment());
    item.put("rollout_percentage", row.rolloutPercentage());
    if (row.updatedBy() != null) {
      Map<String, Object> by = new LinkedHashMap<>();
      by.put("id", row.updatedBy());
      by.put("name", row.updatedByName() == null ? "" : row.updatedByName());
      item.put("updated_by", by);
    } else {
      item.put("updated_by", null);
    }
    item.put("updated_at", row.updatedAt());
    item.put("notes", row.notes());
    return item;
  }

  private static Map<String, Object> auditState(FeatureFlagRow row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("name", row.name());
    state.put("environment", row.environment());
    state.put("enabled", row.enabled());
    state.put("rollout_percentage", row.rolloutPercentage());
    state.put("notes", row.notes());
    return state;
  }

  private static String normalizeEnvironment(String environment) {
    if (environment == null || environment.isBlank()) {
      return FeatureFlagEnvironments.PRODUCTION;
    }
    String env = environment.trim().toLowerCase(Locale.ROOT);
    if (!FeatureFlagEnvironments.isValid(env)) {
      throw new AppException(
          "VALIDATION_ERROR", "environment must be production, staging, or development", 400);
    }
    return env;
  }

  private static String requireFlagName(String name) {
    if (name == null || name.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "flag name is required", 400);
    }
    return name.trim();
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
}
