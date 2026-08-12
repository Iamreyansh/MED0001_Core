package com.nammamedmate.automation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HealthDomainTest {

  @Test
  void killSwitchActionParsesAndMaps() {
    assertThat(KillSwitchAction.parse("pause")).isEqualTo(KillSwitchAction.PAUSE);
    assertThat(KillSwitchAction.PAUSE.toStatus()).isEqualTo(KillSwitchStatus.PAUSED);
    assertThat(KillSwitchAction.RESUME.toStatus()).isEqualTo(KillSwitchStatus.ACTIVE);
    assertThatThrownBy(() -> KillSwitchAction.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KillSwitchAction.parse(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void circuitStatusAndAutoReset() {
    assertThat(CircuitStatus.from(null)).isEqualTo(CircuitStatus.CLOSED);
    assertThat(CircuitStatus.from(" ")).isEqualTo(CircuitStatus.CLOSED);
    assertThat(CircuitStatus.from("open")).isEqualTo(CircuitStatus.OPEN);
    assertThat(CircuitStatus.parse(null)).isEqualTo(CircuitStatus.CLOSED);
    assertThat(CircuitStatus.parse(" ")).isEqualTo(CircuitStatus.CLOSED);
    assertThat(CircuitStatus.parse("OPEN")).isEqualTo(CircuitStatus.OPEN);
    assertThat(CircuitStatus.parse("nope")).isEqualTo(CircuitStatus.CLOSED);

    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    CircuitBreakerState open =
        new CircuitBreakerState(
            "apply_wallet_credit",
            0,
            null,
            -1,
            Instant.parse("2026-07-24T09:30:00Z"),
            Instant.parse("2026-07-24T10:00:00Z"),
            now);
    assertThat(open.thresholdPerHour()).isEqualTo(50);
    assertThat(open.status()).isEqualTo(CircuitStatus.CLOSED);
    assertThat(open.firesLastHour()).isZero();

    CircuitBreakerState tripped =
        new CircuitBreakerState("x", 50, CircuitStatus.OPEN, 51, now.minusSeconds(60), now, now);
    assertThat(tripped.maybeAutoReset(now.minusSeconds(1)).status()).isEqualTo(CircuitStatus.OPEN);
    assertThat(tripped.maybeAutoReset(now).status()).isEqualTo(CircuitStatus.CLOSED);
    assertThat(tripped.maybeAutoReset(null).status()).isEqualTo(CircuitStatus.OPEN);
    assertThat(
            new CircuitBreakerState("x", 50, CircuitStatus.OPEN, 51, now, null, now)
                .maybeAutoReset(now)
                .status())
        .isEqualTo(CircuitStatus.OPEN);
    assertThat(tripped.shouldOpen(50)).isTrue();
    assertThat(tripped.shouldOpen(49)).isFalse();
    CircuitBreakerState opened = tripped.open(now, 52, 0);
    assertThat(opened.resetAt()).isEqualTo(now.plusSeconds(30 * 60));
    assertThat(opened.open(now, 52, 15).resetAt()).isEqualTo(now.plusSeconds(15 * 60));
    assertThat(opened.open(null, 52, 15).openedAt()).isEqualTo(Instant.EPOCH);
    assertThat(opened.withFires(3, now).firesLastHour()).isEqualTo(3);
    assertThat(CircuitBreakerState.parseResetMinutes(null)).isEqualTo(30);
    assertThat(CircuitBreakerState.parseResetMinutes(" ")).isEqualTo(30);
    assertThat(CircuitBreakerState.parseResetMinutes("15")).isEqualTo(15);
    assertThat(CircuitBreakerState.parseResetMinutes("0")).isEqualTo(30);
    assertThat(CircuitBreakerState.parseResetMinutes("nope")).isEqualTo(30);
  }

  @Test
  void ruleHealthSuccessRateAndDeferredCopy() {
    RuleHealthMetrics zero =
        new RuleHealthMetrics(UUID.randomUUID(), "n", "ACTIVE", 0, 0, 0, null, null, null, null);
    assertThat(zero.successRatePct()).isEqualTo(0.0);
    RuleHealthMetrics mixed =
        new RuleHealthMetrics(
            UUID.randomUUID(), "n", "ACTIVE", 48, 47, 1, "e", Instant.EPOCH, 1, Instant.EPOCH);
    assertThat(mixed.successRatePct()).isEqualTo(97.9);
    DeferredExecution d =
        new DeferredExecution(UUID.randomUUID(), UUID.randomUUID(), "x", null, null, Instant.EPOCH);
    assertThat(d.actionParams()).isEmpty();
    assertThat(d.executionContext()).isEmpty();
    assertThat(
            new DeferredExecution(
                    d.id(), d.approvalId(), "x", Map.of("a", 1), Map.of("b", 2), Instant.EPOCH)
                .actionParams())
        .containsEntry("a", 1);
  }
}
