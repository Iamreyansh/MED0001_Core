package com.nammamedmate.integration.application;

import com.nammamedmate.integration.application.port.in.CommunicationChannelLookupPort;
import com.nammamedmate.integration.application.port.out.CommunicationChannelConfigStore;
import com.nammamedmate.integration.application.port.out.CommunicationConfigAuditStore;
import com.nammamedmate.integration.application.port.out.CommunicationCostDailyStore;
import com.nammamedmate.integration.application.port.out.CommunicationProviderPort;
import com.nammamedmate.integration.application.port.out.CommunicationSecretsStore;
import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.integration.domain.CommunicationChannelConfig;
import com.nammamedmate.integration.domain.CommunicationChannels;
import com.nammamedmate.integration.domain.CommunicationConfigAudit;
import com.nammamedmate.integration.domain.CommunicationCostDaily;
import com.nammamedmate.integration.domain.CommunicationCredentialMask;
import com.nammamedmate.integration.domain.CommunicationProviders;
import com.nammamedmate.integration.domain.CommunicationRates;
import com.nammamedmate.integration.domain.CommunicationStatuses;
import com.nammamedmate.integration.domain.CommunicationTemplates;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CommunicationService implements CommunicationChannelLookupPort {

  private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal LIMIT_WARN_PCT = new BigDecimal("80");
  private static final Set<AuthRole> READ_ROLES =
      Set.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS);

  private final CommunicationChannelConfigStore configs;
  private final CommunicationCostDailyStore costs;
  private final CommunicationConfigAuditStore audits;
  private final CommunicationSecretsStore secrets;
  private final CommunicationProviderPort providers;
  private final IntegrationEventPort events;
  private final Clock clock;
  private final ConcurrentHashMap<String, Instant> lastSuccessfulSend = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> limitWarnedToday = new ConcurrentHashMap<>();
  private volatile LocalDate lastResetIstDate;

  public CommunicationService(
      CommunicationChannelConfigStore configs,
      CommunicationCostDailyStore costs,
      CommunicationConfigAuditStore audits,
      CommunicationSecretsStore secrets,
      CommunicationProviderPort providers,
      IntegrationEventPort events,
      Clock clock) {
    this.configs = configs;
    this.costs = costs;
    this.audits = audits;
    this.secrets = secrets;
    this.providers = providers;
    this.events = events;
    this.clock = clock;
  }

  public Map<String, Object> status(MedmatePrincipal principal) {
    requireRead(principal);
    ensureDailyReset();
    Instant now = Instant.now(clock);
    List<Map<String, Object>> channels = new ArrayList<>();
    String overall = CommunicationStatuses.HEALTHY;
    for (CommunicationChannelConfig cfg : configs.findAll()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("channel", cfg.channel());
      row.put("provider", cfg.provider());
      row.put("fallback_provider", cfg.fallbackProvider());
      row.put("status", cfg.currentStatus());
      row.put("last_successful_send", iso(lastSuccessfulSend.get(cfg.channel())));
      Rates24h rates = rates24h(cfg.channel());
      row.put("error_rate_pct_24h", rates.errorRatePct());
      row.put("delivery_rate_pct_24h", rates.deliveryRatePct());
      row.put("last_health_check_at", iso(cfg.lastHealthCheckAt()));
      channels.add(row);
      if (!CommunicationStatuses.isHealthy(cfg.currentStatus())) {
        overall = CommunicationStatuses.DEGRADED;
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("channels", channels);
    data.put("overall_status", overall);
    data.put("as_of", now.toString());
    return data;
  }

  public Map<String, Object> testSend(
      MedmatePrincipal principal, String channel, String recipient, String testTemplate) {
    requireRead(principal);
    ensureDailyReset();
    String ch = requireChannel(channel, "INVALID_CHANNEL", 400);
    if (recipient == null || recipient.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "recipient is required", 400);
    }
    String template = CommunicationTemplates.normalize(testTemplate);
    if (!CommunicationTemplates.isValid(ch, template)) {
      throw new AppException("TEMPLATE_NOT_FOUND", "test_template not found for this channel", 422);
    }
    CommunicationChannelConfig cfg =
        configs
            .findByChannel(ch)
            .orElseThrow(() -> new AppException("CHANNEL_NOT_FOUND", "Unknown channel", 404));

    ResolvedRoute route = resolveRoute(cfg);
    if (route.provider() == null) {
      throw new AppException("PROVIDER_UNAVAILABLE", "Channel disabled with no fallback", 503);
    }

    CommunicationProviderPort.SendResult result;
    try {
      result = providers.sendTest(route.provider(), ch, recipient.trim(), template, true);
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("PROVIDER_UNAVAILABLE", "Provider returned error on test send", 503);
    }
    if (result == null || !"SENT".equals(result.status())) {
      throw new AppException("PROVIDER_UNAVAILABLE", "Provider returned error on test send", 503);
    }

    Instant now = Instant.now(clock);
    lastSuccessfulSend.put(ch, now);
    int nextCount = cfg.dailySentCount() + 1;
    BigDecimal cost = CommunicationRates.cost(ch, template, 1);
    costs.upsertIncrement(todayIst(), ch, route.provider(), 1, 1, route.fallback() ? 1 : 0, cost);
    configs.update(
        new CommunicationChannelConfig(
            cfg.channel(),
            cfg.enabled(),
            cfg.provider(),
            cfg.fallbackProvider(),
            cfg.secretsManagerKey(),
            cfg.dailySendLimit(),
            nextCount,
            cfg.currentStatus(),
            cfg.lastHealthCheckAt(),
            cfg.updatedBy(),
            now));
    maybeWarnLimit(cfg, nextCount);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("channel", ch);
    data.put("provider", route.provider());
    data.put("recipient", recipient.trim());
    data.put("status", "SENT");
    data.put("is_test", true);
    data.put("log_id", result.logId().toString());
    data.put("sent_at", now.toString());
    return data;
  }

  public Map<String, Object> usage(MedmatePrincipal principal, String channelFilter) {
    requireRead(principal);
    ensureDailyReset();
    String filter =
        channelFilter == null || channelFilter.isBlank()
            ? null
            : requireChannel(channelFilter, "INVALID_CHANNEL", 400);
    LocalDate today = todayIst();
    LocalDate monthStart = today.withDayOfMonth(1);
    List<Map<String, Object>> usage = new ArrayList<>();
    for (CommunicationChannelConfig cfg : configs.findAll()) {
      if (filter != null && !filter.equals(cfg.channel())) {
        continue;
      }
      List<CommunicationCostDaily> todayRows =
          costs.findByDate(today).stream().filter(r -> r.channel().equals(cfg.channel())).toList();
      List<CommunicationCostDaily> monthRows =
          costs.findByChannelAndDateRange(cfg.channel(), monthStart, today);
      int sentToday = todayRows.stream().mapToInt(CommunicationCostDaily::sentCount).sum();
      int fallbackToday =
          todayRows.stream().mapToInt(CommunicationCostDaily::fallbackSentCount).sum();
      int deliveredToday =
          todayRows.stream().mapToInt(CommunicationCostDaily::deliveredCount).sum();
      BigDecimal costToday =
          todayRows.stream()
              .map(CommunicationCostDaily::costRs)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .setScale(2, RoundingMode.HALF_UP);
      int sentMonth = monthRows.stream().mapToInt(CommunicationCostDaily::sentCount).sum();
      BigDecimal costMonth =
          monthRows.stream()
              .map(CommunicationCostDaily::costRs)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .setScale(2, RoundingMode.HALF_UP);
      // Prefer live counter; fall back to cost rows if counter was reset mid-day in tests.
      int effectiveSentToday = Math.max(cfg.dailySentCount(), sentToday);
      BigDecimal limitPct =
          cfg.dailySendLimit() <= 0
              ? BigDecimal.ZERO
              : BigDecimal.valueOf(effectiveSentToday)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(cfg.dailySendLimit()), 1, RoundingMode.HALF_UP);
      BigDecimal deliveryPct =
          sentToday == 0
              ? BigDecimal.valueOf(100).setScale(1, RoundingMode.HALF_UP)
              : BigDecimal.valueOf(deliveredToday)
                  .multiply(BigDecimal.valueOf(100))
                  .divide(BigDecimal.valueOf(sentToday), 1, RoundingMode.HALF_UP);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("channel", cfg.channel());
      row.put("provider", cfg.provider());
      row.put("sent_today", effectiveSentToday);
      row.put("sent_month", Math.max(sentMonth, effectiveSentToday));
      row.put("cost_today_rs", costToday);
      row.put("cost_month_rs", costMonth.max(costToday));
      row.put("daily_limit", cfg.dailySendLimit());
      row.put("daily_limit_pct_used", limitPct.doubleValue());
      row.put("delivery_rate_pct", deliveryPct.doubleValue());
      row.put("fallback_sent_today", fallbackToday);
      usage.add(row);
    }
    return Map.of("usage", usage);
  }

  public Map<String, Object> patchConfig(
      MedmatePrincipal principal, String channel, Map<String, Object> body) {
    requireSuper(principal);
    ensureDailyReset();
    String ch = requireChannel(channel, "CHANNEL_NOT_FOUND", 404);
    CommunicationChannelConfig cfg =
        configs
            .findByChannel(ch)
            .orElseThrow(() -> new AppException("CHANNEL_NOT_FOUND", "Unknown channel", 404));
    if (body == null) {
      body = Map.of();
    }

    Map<String, Object> changed = new LinkedHashMap<>();
    boolean enabled = cfg.enabled();
    if (body.containsKey("is_enabled")) {
      enabled = Boolean.TRUE.equals(body.get("is_enabled"));
      if (enabled != cfg.enabled()) {
        changed.put("is_enabled", Map.of("from", cfg.enabled(), "to", enabled));
      }
    }
    String provider = cfg.provider();
    if (body.get("provider") != null) {
      String next = CommunicationProviders.normalize(String.valueOf(body.get("provider")));
      if (!CommunicationProviders.isValid(next)) {
        throw new AppException("VALIDATION_ERROR", "Invalid provider", 422);
      }
      if (!next.equals(cfg.provider())) {
        changed.put("provider", Map.of("from", cfg.provider(), "to", next));
        provider = next;
      }
    }
    String fallback = cfg.fallbackProvider();
    if (body.containsKey("fallback_provider")) {
      Object raw = body.get("fallback_provider");
      String next =
          raw == null || String.valueOf(raw).isBlank()
              ? null
              : CommunicationProviders.normalize(String.valueOf(raw));
      if (next != null && !CommunicationProviders.isValid(next)) {
        throw new AppException("VALIDATION_ERROR", "Invalid fallback_provider", 422);
      }
      if ((next == null && cfg.fallbackProvider() != null)
          || (next != null && !next.equals(cfg.fallbackProvider()))) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("from", cfg.fallbackProvider());
        diff.put("to", next);
        changed.put("fallback_provider", diff);
        fallback = next;
      }
    }
    int dailyLimit = cfg.dailySendLimit();
    if (body.get("daily_send_limit") != null) {
      int next = toInt(body.get("daily_send_limit"));
      if (next < 0) {
        throw new AppException("VALIDATION_ERROR", "daily_send_limit must be >= 0", 422);
      }
      if (next != cfg.dailySendLimit()) {
        changed.put("daily_send_limit", Map.of("from", cfg.dailySendLimit(), "to", next));
        dailyLimit = next;
      }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> credObj =
        body.get("api_credentials") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    String connectivityResult = "SKIPPED";
    String apiKeyPreview =
        CommunicationCredentialMask.apiKeyPreview(
            secrets.get(cfg.secretsManagerKey()).map(s -> s.get("api_key")).orElse(null));

    if (credObj != null && !credObj.isEmpty()) {
      Map<String, String> candidate = toStringMap(credObj);
      boolean ok = providers.connectivityTest(provider, candidate);
      if (!ok) {
        audits.insert(
            new CommunicationConfigAudit(
                UUID.randomUUID(),
                ch,
                principal.subject(),
                Map.of(
                    "api_credentials",
                    Map.of(
                        "api_key",
                        CommunicationCredentialMask.apiKeyPreview(candidate.get("api_key")))),
                "FAILED",
                Instant.now(clock)));
        throw new AppException(
            "CONNECTIVITY_TEST_FAILED", "New credentials failed connectivity test", 422);
      }
      connectivityResult = "PASSED";
      Map<String, String> maskedAudit = new LinkedHashMap<>();
      candidate.forEach(
          (k, v) ->
              maskedAudit.put(
                  k, "api_key".equals(k) ? CommunicationCredentialMask.apiKeyPreview(v) : v));
      changed.put("api_credentials", maskedAudit);
      secrets.put(cfg.secretsManagerKey(), candidate);
      apiKeyPreview = CommunicationCredentialMask.apiKeyPreview(candidate.get("api_key"));
    }

    if (changed.isEmpty()) {
      // no-op patch still returns SKIPPED connectivity (credentials path always mutates changed)
      connectivityResult = "SKIPPED";
    }

    Instant now = Instant.now(clock);
    CommunicationChannelConfig updated =
        new CommunicationChannelConfig(
            cfg.channel(),
            enabled,
            provider,
            fallback,
            cfg.secretsManagerKey(),
            dailyLimit,
            cfg.dailySentCount(),
            cfg.currentStatus(),
            cfg.lastHealthCheckAt(),
            principal.subject(),
            now);
    configs.update(updated);
    audits.insert(
        new CommunicationConfigAudit(
            UUID.randomUUID(), ch, principal.subject(), changed, connectivityResult, now));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("channel", ch);
    data.put("is_enabled", enabled);
    data.put("provider", provider);
    data.put("fallback_provider", fallback);
    data.put("api_key_preview", apiKeyPreview);
    data.put("daily_send_limit", dailyLimit);
    data.put("connectivity_test_result", connectivityResult);
    data.put("updated_by", principal.subject().toString());
    data.put("updated_at", now.toString());
    return data;
  }

  /** Health check every 5 minutes (scheduler). */
  public void runHealthChecks() {
    ensureDailyReset();
    Instant now = Instant.now(clock);
    for (CommunicationChannelConfig cfg : configs.findAll()) {
      boolean pingOk = providers.healthPing(cfg.provider());
      Rates24h rates = rates24h(cfg.channel());
      String status;
      if (!pingOk) {
        status = CommunicationStatuses.DOWN;
      } else if (rates.deliveryRatePct() < 95.0) {
        status = CommunicationStatuses.DEGRADED;
      } else {
        Instant last = lastSuccessfulSend.get(cfg.channel());
        if (last != null && last.isBefore(now.minusSeconds(30 * 60)) && rates.sent() > 0) {
          status = CommunicationStatuses.DOWN;
        } else {
          status = CommunicationStatuses.HEALTHY;
        }
      }
      configs.update(
          new CommunicationChannelConfig(
              cfg.channel(),
              cfg.enabled(),
              cfg.provider(),
              cfg.fallbackProvider(),
              cfg.secretsManagerKey(),
              cfg.dailySendLimit(),
              cfg.dailySentCount(),
              status,
              now,
              cfg.updatedBy(),
              cfg.updatedAt()));
    }
  }

  /** Midnight IST reset (scheduler) + lazy on access. */
  public void resetDailySentCounts() {
    configs.resetAllDailySentCounts();
    lastResetIstDate = todayIst();
    limitWarnedToday.clear();
  }

  void ensureDailyReset() {
    LocalDate today = todayIst();
    if (!today.equals(lastResetIstDate)) {
      // First access after process start on a new IST day: reset counters.
      if (lastResetIstDate != null) {
        configs.resetAllDailySentCounts();
        limitWarnedToday.clear();
      }
      lastResetIstDate = today;
    }
  }

  @Override
  public Optional<ChannelSnapshot> find(String channel) {
    ensureDailyReset();
    if (!CommunicationChannels.isValid(channel)) {
      return Optional.empty();
    }
    return configs
        .findByChannel(CommunicationChannels.normalize(channel))
        .map(
            c ->
                new ChannelSnapshot(
                    c.channel(),
                    c.enabled(),
                    c.provider(),
                    c.fallbackProvider(),
                    c.currentStatus(),
                    c.dailySendLimit(),
                    c.dailySentCount()));
  }

  @Override
  public Optional<String> resolveActiveProvider(String channel) {
    ensureDailyReset();
    // Optional.map already drops null provider from disabled channels with no fallback.
    return configs
        .findByChannel(CommunicationChannels.normalize(channel))
        .map(this::resolveRoute)
        .map(ResolvedRoute::provider);
  }

  private ResolvedRoute resolveRoute(CommunicationChannelConfig cfg) {
    if (!cfg.enabled()) {
      if (cfg.fallbackProvider() != null) {
        return new ResolvedRoute(cfg.fallbackProvider(), true);
      }
      log.info("CHANNEL_DISABLED channel={}", cfg.channel());
      return new ResolvedRoute(null, false);
    }
    if (CommunicationStatuses.DOWN.equals(cfg.currentStatus()) && cfg.fallbackProvider() != null) {
      return new ResolvedRoute(cfg.fallbackProvider(), true);
    }
    return new ResolvedRoute(cfg.provider(), false);
  }

  private void maybeWarnLimit(CommunicationChannelConfig cfg, int nextCount) {
    if (cfg.dailySendLimit() <= 0) {
      return;
    }
    BigDecimal pct =
        BigDecimal.valueOf(nextCount)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(cfg.dailySendLimit()), 1, RoundingMode.HALF_UP);
    if (pct.compareTo(LIMIT_WARN_PCT) < 0) {
      return;
    }
    String key = cfg.channel() + ":" + todayIst();
    if (Boolean.TRUE.equals(limitWarnedToday.putIfAbsent(key, true))) {
      return;
    }
    log.warn(
        "CHANNEL_LIMIT_WARNING channel={} pct_used={} daily_limit={}",
        cfg.channel(),
        pct,
        cfg.dailySendLimit());
    events.publish(
        "integration.comms.channel_limit_warning",
        "communication_channel",
        UUID.nameUUIDFromBytes(cfg.channel().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        Map.of(
            "alert_type",
            "CHANNEL_LIMIT_WARNING",
            "channel",
            cfg.channel(),
            "daily_limit_pct_used",
            pct.doubleValue(),
            "daily_limit",
            cfg.dailySendLimit(),
            "daily_sent_count",
            nextCount));
  }

  private Rates24h rates24h(String channel) {
    LocalDate today = todayIst();
    LocalDate from = today.minusDays(1);
    List<CommunicationCostDaily> rows = costs.findByChannelAndDateRange(channel, from, today);
    int sent = rows.stream().mapToInt(CommunicationCostDaily::sentCount).sum();
    int delivered = rows.stream().mapToInt(CommunicationCostDaily::deliveredCount).sum();
    if (sent == 0) {
      return new Rates24h(0, 100.0, 0.0);
    }
    double delivery =
        BigDecimal.valueOf(delivered)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(sent), 1, RoundingMode.HALF_UP)
            .doubleValue();
    double error =
        BigDecimal.valueOf(100)
            .subtract(BigDecimal.valueOf(delivery))
            .setScale(1, RoundingMode.HALF_UP)
            .doubleValue();
    return new Rates24h(sent, delivery, error);
  }

  private LocalDate todayIst() {
    return LocalDate.now(clock.withZone(IST));
  }

  private static String requireChannel(String channel, String code, int status) {
    if (!CommunicationChannels.isValid(channel)) {
      throw new AppException(code, "channel not in SMS/WHATSAPP/PUSH/EMAIL", status);
    }
    return CommunicationChannels.normalize(channel);
  }

  private static void requireRead(MedmatePrincipal principal) {
    if (principal == null || !READ_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "admin_super or admin_operations required", 403);
    }
  }

  private static void requireSuper(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.ADMIN_SUPER) {
      throw new AppException("FORBIDDEN", "admin_super required", 403);
    }
  }

  private static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  private static int toInt(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(String.valueOf(value));
  }

  private static Map<String, String> toStringMap(Map<String, Object> raw) {
    Map<String, String> out = new LinkedHashMap<>();
    raw.forEach(
        (k, v) -> {
          if (v != null) {
            out.put(k, String.valueOf(v));
          }
        });
    return out;
  }

  private record ResolvedRoute(String provider, boolean fallback) {}

  private record Rates24h(int sent, double deliveryRatePct, double errorRatePct) {}
}
