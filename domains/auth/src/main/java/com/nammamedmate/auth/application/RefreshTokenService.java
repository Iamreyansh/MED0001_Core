package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.CustomerStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.application.port.out.RiderAccountPort;
import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;
import com.nammamedmate.auth.domain.RefreshTokens;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.messaging.DomainEvent;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefreshTokenService {

  static final int REFRESH_IP_LIMIT = 30;
  static final int REFRESH_IP_WINDOW_SECONDS = 60;
  static final long ACCESS_TTL_SECONDS = 900L;

  private final AuthSessionStore sessionStore;
  private final CustomerStore customerStore;
  private final PharmacyStaffStore pharmacyStaffStore;
  private final PharmacyAssignmentStore assignmentStore;
  private final AdminStaffStore adminStaffStore;
  private final RiderAccountPort riderAccountPort;
  private final Rs256JwtService jwtService;
  private final RateLimiter rateLimiter;
  private final OutboxPublisher outboxPublisher;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final TransactionTemplate reuseTx;

  @Autowired
  public RefreshTokenService(
      AuthSessionStore sessionStore,
      CustomerStore customerStore,
      PharmacyStaffStore pharmacyStaffStore,
      PharmacyAssignmentStore assignmentStore,
      AdminStaffStore adminStaffStore,
      RiderAccountPort riderAccountPort,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      OutboxPublisher outboxPublisher,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this(
        sessionStore,
        customerStore,
        pharmacyStaffStore,
        assignmentStore,
        adminStaffStore,
        riderAccountPort,
        jwtService,
        rateLimiter,
        outboxPublisher,
        clock,
        new SecureRandom(),
        requiresNew(transactionManager));
  }

  RefreshTokenService(
      AuthSessionStore sessionStore,
      CustomerStore customerStore,
      PharmacyStaffStore pharmacyStaffStore,
      PharmacyAssignmentStore assignmentStore,
      AdminStaffStore adminStaffStore,
      RiderAccountPort riderAccountPort,
      Rs256JwtService jwtService,
      RateLimiter rateLimiter,
      OutboxPublisher outboxPublisher,
      Clock clock,
      SecureRandom secureRandom,
      TransactionTemplate reuseTx) {
    this.sessionStore = sessionStore;
    this.customerStore = customerStore;
    this.pharmacyStaffStore = pharmacyStaffStore;
    this.assignmentStore = assignmentStore;
    this.adminStaffStore = adminStaffStore;
    this.riderAccountPort = riderAccountPort;
    this.jwtService = jwtService;
    this.rateLimiter = rateLimiter;
    this.outboxPublisher = outboxPublisher;
    this.clock = clock;
    this.secureRandom = secureRandom;
    this.reuseTx = reuseTx;
  }

  private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  @Transactional
  public TokenPairResult refresh(String refreshToken, String clientIp) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "refresh_token is required", 400);
    }

    String ip = clientIp == null || clientIp.isBlank() ? "0.0.0.0" : clientIp;
    String ipKey = "auth:ip:refresh:" + ip + ":count";
    if (!rateLimiter.tryAcquire(ipKey, REFRESH_IP_LIMIT, REFRESH_IP_WINDOW_SECONDS)) {
      throw new AppException(
          "IP_RATE_LIMITED",
          "IP refresh rate limit exceeded",
          429,
          rateLimiter.secondsUntilAvailable(ipKey, REFRESH_IP_LIMIT, REFRESH_IP_WINDOW_SECONDS));
    }

    String hash = RefreshTokens.sha256Hex(refreshToken);
    AuthSessionRecord session =
        sessionStore
            .findByRefreshTokenHash(hash)
            .orElseThrow(
                () -> new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401));

    Instant now = clock.instant();
    if (session.rotatedAt() != null) {
      handleReuse(session, now);
      throw new AppException("REFRESH_TOKEN_REUSED", "Refresh token was already rotated", 401);
    }
    if (session.revokedAt() != null) {
      throw new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401);
    }
    if (!now.isBefore(session.expiresAt())) {
      throw new AppException("REFRESH_TOKEN_EXPIRED", "Refresh token expired", 401);
    }

    assertNotSuspended(session);

    int marked = sessionStore.markRotatedIfActive(session.id(), now);
    if (marked == 0) {
      handleReuse(session, now);
      throw new AppException("REFRESH_TOKEN_REUSED", "Refresh token was already rotated", 401);
    }

    String newRefresh = RefreshTokens.generate(secureRandom);
    AuthSessionRecord replacement =
        new AuthSessionRecord(
            Ids.newId(),
            session.userId(),
            normalizeUserType(session.userType()),
            RefreshTokens.sha256Hex(newRefresh),
            session.tokenScope(),
            session.deviceInfoJson(),
            session.ipAddress(),
            session.userAgent(),
            session.createdAt(),
            now,
            session.expiresAt(),
            session.pharmacyId(),
            session.country(),
            session.city(),
            null,
            null);
    sessionStore.save(replacement);

    JwtClaims claims = buildClaims(session, replacement);
    String accessToken = jwtService.issueAccessToken(claims, ACCESS_TTL_SECONDS);
    long refreshExpiresIn = Math.max(1, ChronoUnit.SECONDS.between(now, session.expiresAt()));

    return new TokenPairResult(
        accessToken, newRefresh, "Bearer", ACCESS_TTL_SECONDS, refreshExpiresIn, replacement.id());
  }

  /**
   * Commits revoke + outbox in a nested transaction so the subsequent {@code AppException} does not
   * roll back rotation-replay protection.
   */
  private void handleReuse(AuthSessionRecord session, Instant now) {
    reuseTx.executeWithoutResult(
        status -> {
          sessionStore.revokeAllForUser(session.userId(), now);
          outboxPublisher.publish(
              DomainEvent.of(
                  "auth.refresh_token_reused",
                  "session",
                  session.id(),
                  Map.of(
                      "user_id",
                      session.userId().toString(),
                      "user_type",
                      normalizeUserType(session.userType()),
                      "session_id",
                      session.id().toString())));
        });
  }

  private void assertNotSuspended(AuthSessionRecord session) {
    String type = normalizeUserType(session.userType());
    switch (type) {
      case "customer" -> {
        customerStore
            .findById(session.userId())
            .orElseThrow(
                () -> new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401));
      }
      case "pharmacy_staff" -> {
        PharmacyStaffRecord staff =
            pharmacyStaffStore
                .findById(session.userId())
                .orElseThrow(
                    () ->
                        new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401));
        if ("SUSPENDED".equals(staff.status())) {
          throw new AppException("ACCOUNT_SUSPENDED", "Account has been suspended", 403);
        }
      }
      case "admin_staff" -> {
        AdminStaffRecord admin =
            adminStaffStore
                .findById(session.userId())
                .orElseThrow(
                    () ->
                        new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401));
        if ("SUSPENDED".equals(admin.status())) {
          throw new AppException("ACCOUNT_SUSPENDED", "Admin account has been suspended", 403);
        }
      }
      case "rider" -> {
        RiderAccount rider =
            riderAccountPort
                .findById(session.userId())
                .orElseThrow(
                    () ->
                        new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401));
        if ("BLOCKED".equals(rider.status())) {
          throw new AppException("UNAUTHORIZED", "Rider account is blocked", 401);
        }
      }
      default -> {
        // unknown user_type: allow refresh if session is valid
      }
    }
  }

  private JwtClaims buildClaims(AuthSessionRecord oldSession, AuthSessionRecord newSession) {
    String type = normalizeUserType(oldSession.userType());
    TokenScope scope = TokenScope.fromValue(oldSession.tokenScope());
    String jti = Ids.newId().toString();
    return switch (type) {
      case "customer" -> new JwtClaims(newSession.userId(), AuthRole.CUSTOMER, null, scope, jti);
      case "pharmacy_staff" -> {
        AuthRole role = AuthRole.PHARMACY_STAFF;
        UUID pharmacyId = newSession.pharmacyId();
        if (pharmacyId != null) {
          List<PharmacyAssignmentRecord> assignments =
              assignmentStore.listActiveByStaffId(newSession.userId());
          for (PharmacyAssignmentRecord a : assignments) {
            if (pharmacyId.equals(a.pharmacyId())) {
              role =
                  com.nammamedmate.auth.domain.PharmacyRoleCodes.isOwner(a.roleCode())
                      ? AuthRole.PHARMACY_OWNER
                      : AuthRole.PHARMACY_STAFF;
              break;
            }
          }
        }
        yield new JwtClaims(newSession.userId(), role, pharmacyId, scope, jti);
      }
      case "admin_staff" -> {
        AdminStaffRecord admin =
            adminStaffStore
                .findById(newSession.userId())
                .orElseThrow(
                    () ->
                        new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401));
        yield new JwtClaims(
            newSession.userId(), AuthRole.fromValue(admin.role()), null, scope, jti);
      }
      case "rider" -> new JwtClaims(newSession.userId(), AuthRole.RIDER, null, scope, jti);
      default -> throw new AppException("REFRESH_TOKEN_INVALID", "Refresh token not found", 401);
    };
  }

  /** Maps legacy admin session rows to story user_type admin_staff. */
  static String normalizeUserType(String userType) {
    if ("admin".equals(userType)) {
      return "admin_staff";
    }
    return userType;
  }
}
