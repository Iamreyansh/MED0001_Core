package com.nammamedmate.auth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.MedmatePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SessionListService {

  static final int LIST_LIMIT = 20;
  static final int LIST_WINDOW_SECONDS = 60;
  static final int DEFAULT_PAGE_SIZE = 20;
  static final int MAX_PAGE_SIZE = 100;

  private final AuthSessionStore sessionStore;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public SessionListService(
      AuthSessionStore sessionStore,
      RateLimiter rateLimiter,
      Clock clock,
      ObjectMapper objectMapper) {
    this.sessionStore = sessionStore;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  public record SessionListResult(List<Map<String, Object>> sessions, PaginationMeta meta) {
    public SessionListResult {
      sessions = List.copyOf(sessions);
    }
  }

  public SessionListResult list(MedmatePrincipal principal, Integer pageParam, Integer limitParam) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    String key = "auth:user:sessions:" + principal.subject() + ":count";
    if (!rateLimiter.tryAcquire(key, LIST_LIMIT, LIST_WINDOW_SECONDS)) {
      throw new AppException(
          "RATE_LIMITED",
          "Rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(key, LIST_LIMIT, LIST_WINDOW_SECONDS));
    }

    int page = pageParam == null || pageParam < 1 ? 1 : pageParam;
    int limit =
        limitParam == null || limitParam < 1
            ? DEFAULT_PAGE_SIZE
            : Math.min(limitParam, MAX_PAGE_SIZE);

    Instant now = clock.instant();
    long total = sessionStore.countActiveByUserId(principal.subject(), now);
    List<AuthSessionRecord> rows =
        sessionStore.listActiveByUserId(principal.subject(), now, page, limit);

    List<Map<String, Object>> data = new ArrayList<>(rows.size());
    for (AuthSessionRecord s : rows) {
      data.add(toView(s));
    }
    return new SessionListResult(data, PaginationMeta.of(page, limit, total));
  }

  private Map<String, Object> toView(AuthSessionRecord s) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("session_id", s.id());
    row.put("device", parseDevice(s.deviceInfoJson()));
    row.put("ip_address", s.ipAddress());
    row.put("country", s.country());
    row.put("city", s.city());
    row.put("user_agent", s.userAgent());
    row.put("created_at", s.createdAt());
    row.put("last_active_at", s.lastActiveAt());
    row.put("expires_at", s.expiresAt());
    // Access-token auth has no refresh token to hash-compare (story note). Always false here;
    // clients that store session_id at login can compare client-side.
    row.put("is_current", false);
    return row;
  }

  private Map<String, Object> parseDevice(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (Exception ex) {
      return Map.of();
    }
  }
}
