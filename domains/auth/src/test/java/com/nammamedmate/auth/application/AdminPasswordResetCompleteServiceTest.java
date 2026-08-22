package com.nammamedmate.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AdminPasswordResetCompleteServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  void completesResetAndRejectsBadInput() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    AdminPasswordResetCompleteService service =
        new AdminPasswordResetCompleteService(
            jdbc, new BCryptPasswordEncoder(), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> service.complete(null, "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.complete("tok", "short"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(jdbc.queryForList(anyString(), any(Object.class))).thenReturn(List.of());
    assertThatThrownBy(() -> service.complete("tok", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_INVALID");

    UUID id = Ids.newId();
    when(jdbc.queryForList(anyString(), any(Object.class)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    id,
                    "email",
                    "ops@test.in",
                    "reset_token_expires_at",
                    Timestamp.from(NOW.minusSeconds(1)))));
    assertThatThrownBy(() -> service.complete("tok", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_EXPIRED");

    when(jdbc.queryForList(anyString(), any(Object.class)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    id,
                    "email",
                    "ops@test.in",
                    "reset_token_expires_at",
                    Timestamp.from(NOW.plusSeconds(3600)))));
    when(jdbc.update(anyString(), any(), any(), eq(id), any())).thenReturn(1);
    assertThat(service.complete("tok", "Passw0rd!").get("status")).isEqualTo("ACTIVE");

    when(jdbc.update(anyString(), any(), any(), eq(id), any())).thenReturn(0);
    assertThatThrownBy(() -> service.complete("tok", "Passw0rd!"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RESET_INVALID");

    AdminPasswordResetCompleteService badDigest =
        new AdminPasswordResetCompleteService(
                jdbc, new BCryptPasswordEncoder(), Clock.fixed(NOW, ZoneOffset.UTC))
            .withDigests(
                () -> {
                  throw new java.security.NoSuchAlgorithmException("test");
                });
    assertThatThrownBy(() -> badDigest.complete("tok", "Passw0rd!"))
        .isInstanceOf(IllegalStateException.class);
  }
}
