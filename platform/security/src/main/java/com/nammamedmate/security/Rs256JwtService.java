package com.nammamedmate.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

public final class Rs256JwtService {

  private final PrivateKey privateKey;
  private final PublicKey publicKey;
  private final TokenRevocationStore revocationStore;
  private final Clock clock;
  private final long accessTtlSeconds;

  public Rs256JwtService(
      PrivateKey privateKey,
      PublicKey publicKey,
      TokenRevocationStore revocationStore,
      Clock clock,
      long accessTtlSeconds) {
    this.privateKey = Objects.requireNonNull(privateKey);
    this.publicKey = Objects.requireNonNull(publicKey);
    this.revocationStore = Objects.requireNonNull(revocationStore);
    this.clock = Objects.requireNonNull(clock);
    this.accessTtlSeconds = accessTtlSeconds;
  }

  public String issueAccessToken(JwtClaims claims) {
    Objects.requireNonNull(claims);
    Instant now = clock.instant();
    var builder =
        Jwts.builder()
            .id(claims.jti())
            .subject(claims.subject().toString())
            .claim("role", claims.role().value())
            .claim("token_scope", claims.tokenScope().value())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
            .signWith(privateKey);
    if (claims.pharmacyId() != null) {
      builder.claim("pharmacy_id", claims.pharmacyId().toString());
    }
    return builder.compact();
  }

  public JwtClaims parseAndValidate(String token) {
    Claims claims =
        Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    String jti = claims.getId();
    if (revocationStore.isRevoked(jti)) {
      throw new IllegalArgumentException("token revoked");
    }
    UUID pharmacyId = null;
    Object pharmacyClaim = claims.get("pharmacy_id");
    if (pharmacyClaim != null) {
      pharmacyId = UUID.fromString(pharmacyClaim.toString());
    }
    return new JwtClaims(
        UUID.fromString(claims.getSubject()),
        AuthRole.fromValue(claims.get("role", String.class)),
        pharmacyId,
        TokenScope.fromValue(claims.get("token_scope", String.class)),
        jti);
  }
}
