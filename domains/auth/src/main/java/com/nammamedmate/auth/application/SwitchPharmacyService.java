package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SwitchPharmacyService {

  static final long ACCESS_TTL_SECONDS = 900L;
  static final int SWITCH_RATE_LIMIT = 30;
  static final int SWITCH_RATE_WINDOW_SECONDS = 60;

  private final PharmacyAssignmentStore assignmentStore;
  private final PharmacyStore pharmacyStore;
  private final Rs256JwtService jwtService;
  private final RateLimiter rateLimiter;

  public SwitchPharmacyService(
      PharmacyAssignmentStore assignmentStore,
      PharmacyStore pharmacyStore,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter) {
    this.assignmentStore = assignmentStore;
    this.pharmacyStore = pharmacyStore;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
  }

  public SwitchPharmacyResult switchPharmacy(UUID staffId, UUID pharmacyId) {
    String rateKey = "pharmacy:switch:" + staffId + ":count";
    if (!rateLimiter.tryAcquire(rateKey, SWITCH_RATE_LIMIT, SWITCH_RATE_WINDOW_SECONDS)) {
      throw new AppException(
          "RATE_LIMITED",
          "Switch pharmacy rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(
              rateKey, SWITCH_RATE_LIMIT, SWITCH_RATE_WINDOW_SECONDS));
    }

    PharmacyRecord pharmacy =
        pharmacyStore
            .findById(pharmacyId)
            .orElseThrow(() -> new AppException("PHARMACY_NOT_FOUND", "Pharmacy not found", 404));

    var assignment =
        assignmentStore
            .findActive(staffId, pharmacyId)
            .orElseThrow(
                () -> new AppException("FORBIDDEN", "Staff is not assigned to this pharmacy", 403));

    AuthRole role =
        "pharmacy_owner".equals(assignment.roleCode())
            ? AuthRole.PHARMACY_OWNER
            : AuthRole.PHARMACY_STAFF;
    String accessToken =
        jwtService.issueAccessToken(
            new JwtClaims(staffId, role, pharmacyId, TokenScope.FULL, Ids.newId().toString()),
            ACCESS_TTL_SECONDS);

    return new SwitchPharmacyResult(
        accessToken, ACCESS_TTL_SECONDS, pharmacy, assignment.roleCode());
  }
}
