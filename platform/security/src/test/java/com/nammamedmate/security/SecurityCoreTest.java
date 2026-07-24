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
