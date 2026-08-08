package com.nammamedmate.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.rider.application.port.out.PlatformPricingConfigStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodFloatLimitsTest {

  @Test
  void resolveAndRiskHelpers() {
    assertThat(CodFloatLimits.resolvePaise(null)).isEqualTo(200_000L);
    assertThat(CodFloatLimits.resolvePaise(cfg(Optional.empty()))).isEqualTo(200_000L);
    assertThat(CodFloatLimits.resolvePaise(cfg(Optional.of("250000")))).isEqualTo(250_000L);
    assertThat(CodFloatLimits.resolvePaise(cfg(Optional.of("2000.00")))).isEqualTo(200_000L);
    assertThat(CodFloatLimits.resolvePaise(cfg(Optional.of("nope")))).isEqualTo(200_000L);
    assertThat(CodFloatLimits.resolvePaise(cfg(Optional.of("")))).isEqualTo(200_000L);
    assertThat(CodFloatLimits.parsePaise(null)).isEqualTo(200_000L);

    assertThat(CodFloatLimits.isFloatRisk(200_001, 200_000)).isTrue();
    assertThat(CodFloatLimits.isFloatRisk(200_000, 200_000)).isFalse();
    assertThat(CodFloatLimits.canAcceptCod(200_000, 200_000)).isFalse();
    assertThat(CodFloatLimits.canAcceptCod(199_999, 200_000)).isTrue();
    assertThat(CodFloatLimits.riskStatus(200_001, 200_000)).isEqualTo("FLOAT_RISK");
    assertThat(CodFloatLimits.riskStatus(100, 200_000)).isEqualTo("SAFE");
    assertThat(CodFloatLimits.paiseToRupees(1850_00)).isEqualByComparingTo("1850.00");
    assertThat(CodFloatLimits.rupeesToPaise(new BigDecimal("20.50"))).isEqualTo(2050L);
    assertThat(CodFloatLimits.rupeesToPaise(10)).isEqualTo(1000L);
    assertThat(CodFloatLimits.rupeesToPaise("1.25")).isEqualTo(125L);
    assertThatThrownBy(() -> CodFloatLimits.rupeesToPaise(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PlatformPricingConfigStore cfg(Optional<String> value) {
    return new PlatformPricingConfigStore() {
      @Override
      public Optional<String> get(String key) {
        return value;
      }

      @Override
      public BigDecimal handlingFeeRupees() {
        return BigDecimal.ZERO;
      }

      @Override
      public void upsert(
          String key, String value, String description, UUID updatedBy, Instant now) {}
    };
  }
}
