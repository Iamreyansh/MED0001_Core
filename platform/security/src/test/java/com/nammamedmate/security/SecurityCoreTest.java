package com.nammamedmate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityCoreTest {

  private KeyPair keyPair;
  private InMemoryTokenRevocationStore revocationStore;
  private Rs256JwtService jwtService;
  private Clock clock;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
    clock = Clock.systemUTC();
    revocationStore = new InMemoryTokenRevocationStore(clock);
    jwtService =
        new Rs256JwtService(keyPair.getPrivate(), keyPair.getPublic(), revocationStore, clock, 900);
    SecurityContextHolder.clearContext();
  }

  @Test
  void rolesAndScopes() {
    assertThat(AuthRole.fromValue("customer")).isEqualTo(AuthRole.CUSTOMER);
    assertThat(AuthRole.CUSTOMER.value()).isEqualTo("customer");
    assertThatThrownBy(() -> AuthRole.fromValue("nope"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TokenScope.fromValue("pos")).isEqualTo(TokenScope.POS);
    assertThatThrownBy(() -> TokenScope.fromValue("x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issueParseAndRevoke() {
    UUID sub = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    JwtClaims claims =
        new JwtClaims(sub, AuthRole.PHARMACY_STAFF, pharmacy, TokenScope.FULL, "jti-1");
    String token = jwtService.issueAccessToken(claims);
    JwtClaims parsed = jwtService.parseAndValidate(token);
    assertThat(parsed.subject()).isEqualTo(sub);
    assertThat(parsed.pharmacyId()).isEqualTo(pharmacy);
    revocationStore.revoke("jti-1", 60);
    assertThat(revocationStore.isRevoked("jti-1")).isTrue();
    assertThatThrownBy(() -> jwtService.parseAndValidate(token))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issueWithCustomTtl() {
    JwtClaims claims =
        new JwtClaims(
            UUID.randomUUID(),
            AuthRole.PHARMACY_STAFF,
            UUID.randomUUID(),
            TokenScope.POS,
            "jti-pos");
    String token = jwtService.issueAccessToken(claims, 14400L);
    JwtClaims parsed = jwtService.parseAndValidate(token);
    assertThat(parsed.tokenScope()).isEqualTo(TokenScope.POS);
  }

  @Test
  void issueWithoutPharmacyAndRevocationEdgeCases() {
    JwtClaims claims =
        new JwtClaims(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "jti-2");
    assertThat(jwtService.parseAndValidate(jwtService.issueAccessToken(claims)).pharmacyId())
        .isNull();
    InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore();
    assertThat(store.isRevoked(null)).isTrue();
    assertThat(store.isRevoked(" ")).isTrue();
    store.revoke(null, 10);
    store.revoke("  ", 10);
    store.revoke("x", 0);
    store.revoke("y", 1);
    assertThat(store.isRevoked("y")).isTrue();
  }

  @Test
  void expiredRevocationIsCleared() {
    MutableClock mutable = new MutableClock(Instant.now());
    InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore(mutable);
    store.revoke("z", 1);
    mutable.advanceSeconds(2);
    assertThat(store.isRevoked("z")).isFalse();
  }

  @Test
  void tryRevokeIsAtomicAndRejectsReuse() {
    InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore();
    assertThat(store.tryRevoke(null, 10)).isFalse();
    assertThat(store.tryRevoke(" ", 10)).isFalse();
    assertThat(store.tryRevoke("jti", 0)).isFalse();
    assertThat(store.tryRevoke("jti", 10)).isTrue();
    assertThat(store.tryRevoke("jti", 10)).isFalse();
    assertThat(store.isRevoked("jti")).isTrue();
  }

  @Test
  void tryRevokeReclaimsExpiredEntry() {
    MutableClock mutable = new MutableClock(Instant.now());
    InMemoryTokenRevocationStore store = new InMemoryTokenRevocationStore(mutable);
    assertThat(store.tryRevoke("expired", 1)).isTrue();
    mutable.advanceSeconds(2);
    assertThat(store.tryRevoke("expired", 5)).isTrue();
    assertThat(store.isRevoked("expired")).isTrue();
  }

  @Test
  void pharmacyContextAndFilter() throws Exception {
    assertThat(PharmacyContext.currentPharmacyId()).isEmpty();
    UUID pharmacy = UUID.randomUUID();
    JwtClaims claims =
        new JwtClaims(UUID.randomUUID(), AuthRole.PHARMACY_OWNER, pharmacy, TokenScope.FULL, "j");
    String token = jwtService.issueAccessToken(claims);
    JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(PharmacyContext.currentPharmacyId()).contains(pharmacy);
    assertThat(PharmacyContext.currentPrincipal()).isPresent();
    assertThat(PharmacyContext.currentPrincipal().orElseThrow().hasPharmacyContext()).isTrue();

    MockHttpServletRequest bad = new MockHttpServletRequest();
    bad.addHeader("Authorization", "Bearer not-a-jwt");
    filter.doFilter(bad, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(PharmacyContext.currentPrincipal()).isEmpty();

    MockHttpServletRequest noAuth = new MockHttpServletRequest();
    filter.doFilter(noAuth, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(PharmacyContext.currentPrincipal()).isEmpty();

    MockHttpServletRequest basic = new MockHttpServletRequest();
    basic.addHeader("Authorization", "Basic xyz");
    filter.doFilter(basic, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(PharmacyContext.currentPrincipal()).isEmpty();

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("user", "n"));
    assertThat(PharmacyContext.currentPharmacyId()).isEmpty();
    assertThat(PharmacyContext.currentPrincipal()).isEmpty();
  }

  @Test
  void pemLoaderRoundTrip() {
    String privatePem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
    String publicPem =
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(keyPair.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----";
    assertThat(RsaKeyLoader.loadPrivateKeyPem(privatePem)).isNotNull();
    assertThat(RsaKeyLoader.loadPublicKeyPem(publicPem)).isNotNull();
    assertThatThrownBy(() -> RsaKeyLoader.loadPrivateKeyPem("bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RsaKeyLoader.loadPublicKeyPem("bad"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void posTokenRestrictionFilter() throws Exception {
    JwtClaims posClaims =
        new JwtClaims(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, UUID.randomUUID(), TokenScope.POS, "jti-p");
    String posToken = jwtService.issueAccessToken(posClaims, 14400L);

    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
    PosTokenRestrictionFilter posFilter = new PosTokenRestrictionFilter();

    // POS token on non-POS path → 403
    MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/v1/some/endpoint");
    req1.addHeader("Authorization", "Bearer " + posToken);
    MockHttpServletResponse res1 = new MockHttpServletResponse();
    jwtFilter.doFilter(req1, res1, new MockFilterChain());
    MockHttpServletRequest req1b = new MockHttpServletRequest("POST", "/api/v1/some/endpoint");
    req1b.addHeader("Authorization", "Bearer " + posToken);
    jwtFilter.doFilter(req1b, res1, new MockFilterChain());
    posFilter.doFilter(req1b, res1, new MockFilterChain());
    assertThat(res1.getStatus()).isEqualTo(403);
    assertThat(res1.getContentAsString()).contains("POS_TOKEN_RESTRICTED");

    // POS token on /api/v1/pos/ path → passes
    SecurityContextHolder.clearContext();
    MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/v1/pos/sale");
    req2.addHeader("Authorization", "Bearer " + posToken);
    MockHttpServletResponse res2 = new MockHttpServletResponse();
    jwtFilter.doFilter(req2, res2, new MockFilterChain());
    posFilter.doFilter(req2, res2, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(res2.getStatus()).isEqualTo(200);

    // FULL token on non-POS path → passes
    SecurityContextHolder.clearContext();
    JwtClaims fullClaims =
        new JwtClaims(
            UUID.randomUUID(),
            AuthRole.PHARMACY_OWNER,
            UUID.randomUUID(),
            TokenScope.FULL,
            "jti-f");
    String fullToken = jwtService.issueAccessToken(fullClaims);
    MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/api/v1/some/data");
    req3.addHeader("Authorization", "Bearer " + fullToken);
    MockHttpServletResponse res3 = new MockHttpServletResponse();
    jwtFilter.doFilter(req3, res3, new MockFilterChain());
    posFilter.doFilter(req3, res3, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(res3.getStatus()).isEqualTo(200);

    // No auth on non-POS path → passes filter
    SecurityContextHolder.clearContext();
    MockHttpServletRequest req4 = new MockHttpServletRequest("GET", "/api/v1/health");
    MockHttpServletResponse res4 = new MockHttpServletResponse();
    posFilter.doFilter(req4, res4, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(res4.getStatus()).isEqualTo(200);
  }

  @Test
  void posTokenPermitsWhitelistedPaths() throws Exception {
    JwtClaims posClaims =
        new JwtClaims(
            UUID.randomUUID(),
            AuthRole.PHARMACY_STAFF,
            UUID.randomUUID(),
            TokenScope.POS,
            "jti-wl");
    String posToken = jwtService.issueAccessToken(posClaims, 14400L);
    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
    PosTokenRestrictionFilter posFilter = new PosTokenRestrictionFilter();

    String[] allowed = {
      "/api/v1/pos/sale", "/api/v1/pharmacy/pos/cart", "/actuator/health", "/api/v1/health"
    };
    for (String path : allowed) {
      SecurityContextHolder.clearContext();
      MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
      req.addHeader("Authorization", "Bearer " + posToken);
      MockHttpServletResponse res = new MockHttpServletResponse();
      jwtFilter.doFilter(req, res, new MockFilterChain());
      posFilter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
      assertThat(res.getStatus()).as("Expected 200 for allowed path: " + path).isEqualTo(200);
    }

    String[] blocked = {
      "/actuator/metrics",
      "/actuator/info",
      "/api/v1/auth/pharmacy/login",
      "/api/v1/webhooks/cashfree",
      "/v3/api-docs/openapi",
      "/swagger-ui/index.html"
    };
    for (String path : blocked) {
      SecurityContextHolder.clearContext();
      MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
      req.addHeader("Authorization", "Bearer " + posToken);
      MockHttpServletResponse res = new MockHttpServletResponse();
      jwtFilter.doFilter(req, res, new MockFilterChain());
      posFilter.doFilter(req, res, new MockFilterChain());
      assertThat(res.getStatus()).as("Expected 403 for blocked path: " + path).isEqualTo(403);
      assertThat(res.getContentAsString()).contains("POS_TOKEN_RESTRICTED");
    }

    assertThat(PosTokenRestrictionFilter.isAllowedForPos(null)).isFalse();
  }

  @Test
  void apiAuthHandlersWriteEnvelope() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse unauthorized = new MockHttpServletResponse();
    new ApiAuthenticationEntryPoint()
        .commence(
            request,
            unauthorized,
            new org.springframework.security.authentication.BadCredentialsException("nope"));
    assertThat(unauthorized.getStatus()).isEqualTo(401);
    assertThat(unauthorized.getContentAsString()).contains("UNAUTHORIZED");

    MockHttpServletResponse forbidden = new MockHttpServletResponse();
    new ApiAccessDeniedHandler()
        .handle(
            request,
            forbidden,
            new org.springframework.security.access.AccessDeniedException("nope"));
    assertThat(forbidden.getStatus()).isEqualTo(403);
    assertThat(forbidden.getContentAsString()).contains("FORBIDDEN");
  }

  @Test
  void posFilterPassesWhenPrincipalIsNotMedmate() throws Exception {
    // auth != null but getPrincipal() is not MedmatePrincipal → filter passes through
    PosTokenRestrictionFilter posFilter = new PosTokenRestrictionFilter();
    var auth = new UsernamePasswordAuthenticationToken("user", null);
    SecurityContextHolder.getContext().setAuthentication(auth);

    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/some/data");
    MockHttpServletResponse res = new MockHttpServletResponse();
    posFilter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(res.getStatus()).isEqualTo(200);
    SecurityContextHolder.clearContext();
  }

  @Test
  void aesGcmCipherRoundTripAndErrors() {
    byte[] key = new byte[32];
    AesGcmCipher cipher = new AesGcmCipher(key);
    String encrypted = cipher.encrypt("admin-totp-secret");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("admin-totp-secret");

    AesGcmCipher fromB64 =
        AesGcmCipher.fromBase64Key("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    assertThat(fromB64.decrypt(fromB64.encrypt("x"))).isEqualTo("x");

    assertThatThrownBy(() -> new AesGcmCipher(new byte[16]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");

    assertThatThrownBy(() -> cipher.decrypt("dGVzdA=="))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AES-GCM decrypt failed");

    AesGcmCipher failing =
        new AesGcmCipher(
            key,
            new java.security.SecureRandom(),
            () -> {
              throw new java.security.GeneralSecurityException("test");
            });
    assertThatThrownBy(() -> failing.encrypt("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AES-GCM encrypt failed");
  }

  @Test
  void mfaChallengeRestrictionFilter() throws Exception {
    JwtClaims challengeClaims =
        new JwtClaims(
            UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.MFA_CHALLENGE, "jti-mfa");
    String challengeToken = jwtService.issueAccessToken(challengeClaims, 300L);

    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
    MfaChallengeRestrictionFilter mfaFilter = new MfaChallengeRestrictionFilter();

    MockHttpServletRequest blocked =
        new MockHttpServletRequest("GET", "/api/v1/auth/admin/setup-mfa");
    blocked.addHeader("Authorization", "Bearer " + challengeToken);
    MockHttpServletResponse blockedRes = new MockHttpServletResponse();
    jwtFilter.doFilter(blocked, blockedRes, new MockFilterChain());
    mfaFilter.doFilter(blocked, blockedRes, new MockFilterChain());
    assertThat(blockedRes.getStatus()).isEqualTo(401);
    assertThat(blockedRes.getContentAsString()).contains("CHALLENGE_TOKEN_INVALID");

    String[] allowed = {"/api/v1/auth/admin/verify-mfa", "/api/v1/health", "/actuator/health"};
    for (String path : allowed) {
      SecurityContextHolder.clearContext();
      MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
      req.addHeader("Authorization", "Bearer " + challengeToken);
      MockHttpServletResponse res = new MockHttpServletResponse();
      jwtFilter.doFilter(req, res, new MockFilterChain());
      mfaFilter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
      assertThat(res.getStatus()).as("allowed for " + path).isEqualTo(200);
    }

    // FULL token on setup-mfa path → passes MFA filter
    SecurityContextHolder.clearContext();
    JwtClaims fullClaims =
        new JwtClaims(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "jti-full-mfa");
    String fullToken = jwtService.issueAccessToken(fullClaims);
    MockHttpServletRequest fullReq =
        new MockHttpServletRequest("POST", "/api/v1/auth/admin/setup-mfa");
    fullReq.addHeader("Authorization", "Bearer " + fullToken);
    MockHttpServletResponse fullRes = new MockHttpServletResponse();
    jwtFilter.doFilter(fullReq, fullRes, new MockFilterChain());
    mfaFilter.doFilter(fullReq, fullRes, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(fullRes.getStatus()).isEqualTo(200);

    assertThat(MfaChallengeRestrictionFilter.isAllowedForMfaChallenge(null)).isFalse();

    SecurityContextHolder.clearContext();
    MockHttpServletRequest noAuth = new MockHttpServletRequest("GET", "/api/v1/some/data");
    MockHttpServletResponse noAuthRes = new MockHttpServletResponse();
    mfaFilter.doFilter(
        noAuth, noAuthRes, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(noAuthRes.getStatus()).isEqualTo(200);
  }

  @Test
  void mfaFilterPassesWhenPrincipalIsNotMedmate() throws Exception {
    MfaChallengeRestrictionFilter mfaFilter = new MfaChallengeRestrictionFilter();
    var auth = new UsernamePasswordAuthenticationToken("user", null);
    SecurityContextHolder.getContext().setAuthentication(auth);

    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/some/data");
    MockHttpServletResponse res = new MockHttpServletResponse();
    mfaFilter.doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(res.getStatus()).isEqualTo(200);
    SecurityContextHolder.clearContext();
  }

  @Test
  void principalRequiresFields() {
    assertThatThrownBy(
            () -> new MedmatePrincipal(null, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"))
        .isInstanceOf(NullPointerException.class);
    assertThat(
            new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j")
                .hasPharmacyContext())
        .isFalse();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advanceSeconds(long s) {
      instant = instant.plusSeconds(s);
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
