package com.nammamedmate.notification.application;

import com.nammamedmate.kernel.error.AppException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** HS256 unsubscribe tokens (7-day). STORY-005 expands preference wiring. */
@Component
public final class UnsubscribeTokenService {

  public static final String PURPOSE = "email_unsubscribe";
  private static final long TTL_SECONDS = 7L * 24 * 60 * 60;

  private final SecretKey key;
  private final Clock clock;

  public UnsubscribeTokenService(
      @Value("${medmate.email.unsubscribe-secret:dev-email-unsubscribe-secret-32b}") String secret,
      Clock clock) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
      byte[] padded = new byte[32];
      System.arraycopy(bytes, 0, padded, 0, bytes.length);
      bytes = padded;
    }
    this.key = Keys.hmacShaKeyFor(bytes);
    this.clock = clock;
  }

  public String issue(String email, UUID customerId) {
    Instant now = clock.instant();
    var builder =
        Jwts.builder()
            .subject(customerId == null ? email : customerId.toString())
            .claim("email", email)
            .claim("purpose", PURPOSE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(TTL_SECONDS)))
            .signWith(key);
    return builder.compact();
  }

  public record ParsedToken(String email, UUID customerId) {}

  public ParsedToken parse(String token) {
    if (token == null) {
      throw new AppException("INVALID_TOKEN", "Unsubscribe token is required", 400);
    }
    if (token.isBlank()) {
      throw new AppException("INVALID_TOKEN", "Unsubscribe token is required", 400);
    }
    try {
      Claims claims =
          Jwts.parser()
              .clock(() -> Date.from(clock.instant()))
              .verifyWith(key)
              .build()
              .parseSignedClaims(token.trim())
              .getPayload();
      if (!PURPOSE.equals(claims.get("purpose", String.class))) {
        throw new AppException("INVALID_TOKEN", "Token purpose mismatch", 400);
      }
      String email = claims.get("email", String.class);
      if (email == null) {
        throw new AppException("INVALID_TOKEN", "Token missing email", 400);
      }
      if (email.isBlank()) {
        throw new AppException("INVALID_TOKEN", "Token missing email", 400);
      }
      UUID customerId = null;
      String subject = claims.getSubject();
      if (subject == null) {
        return new ParsedToken(email.trim().toLowerCase(), null);
      }
      try {
        customerId = UUID.fromString(subject);
      } catch (RuntimeException ignored) {
        // subject may be the email itself
      }
      return new ParsedToken(email.trim().toLowerCase(), customerId);
    } catch (ExpiredJwtException e) {
      throw new AppException("TOKEN_EXPIRED", "Unsubscribe token has expired", 410);
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new AppException("INVALID_TOKEN", "Unsubscribe token is invalid", 400);
    }
  }
}
