package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import com.nammamedmate.auth.application.port.out.AdminStaffStore;
import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.auth.application.port.out.CustomerStore;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentStore;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffStore;
import com.nammamedmate.auth.application.port.out.PharmacyStore;
import com.nammamedmate.auth.domain.RefreshTokens;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.InMemoryTokenRevocationStore;
import com.nammamedmate.security.JwtClaims;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.Rs256JwtService;
import com.nammamedmate.security.TokenScope;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TokenManagementServicesTest {

  private MutableClock clock;
  private InMemorySessionStore sessions;
  private FakeCustomerStore customers;
  private FakePharmacyStaffStore pharmacyStaff;
  private FakeAssignmentStore assignments;
  private FakePharmacyStore pharmacies;
  private FakeAdminStore admins;
  private Rs256JwtService jwt;
  private InMemoryRateLimiter limiter;
  private InMemoryTokenRevocationStore revocation;
  private InMemoryOutboxStore outbox;
  private TransactionTemplate reuseTx;
  private RefreshTokenService refreshService;
  private SessionLogoutService logoutService;
  private CurrentUserService meService;
  private SessionListService listService;

  @BeforeEach
  void setUp() throws Exception {
    clock = new MutableClock(Instant.parse("2026-07-26T02:00:00Z"));
    sessions = new InMemorySessionStore();
    customers = new FakeCustomerStore();
    pharmacyStaff = new FakePharmacyStaffStore();
    assignments = new FakeAssignmentStore();
    pharmacies = new FakePharmacyStore();
    admins = new FakeAdminStore();
    limiter = new InMemoryRateLimiter(clock);
    revocation = new InMemoryTokenRevocationStore(clock);
    outbox = new InMemoryOutboxStore();
    reuseTx = immediateReuseTx();
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair pair = gen.generateKeyPair();
    jwt = new Rs256JwtService(pair.getPrivate(), pair.getPublic(), revocation, clock, 900);
    refreshService =
        new RefreshTokenService(
            sessions,
            customers,
            pharmacyStaff,
            assignments,
            admins,
            jwt,
            limiter,
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock,
            new SecureRandom(),
            reuseTx);
    logoutService = new SessionLogoutService(sessions, revocation, limiter, clock);
    RbacPermissionService rbac =
        new RbacPermissionService(
            emptyRoleStore(),
            assignments,
            emptyPermissionCatalog(),
            new com.nammamedmate.auth.adapter.out.cache.RedisRolePermissionCache());
    meService =
        new CurrentUserService(
            customers, pharmacyStaff, assignments, pharmacies, admins, rbac, limiter);
    listService = new SessionListService(sessions, limiter, clock, new ObjectMapper());
  }

  private static com.nammamedmate.auth.application.port.out.PharmacyRoleStore emptyRoleStore() {
    return new com.nammamedmate.auth.application.port.out.PharmacyRoleStore() {
      @Override
      public List<com.nammamedmate.auth.application.port.out.PharmacyRoleRecord> listSystemRoles() {
        return List.of();
      }

      @Override
      public List<com.nammamedmate.auth.application.port.out.PharmacyRoleRecord>
          listCustomByPharmacy(UUID pharmacyId) {
        return List.of();
      }

      @Override
      public Optional<com.nammamedmate.auth.application.port.out.PharmacyRoleRecord> findById(
          UUID id) {
        return Optional.empty();
      }

      @Override
      public Optional<com.nammamedmate.auth.application.port.out.PharmacyRoleRecord>
          findSystemByCode(String code) {
        return Optional.empty();
      }

      @Override
      public Optional<com.nammamedmate.auth.application.port.out.PharmacyRoleRecord>
          findActiveByPharmacyAndCode(UUID pharmacyId, String code) {
        return Optional.empty();
      }

      @Override
      public com.nammamedmate.auth.application.port.out.PharmacyRoleRecord save(
          com.nammamedmate.auth.application.port.out.PharmacyRoleRecord role) {
        return role;
      }

      @Override
      public int countActiveStaff(UUID roleId, UUID pharmacyId) {
        return 0;
      }
    };
  }

  private static com.nammamedmate.auth.application.port.out.PermissionCatalogStore
      emptyPermissionCatalog() {
    return new com.nammamedmate.auth.application.port.out.PermissionCatalogStore() {
      @Override
      public List<com.nammamedmate.auth.application.port.out.PermissionRecord> listByDomain(
          String domain) {
        return List.of(
            new com.nammamedmate.auth.application.port.out.PermissionRecord(
                "orders", "read", "d", "pharmacy"),
            new com.nammamedmate.auth.application.port.out.PermissionRecord(
                "inventory", "read", "d", "pharmacy"),
            new com.nammamedmate.auth.application.port.out.PermissionRecord(
                "staff", "manage", "d", "pharmacy"));
      }

      @Override
      public List<com.nammamedmate.auth.application.port.out.PermissionRecord>
          listByDomainAndResource(String domain, String resource) {
        return List.of();
      }

      @Override
      public Optional<com.nammamedmate.auth.application.port.out.PermissionRecord> find(
          String domain, String resource, String action) {
        return Optional.empty();
      }
    };
  }

  private static TransactionTemplate immediateReuseTx() {
    TransactionTemplate template =
        new TransactionTemplate(
            new PlatformTransactionManager() {
              @Override
              public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
              }

              @Override
              public void commit(TransactionStatus status) {}

              @Override
              public void rollback(TransactionStatus status) {}
            });
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  @Test
  void refreshRotatesTokenAndInvalidatesOld() {
    UUID userId = Ids.newId();
    customers.save(customer(userId));
    String refresh = "old-refresh-token-value-aaaaaaaa";
    AuthSessionRecord session =
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            RefreshTokens.sha256Hex(refresh),
            "full",
            "{\"platform\":\"android\"}",
            "1.2.3.4",
            "ua",
            clock.instant(),
            clock.instant(),
            clock.instant().plus(30, ChronoUnit.DAYS),
            null);
    sessions.save(session);
    AuthSessionRecord sibling =
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            RefreshTokens.sha256Hex("sibling-refresh-token-bbbbbbbb"),
            "full",
            null,
            "1.2.3.4",
            "ua",
            clock.instant(),
            clock.instant(),
            clock.instant().plus(30, ChronoUnit.DAYS),
            null);
    sessions.save(sibling);

    TokenPairResult result = refreshService.refresh(refresh, "9.9.9.9");
    assertThat(result.accessToken()).isNotBlank();
    assertThat(result.refreshToken()).isNotEqualTo(refresh);
    assertThat(result.accessTokenExpiresIn()).isEqualTo(900);
    assertThat(sessions.findById(session.id()).orElseThrow().rotatedAt()).isNotNull();
    assertThatThrownBy(() -> refreshService.refresh(refresh, "9.9.9.9"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_REUSED");
    assertThat(sessions.findById(session.id()).orElseThrow().revokedAt()).isNotNull();
    assertThat(sessions.findById(sibling.id()).orElseThrow().revokedAt()).isNotNull();
    assertThat(sessions.findById(result.sessionId()).orElseThrow().revokedAt()).isNotNull();
    assertThat(outbox.all()).isNotEmpty();
    assertThat(outbox.all().get(0).type()).isEqualTo("auth.refresh_token_reused");
  }

  @Test
  void refreshExpiredReturnsExpired() {
    UUID userId = Ids.newId();
    customers.save(customer(userId));
    String refresh = "expired-refresh-bbbbbbbbbbbbbbbb";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            RefreshTokens.sha256Hex(refresh),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant().minus(40, ChronoUnit.DAYS),
            clock.instant().minus(40, ChronoUnit.DAYS),
            clock.instant().minus(1, ChronoUnit.DAYS),
            null));
    assertThatThrownBy(() -> refreshService.refresh(refresh, "1.1.1.1"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_EXPIRED");
  }

  @Test
  void logoutAllRevokesAllSessions() {
    UUID userId = Ids.newId();
    for (int i = 0; i < 3; i++) {
      sessions.save(
          AuthSessionRecord.active(
              Ids.newId(),
              userId,
              "customer",
              RefreshTokens.sha256Hex("tok-" + i),
              "full",
              null,
              "1.1.1.1",
              null,
              clock.instant(),
              clock.instant(),
              clock.instant().plus(30, ChronoUnit.DAYS),
              null));
    }
    MedmatePrincipal principal =
        new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti-1");
    int revoked = logoutService.logoutAll(principal);
    assertThat(revoked).isEqualTo(3);
    assertThat(sessions.countActiveByUserId(userId, clock.instant())).isZero();
    assertThat(revocation.isRevoked("jti-1")).isTrue();
  }

  @Test
  void revokeOtherUsersSessionForbidden() {
    UUID owner = Ids.newId();
    UUID other = Ids.newId();
    UUID sessionId = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            sessionId,
            owner,
            "customer",
            "hash",
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    MedmatePrincipal principal =
        new MedmatePrincipal(other, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti-2");
    assertThatThrownBy(() -> logoutService.revokeSession(principal, sessionId))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void meReturnsCustomerProfile() {
    UUID userId = Ids.newId();
    customers.save(
        new CustomerRecord(
            userId,
            "+919876543210",
            List.of(),
            "Ramesh",
            null,
            null,
            null,
            "kn",
            "LOYAL",
            12550L,
            38,
            clock.instant()));
    Map<String, Object> me =
        meService.me(new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti"));
    assertThat(me.get("role")).isEqualTo("customer");
    assertThat(me.get("loyalty_points")).isEqualTo(38);
    assertThat(me.get("wallet_balance").toString()).isEqualTo("125.50");
  }

  @Test
  void meReturnsAdminProfile() {
    UUID adminId = Ids.newId();
    admins.save(
        new AdminStaffRecord(
            adminId,
            "Ayesha",
            "ayesha@namma-medmate.in",
            "hash",
            "admin_super",
            "ACTIVE",
            true,
            "secret",
            List.of(),
            0,
            null,
            null,
            clock.instant(),
            clock.instant(),
            null,
            clock.instant(),
            clock.instant()));
    Map<String, Object> me =
        meService.me(
            new MedmatePrincipal(adminId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "jti"));
    assertThat(me.get("role")).isEqualTo("admin_super");
    assertThat(me.get("mfa_enabled")).isEqualTo(true);
  }

  @Test
  void listSessionsMarksIsCurrentFalse() {
    UUID userId = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            "h1",
            "full",
            "{\"platform\":\"ios\"}",
            "1.1.1.1",
            "ua",
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    var result =
        listService.list(
            new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti"), 1, 20);
    assertThat(result.sessions()).hasSize(1);
    assertThat(result.sessions().get(0).get("is_current")).isEqualTo(false);
    assertThat(result.meta().total()).isEqualTo(1);
  }

  @Test
  void remainingCoverageBranches() {
    // public @Autowired constructor path
    RefreshTokenService wired =
        new RefreshTokenService(
            sessions,
            customers,
            pharmacyStaff,
            assignments,
            admins,
            jwt,
            limiter,
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock,
            new PlatformTransactionManager() {
              @Override
              public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
              }

              @Override
              public void commit(TransactionStatus status) {}

              @Override
              public void rollback(TransactionStatus status) {}
            });
    assertThatThrownBy(() -> wired.refresh("missing", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    UUID userId = Ids.newId();
    customers.save(customer(userId));
    MedmatePrincipal p =
        new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "cov-jti");

    assertThatThrownBy(() -> logoutService.logout(p, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    String rotatedTok = "rotated-logout-rrrrrrrrrrrr";
    sessions.save(
        new AuthSessionRecord(
            Ids.newId(),
            userId,
            "customer",
            RefreshTokens.sha256Hex(rotatedTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null,
            null,
            null,
            clock.instant(),
            null));
    assertThatThrownBy(() -> logoutService.logout(p, rotatedTok))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    UUID revokedId = Ids.newId();
    sessions.save(
        new AuthSessionRecord(
            revokedId,
            userId,
            "customer",
            "revoked-own",
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null,
            null,
            null,
            null,
            clock.instant()));
    assertThatThrownBy(() -> logoutService.revokeSession(p, revokedId))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    UUID okRevoke = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            okRevoke,
            userId,
            "customer",
            "ok-revoke-hash",
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThat(logoutService.revokeSession(p, okRevoke)).isEqualTo(okRevoke);

    // null device_info list path
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            "null-device",
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThat(
            listService
                .list(
                    new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "lst"),
                    1,
                    20)
                .sessions()
                .stream()
                .anyMatch(s -> Map.of().equals(s.get("device"))))
        .isTrue();

    // pharmacy assignment loop: first row mismatched pharmacy id
    UUID staffId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    UUID otherPharmacy = Ids.newId();
    pharmacyStaff.save(
        new PharmacyStaffRecord(
            staffId,
            "Z",
            "z@y.in",
            "+919800000077",
            "hash",
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    assignments.records.clear();
    assignments.records.add(
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, otherPharmacy, "owner", true, clock.instant(), null, "Other"));
    assignments.records.add(
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "owner", true, clock.instant(), null, "Match"));
    String tok = "pharm-loop-ssssssssssssss";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            staffId,
            "pharmacy_staff",
            RefreshTokens.sha256Hex(tok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            pharmacyId));
    assertThat(jwt.parseAndValidate(refreshService.refresh(tok, "8.8.8.8").accessToken()).role())
        .isEqualTo(AuthRole.PHARMACY_OWNER);

    // admin disappears between suspension check and claim build
    UUID adminId = Ids.newId();
    AdminStaffRecord admin =
        new AdminStaffRecord(
            adminId,
            "Gone",
            "gone@x.in",
            "hash",
            "admin_finance",
            "ACTIVE",
            false,
            null,
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant());
    AtomicInteger lookups = new AtomicInteger();
    AdminStaffStore flaky =
        new AdminStaffStore() {
          @Override
          public Optional<AdminStaffRecord> findByEmail(String email) {
            return Optional.empty();
          }

          @Override
          public Optional<AdminStaffRecord> findById(UUID id) {
            if (lookups.incrementAndGet() == 1) {
              return Optional.of(admin);
            }
            return Optional.empty();
          }

          @Override
          public AdminStaffRecord save(AdminStaffRecord staff) {
            return staff;
          }
        };
    RefreshTokenService flakyRefresh =
        new RefreshTokenService(
            sessions,
            customers,
            pharmacyStaff,
            assignments,
            flaky,
            jwt,
            new InMemoryRateLimiter(clock),
            new OutboxPublisher(outbox, new ObjectMapper()),
            clock,
            new SecureRandom(),
            reuseTx);
    String adminTok = "admin-flaky-tttttttttttttt";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            adminId,
            "admin_staff",
            RefreshTokens.sha256Hex(adminTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThatThrownBy(() -> flakyRefresh.refresh(adminTok, "9.9.9.9"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    // remaining compound-branch edges
    assertThatThrownBy(() -> refreshService.refresh("", "1.1.1.1"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    String blankIpTok = "blank-ip-uuuuuuuuuuuuuu";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            RefreshTokens.sha256Hex(blankIpTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThat(refreshService.refresh(blankIpTok, "").accessToken()).isNotBlank();

    String revokedLogoutTok = "revoked-logout-vvvvvvvvvvvv";
    sessions.save(
        new AuthSessionRecord(
            Ids.newId(),
            userId,
            "customer",
            RefreshTokens.sha256Hex(revokedLogoutTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null,
            null,
            null,
            null,
            clock.instant()));
    assertThatThrownBy(() -> logoutService.logout(p, revokedLogoutTok))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            "blank-device-json",
            "full",
            "",
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThat(
            listService
                .list(
                    new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "lst2"),
                    1,
                    50)
                .sessions())
        .isNotEmpty();

    // pharmacyId set but no matching assignment rows
    UUID staffNoMatch = Ids.newId();
    UUID phWanted = Ids.newId();
    pharmacyStaff.save(
        new PharmacyStaffRecord(
            staffNoMatch,
            "NM",
            "nm@y.in",
            "+919800000066",
            "hash",
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    assignments.records.clear();
    assignments.records.add(
        new PharmacyAssignmentRecord(
            Ids.newId(), staffNoMatch, Ids.newId(), "owner", true, clock.instant(), null, "Nope"));
    String noMatchTok = "pharm-nomatch-wwwwwwwwwwww";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            staffNoMatch,
            "pharmacy_staff",
            RefreshTokens.sha256Hex(noMatchTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            phWanted));
    assertThat(
            jwt.parseAndValidate(refreshService.refresh(noMatchTok, "1.2.3.4").accessToken())
                .role())
        .isEqualTo(AuthRole.PHARMACY_STAFF);
  }

  @Test
  void coverageEdgesForTokenLifecycle() {
    // null refresh + blank IP defaults
    assertThatThrownBy(() -> refreshService.refresh(null, " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    // rate limit refresh
    for (int i = 0; i < RefreshTokenService.REFRESH_IP_LIMIT; i++) {
      try {
        refreshService.refresh("missing-" + i, "rate-ip");
      } catch (AppException ignored) {
        // expected invalid
      }
    }
    assertThatThrownBy(() -> refreshService.refresh("x", "rate-ip"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("IP_RATE_LIMITED");

    // revoked session lookup
    UUID userId = Ids.newId();
    customers.save(customer(userId));
    String revokedTok = "revoked-refresh-ffffffffffffffff";
    UUID sid = Ids.newId();
    sessions.save(
        new AuthSessionRecord(
            sid,
            userId,
            "customer",
            RefreshTokens.sha256Hex(revokedTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null,
            null,
            null,
            null,
            clock.instant()));
    assertThatThrownBy(() -> refreshService.refresh(revokedTok, "2.2.2.2"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    // concurrent rotate (markRotated returns 0)
    String raceTok = "race-refresh-gggggggggggggggg";
    UUID raceId = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            raceId,
            userId,
            "customer",
            RefreshTokens.sha256Hex(raceTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    sessions.forceMarkRotatedFail(raceId);
    assertThatThrownBy(() -> refreshService.refresh(raceTok, "3.3.3.3"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_REUSED");

    // pharmacy owner refresh path
    UUID staffId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    pharmacyStaff.save(
        new PharmacyStaffRecord(
            staffId,
            "P",
            "p@y.in",
            "+919800000099",
            "hash",
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    assignments.records.add(
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "owner", true, clock.instant(), null, "Shop"));
    String pharmTok = "pharm-ok-hhhhhhhhhhhhhhhh";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            staffId,
            "pharmacy_staff",
            RefreshTokens.sha256Hex(pharmTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            pharmacyId));
    TokenPairResult pharm = refreshService.refresh(pharmTok, "4.4.4.4");
    assertThat(jwt.parseAndValidate(pharm.accessToken()).role()).isEqualTo(AuthRole.PHARMACY_OWNER);

    // pharmacy without pharmacyId / staff assignment mismatch stays staff
    String pharmTok2 = "pharm-staff-iiiiiiiiiiiiiiii";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            staffId,
            "pharmacy_staff",
            RefreshTokens.sha256Hex(pharmTok2),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThat(
            jwt.parseAndValidate(refreshService.refresh(pharmTok2, "4.4.4.5").accessToken()).role())
        .isEqualTo(AuthRole.PHARMACY_STAFF);

    // pharmacy_staff role (non-owner) with matching pharmacy assignment
    String pharmTok3 = "pharm-staff-role-zzzzzzzzzz";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            staffId,
            "pharmacy_staff",
            RefreshTokens.sha256Hex(pharmTok3),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            pharmacyId));
    assignments.records.clear();
    assignments.records.add(
        new PharmacyAssignmentRecord(
            Ids.newId(),
            staffId,
            pharmacyId,
            "pharmacy_staff",
            true,
            clock.instant(),
            null,
            "Shop"));
    assertThat(
            jwt.parseAndValidate(refreshService.refresh(pharmTok3, "4.4.4.6").accessToken()).role())
        .isEqualTo(AuthRole.PHARMACY_STAFF);

    // missing customer on refresh
    String orphan = "orphan-jjjjjjjjjjjjjjjj";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            Ids.newId(),
            "customer",
            RefreshTokens.sha256Hex(orphan),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThatThrownBy(() -> refreshService.refresh(orphan, "5.5.5.5"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    // missing pharmacy staff
    String missingStaff = "missing-staff-kkkkkkkkkkkk";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            Ids.newId(),
            "pharmacy_staff",
            RefreshTokens.sha256Hex(missingStaff),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            Ids.newId()));
    assertThatThrownBy(() -> refreshService.refresh(missingStaff, "5.5.5.6"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    // suspended admin
    UUID suspendedAdmin = Ids.newId();
    admins.save(
        new AdminStaffRecord(
            suspendedAdmin,
            "S",
            "s@x.in",
            "hash",
            "admin_operations",
            "SUSPENDED",
            false,
            null,
            List.of(),
            0,
            null,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    String adminTok = "admin-susp-llllllllllllllll";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            suspendedAdmin,
            "admin_staff",
            RefreshTokens.sha256Hex(adminTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThatThrownBy(() -> refreshService.refresh(adminTok, "6.6.6.6"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ACCOUNT_SUSPENDED");

    // missing admin
    String missingAdmin = "missing-admin-mmmmmmmmmmmm";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            Ids.newId(),
            "admin_staff",
            RefreshTokens.sha256Hex(missingAdmin),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThatThrownBy(() -> refreshService.refresh(missingAdmin, "6.6.6.7"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    // rider refresh + unknown type
    String riderTok = "rider-tok-nnnnnnnnnnnnnnnn";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            Ids.newId(),
            "rider",
            RefreshTokens.sha256Hex(riderTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThat(
            jwt.parseAndValidate(refreshService.refresh(riderTok, "7.7.7.7").accessToken()).role())
        .isEqualTo(AuthRole.RIDER);

    String weirdTok = "weird-tok-oooooooooooooooo";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            Ids.newId(),
            "unknown_type",
            RefreshTokens.sha256Hex(weirdTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThatThrownBy(() -> refreshService.refresh(weirdTok, "7.7.7.8"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REFRESH_TOKEN_INVALID");

    // logout edges
    assertThatThrownBy(() -> logoutService.logout(null, "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal p =
        new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti-c");
    assertThatThrownBy(() -> logoutService.logout(p, " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> logoutService.logoutAll(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> logoutService.revokeSession(null, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> logoutService.revokeSession(p, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> logoutService.revokeSession(p, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    UUID own = Ids.newId();
    sessions.save(
        new AuthSessionRecord(
            own,
            userId,
            "customer",
            "rot-hash",
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null,
            null,
            null,
            clock.instant(),
            null));
    assertThatThrownBy(() -> logoutService.revokeSession(p, own))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    UUID active = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            active,
            userId,
            "customer",
            "active-hash",
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    sessions.forceRevokeFail(active);
    assertThatThrownBy(() -> logoutService.revokeSession(p, active))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    // logout rotated/other-user/revoke race
    String otherTok = "other-logout-pppppppppppppp";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            Ids.newId(),
            "customer",
            RefreshTokens.sha256Hex(otherTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    assertThatThrownBy(() -> logoutService.logout(p, otherTok))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    String mineTok = "mine-logout-qqqqqqqqqqqqqq";
    UUID mineId = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            mineId,
            userId,
            "customer",
            RefreshTokens.sha256Hex(mineTok),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    sessions.forceRevokeFail(mineId);
    assertThatThrownBy(() -> logoutService.logout(p, mineTok))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");

    // rate limit logout
    for (int i = 0; i < SessionLogoutService.LOGOUT_LIMIT; i++) {
      try {
        logoutService.logout(p, "nope-" + i);
      } catch (AppException ignored) {
      }
    }
    assertThatThrownBy(() -> logoutService.logout(p, "nope"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");

    // list pagination defaults + rate limit
    listService.list(p, 0, 0);
    listService.list(p, 1, 500);
    MedmatePrincipal listUser =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "jl");
    for (int i = 0; i < SessionListService.LIST_LIMIT; i++) {
      listService.list(listUser, null, null);
    }
    assertThatThrownBy(() -> listService.list(listUser, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");

    // me rate limit + missing users
    MedmatePrincipal meUser =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "jm");
    for (int i = 0; i < CurrentUserService.ME_LIMIT; i++) {
      try {
        meService.me(meUser);
      } catch (AppException ignored) {
      }
    }
    assertThatThrownBy(() -> meService.me(meUser))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");

    UUID missing = Ids.newId();
    assertThatThrownBy(
            () ->
                meService.me(
                    new MedmatePrincipal(missing, AuthRole.CUSTOMER, null, TokenScope.FULL, "m1")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                meService.me(
                    new MedmatePrincipal(
                        missing, AuthRole.PHARMACY_STAFF, Ids.newId(), TokenScope.FULL, "m2")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(
            () ->
                meService.me(
                    new MedmatePrincipal(
                        missing, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "m3")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    // pharmacy me without pharmacy context + staff permissions + null pharmacy name
    UUID staff2 = Ids.newId();
    pharmacyStaff.save(
        new PharmacyStaffRecord(
            staff2,
            "Q",
            "q@y.in",
            "+919800000088",
            "hash",
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    Map<String, Object> staffMe =
        meService.me(
            new MedmatePrincipal(staff2, AuthRole.PHARMACY_STAFF, null, TokenScope.FULL, "m4"));
    assertThat(staffMe.get("permissions")).isEqualTo(List.of());

    UUID phId = Ids.newId();
    pharmacies.byId.put(phId, new PharmacyRecord(phId, null, null, null, null));
    assertThatThrownBy(
            () ->
                meService.me(
                    new MedmatePrincipal(
                        staff2, AuthRole.PHARMACY_STAFF, Ids.newId(), TokenScope.FULL, "m5")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    Map<String, Object> withPh =
        meService.me(
            new MedmatePrincipal(staff2, AuthRole.PHARMACY_STAFF, phId, TokenScope.FULL, "m6"));
    assertThat(((Map<?, ?>) withPh.get("active_pharmacy")).get("name")).isEqualTo("");
  }

  @Test
  void refreshAdminAndLegacyAdminType() {
    UUID adminId = Ids.newId();
    admins.save(
        new AdminStaffRecord(
            adminId,
            "A",
            "a@x.in",
            "hash",
            "admin_super",
            "ACTIVE",
            true,
            "sec",
            List.of(),
            0,
            null,
            null,
            clock.instant(),
            clock.instant(),
            null,
            clock.instant(),
            clock.instant()));
    String refresh = "admin-refresh-eeeeeeeeeeeeeeee";
    sessions.save(
        new AuthSessionRecord(
            Ids.newId(),
            adminId,
            "admin",
            RefreshTokens.sha256Hex(refresh),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plus(8, ChronoUnit.HOURS),
            null,
            null,
            null,
            null,
            null));
    TokenPairResult result = refreshService.refresh(refresh, "1.1.1.1");
    assertThat(result.accessToken()).isNotBlank();
    JwtClaims claims = jwt.parseAndValidate(result.accessToken());
    assertThat(claims.role()).isEqualTo(AuthRole.ADMIN_SUPER);
  }

  @Test
  void logoutSessionNotFoundAndListUnauthorized() {
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.CUSTOMER, null, TokenScope.FULL, "jti");
    assertThatThrownBy(() -> logoutService.logout(principal, "missing"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SESSION_NOT_FOUND");
    assertThatThrownBy(() -> listService.list(null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> meService.me(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    Map<String, Object> rider =
        meService.me(new MedmatePrincipal(Ids.newId(), AuthRole.RIDER, null, TokenScope.FULL, "j"));
    assertThat(rider.get("role")).isEqualTo("rider");
  }

  @Test
  void listParsesBadDeviceJson() {
    UUID userId = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            userId,
            "customer",
            "hx",
            "full",
            "{not-json",
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    var result =
        listService.list(
            new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"), 1, 5);
    assertThat(result.sessions().get(0).get("device")).isEqualTo(Map.of());
  }

  @Test
  void refreshSuspendedPharmacyForbidden() {
    UUID staffId = Ids.newId();
    pharmacyStaff.save(
        new PharmacyStaffRecord(
            staffId,
            "Priya",
            "p@x.in",
            "+919800000001",
            "hash",
            null,
            "SUSPENDED",
            0,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    String refresh = "pharm-refresh-cccccccccccccccc";
    sessions.save(
        AuthSessionRecord.active(
            Ids.newId(),
            staffId,
            "pharmacy_staff",
            RefreshTokens.sha256Hex(refresh),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plus(7, ChronoUnit.DAYS),
            Ids.newId()));
    assertThatThrownBy(() -> refreshService.refresh(refresh, "1.1.1.1"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ACCOUNT_SUSPENDED");
  }

  @Test
  void logoutCurrentSession() {
    UUID userId = Ids.newId();
    String refresh = "logout-refresh-dddddddddddddddd";
    UUID sessionId = Ids.newId();
    sessions.save(
        AuthSessionRecord.active(
            sessionId,
            userId,
            "customer",
            RefreshTokens.sha256Hex(refresh),
            "full",
            null,
            "1.1.1.1",
            null,
            clock.instant(),
            clock.instant(),
            clock.instant().plusSeconds(1000),
            null));
    MedmatePrincipal principal =
        new MedmatePrincipal(userId, AuthRole.CUSTOMER, null, TokenScope.FULL, "jti-out");
    logoutService.logout(principal, refresh);
    assertThat(sessions.findById(sessionId).orElseThrow().revokedAt()).isNotNull();
    assertThat(revocation.isRevoked("jti-out")).isTrue();
  }

  @Test
  void pharmacyMeIncludesPermissions() {
    UUID staffId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    pharmacyStaff.save(
        new PharmacyStaffRecord(
            staffId,
            "Priya",
            "priya@x.in",
            "+919800000002",
            "hash",
            null,
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            clock.instant(),
            clock.instant()));
    pharmacies.byId.put(pharmacyId, new PharmacyRecord(pharmacyId, "Sri Rama", null, "BLR", "PRO"));
    assignments.records.add(
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "owner", true, clock.instant(), null, "Sri Rama"));
    Map<String, Object> me =
        meService.me(
            new MedmatePrincipal(
                staffId, AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "jti"));
    assertThat(me.get("role")).isEqualTo("owner");
    assertThat(me.get("permissions")).isEqualTo(List.of("inventory:*", "orders:*", "staff:*"));
  }

  private static CustomerRecord customer(UUID id) {
    return new CustomerRecord(
        id, "+919999900010", List.of(), "C", null, null, null, null, null, 0, 0, Instant.now());
  }

  private static final class InMemorySessionStore implements AuthSessionStore {
    private final Map<UUID, AuthSessionRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byHash = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> failRotate = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> failRevoke = ConcurrentHashMap.newKeySet();

    void forceMarkRotatedFail(UUID id) {
      failRotate.add(id);
    }

    void forceRevokeFail(UUID id) {
      failRevoke.add(id);
    }

    @Override
    public AuthSessionRecord save(AuthSessionRecord session) {
      byId.put(session.id(), session);
      byHash.put(session.refreshTokenHash(), session.id());
      return session;
    }

    @Override
    public Optional<AuthSessionRecord> findByRefreshTokenHash(String refreshTokenHash) {
      UUID id = byHash.get(refreshTokenHash);
      return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<AuthSessionRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public int markRotatedIfActive(UUID id, Instant rotatedAt) {
      if (failRotate.contains(id)) {
        return 0;
      }
      AuthSessionRecord s = byId.get(id);
      if (s == null || s.rotatedAt() != null || s.revokedAt() != null) {
        return 0;
      }
      AuthSessionRecord updated =
          new AuthSessionRecord(
              s.id(),
              s.userId(),
              s.userType(),
              s.refreshTokenHash(),
              s.tokenScope(),
              s.deviceInfoJson(),
              s.ipAddress(),
              s.userAgent(),
              s.createdAt(),
              s.lastActiveAt(),
              s.expiresAt(),
              s.pharmacyId(),
              s.country(),
              s.city(),
              rotatedAt,
              s.revokedAt());
      byId.put(id, updated);
      return 1;
    }

    @Override
    public int revokeIfActive(UUID id, Instant revokedAt) {
      if (failRevoke.contains(id)) {
        return 0;
      }
      AuthSessionRecord s = byId.get(id);
      if (s == null || s.revokedAt() != null) {
        return 0;
      }
      byId.put(
          id,
          new AuthSessionRecord(
              s.id(),
              s.userId(),
              s.userType(),
              s.refreshTokenHash(),
              s.tokenScope(),
              s.deviceInfoJson(),
              s.ipAddress(),
              s.userAgent(),
              s.createdAt(),
              s.lastActiveAt(),
              s.expiresAt(),
              s.pharmacyId(),
              s.country(),
              s.city(),
              s.rotatedAt(),
              revokedAt));
      return 1;
    }

    @Override
    public int revokeAllForUser(UUID userId, Instant revokedAt) {
      int n = 0;
      for (AuthSessionRecord s : List.copyOf(byId.values())) {
        if (s.userId().equals(userId) && s.revokedAt() == null) {
          revokeIfActive(s.id(), revokedAt);
          n++;
        }
      }
      return n;
    }

    @Override
    public List<AuthSessionRecord> listActiveByUserId(
        UUID userId, Instant now, int page, int limit) {
      return byId.values().stream()
          .filter(
              s ->
                  s.userId().equals(userId)
                      && s.revokedAt() == null
                      && s.rotatedAt() == null
                      && s.expiresAt().isAfter(now))
          .sorted((a, b) -> b.lastActiveAt().compareTo(a.lastActiveAt()))
          .skip((long) (Math.max(page, 1) - 1) * Math.max(limit, 1))
          .limit(Math.max(limit, 1))
          .toList();
    }

    @Override
    public long countActiveByUserId(UUID userId, Instant now) {
      return byId.values().stream()
          .filter(
              s ->
                  s.userId().equals(userId)
                      && s.revokedAt() == null
                      && s.rotatedAt() == null
                      && s.expiresAt().isAfter(now))
          .count();
    }
  }

  private static final class FakeCustomerStore implements CustomerStore {
    final Map<UUID, CustomerRecord> byId = new HashMap<>();

    @Override
    public Optional<CustomerRecord> findByPhone(String phone) {
      return byId.values().stream().filter(c -> c.phone().equals(phone)).findFirst();
    }

    @Override
    public Optional<CustomerRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public CustomerRecord save(CustomerRecord customer) {
      byId.put(customer.id(), customer);
      return customer;
    }
  }

  private static final class FakePharmacyStaffStore implements PharmacyStaffStore {
    final Map<UUID, PharmacyStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<PharmacyStaffRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public Optional<PharmacyStaffRecord> findByPhone(String phone) {
      return Optional.empty();
    }

    @Override
    public Optional<PharmacyStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public PharmacyStaffRecord save(PharmacyStaffRecord staff) {
      byId.put(staff.id(), staff);
      return staff;
    }
  }

  private static final class FakeAssignmentStore implements PharmacyAssignmentStore {
    final List<PharmacyAssignmentRecord> records = new ArrayList<>();

    @Override
    public List<PharmacyAssignmentRecord> listActiveByStaffId(UUID staffId) {
      return records.stream()
          .filter(r -> r.staffId().equals(staffId) && r.isActive() && r.removedAt() == null)
          .toList();
    }

    @Override
    public Optional<PharmacyAssignmentRecord> findActive(UUID staffId, UUID pharmacyId) {
      return records.stream()
          .filter(
              r ->
                  r.staffId().equals(staffId)
                      && r.pharmacyId().equals(pharmacyId)
                      && r.isActive()
                      && r.removedAt() == null)
          .findFirst();
    }
  }

  private static final class FakePharmacyStore implements PharmacyStore {
    final Map<UUID, PharmacyRecord> byId = new HashMap<>();

    @Override
    public Optional<PharmacyRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }
  }

  private static final class FakeAdminStore implements AdminStaffStore {
    final Map<UUID, AdminStaffRecord> byId = new HashMap<>();

    @Override
    public Optional<AdminStaffRecord> findByEmail(String email) {
      return Optional.empty();
    }

    @Override
    public Optional<AdminStaffRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public AdminStaffRecord save(AdminStaffRecord staff) {
      byId.put(staff.id(), staff);
      return staff;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
