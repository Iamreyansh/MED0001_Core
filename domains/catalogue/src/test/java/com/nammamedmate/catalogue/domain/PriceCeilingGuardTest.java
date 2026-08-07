package com.nammamedmate.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceCeilingGuardTest {

  @Test
  void allowsWhenNoCeilingOrWithinCeiling() {
    assertThatCode(() -> PriceCeilingGuard.assertWithinCeiling("Amox", null, 8000))
        .doesNotThrowAnyException();
    assertThatCode(() -> PriceCeilingGuard.assertWithinCeiling("Amox", 7200L, 7200))
        .doesNotThrowAnyException();
    assertThatCode(() -> PriceCeilingGuard.assertWithinCeiling("Amox", 7200L, 7000))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWhenAboveCeiling() {
    assertThatThrownBy(
            () -> PriceCeilingGuard.assertWithinCeiling("Amoxicillin 500mg", 7200L, 8000))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("PRICE_CEILING_VIOLATED");
              assertThat(ae.httpStatus()).isEqualTo(400);
              assertThat(ae.getMessage()).contains("Amoxicillin 500mg");
              assertThat(ae.getMessage()).contains("72.00");
              assertThat(ae.getMessage()).contains("80.00");
            });
  }

  @Test
  void blankNameFallsBack() {
    assertThatThrownBy(() -> PriceCeilingGuard.assertWithinCeiling("  ", 100L, 200))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).getMessage()).contains("medicine"));
    assertThatThrownBy(() -> PriceCeilingGuard.assertWithinCeiling(null, 100L, 200))
        .isInstanceOf(AppException.class)
        .satisfies(ex -> assertThat(((AppException) ex).getMessage()).contains("medicine"));
  }

  @Test
  void paiseToRupeesScaled() {
    assertThat(PriceCeilingGuard.paiseToRupees(721)).isEqualByComparingTo(new BigDecimal("7.21"));
  }
}
