package com.nammamedmate.catalogue.application;

import com.nammamedmate.catalogue.domain.ScheduleRules;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ScheduleRulesService {

  private static final int LIMIT = 60;
  private static final int WINDOW = 60;

  private final RateLimiter rateLimiter;

  public ScheduleRulesService(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  public Map<String, Object> get(MedmatePrincipal principal) {
    requireScheduleRole(principal);
    if (!rateLimiter.tryAcquire(
        "admin:catalogue:schedule-rules:" + principal.subject(), LIMIT, WINDOW)) {
      throw new AppException("RATE_LIMIT_EXCEEDED", "Too many requests", 429);
    }
    return Map.of("schedules", ScheduleRules.all());
  }

  private static void requireScheduleRole(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    AuthRole role = principal.role();
    if (role != AuthRole.ADMIN_SUPER
        && role != AuthRole.ADMIN_OPERATIONS
        && role != AuthRole.ADMIN_COMPLIANCE
        && role != AuthRole.PHARMACY_OWNER
        && role != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Not allowed to view schedule rules", 403);
    }
  }
}
