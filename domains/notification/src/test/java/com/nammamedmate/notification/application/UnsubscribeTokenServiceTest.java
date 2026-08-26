package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnsubscribeTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:25:00Z");

  @Test
  void issueParseAndErrorBranches() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    UnsubscribeTokenService tokens = new UnsubscribeTokenService("short-secret", clock);
    UUID customerId = UUID.fromString("c0000001-0000-4000-8000-000000000001");

    String issued = tokens.issue("A@B.com", customerId);
    UnsubscribeTokenService.ParsedToken parsed = tokens.parse(issued);
    assertThat(parsed.email()).isEqualTo("a@b.com");
    assertThat(parsed.customerId()).isEqualTo(customerId);

    assertThat(tokens.issue("solo@b.com", null)).isNotBlank();
    assertThatThrownBy(() -> tokens.parse(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");
    assertThatThrownBy(() -> tokens.parse("  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");
    assertThatThrownBy(() -> tokens.parse("not-a-jwt"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    byte[] bytes = "short-secret".getBytes(StandardCharsets.UTF_8);
    byte[] padded = new byte[32];
    System.arraycopy(bytes, 0, padded, 0, bytes.length);
    var key = Keys.hmacShaKeyFor(padded);

    String badPurpose =
        Jwts.builder()
            .subject("x")
            .claim("email", "a@b.com")
            .claim("purpose", "other")
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(badPurpose))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    String noEmail =
        Jwts.builder()
            .subject("x")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(noEmail))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    String blankEmailClaim =
        Jwts.builder()
            .subject("x")
            .claim("email", "\t  \n")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(blankEmailClaim))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_TOKEN");

    String noSubject =
        Jwts.builder()
            .claim("email", "nosub@b.com")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThat(tokens.parse(noSubject).customerId()).isNull();

    String emailSubject =
        Jwts.builder()
            .subject("a@b.com")
            .claim("email", "a@b.com")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.plusSeconds(3600)))
            .signWith(key)
            .compact();
    assertThat(tokens.parse(emailSubject).customerId()).isNull();

    String expired =
        Jwts.builder()
            .subject(customerId.toString())
            .claim("email", "a@b.com")
            .claim("purpose", UnsubscribeTokenService.PURPOSE)
            .expiration(Date.from(NOW.minusSeconds(10)))
            .signWith(key)
            .compact();
    assertThatThrownBy(() -> tokens.parse(expired))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOKEN_EXPIRED");

    UnsubscribeTokenService longSecret =
        new UnsubscribeTokenService("test-email-unsubscribe-secret-key!!", clock);
    assertThat(longSecret.issue("z@z.com", customerId)).isNotBlank();
  }
}
