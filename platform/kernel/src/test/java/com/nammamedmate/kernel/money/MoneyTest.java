package com.nammamedmate.kernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void ofPaiseAndRupees() {
    assertThat(Money.ofPaise(199).paise()).isEqualTo(199);
    assertThat(Money.ofRupees(2).paise()).isEqualTo(200);
    assertThat(Money.ofPaise(250).rupeesFloor()).isEqualTo(2);
  }

  @Test
  void arithmeticAndFlags() {
    Money a = Money.ofPaise(100);
    Money b = Money.ofPaise(40);
    assertThat(a.plus(b)).isEqualTo(Money.ofPaise(140));
    assertThat(a.minus(b)).isEqualTo(Money.ofPaise(60));
    assertThat(Money.ofPaise(0).isZero()).isTrue();
    assertThat(Money.ofPaise(1).isZero()).isFalse();
    assertThat(Money.ofPaise(-1).isNegative()).isTrue();
    assertThat(Money.ofPaise(1).isNegative()).isFalse();
  }

  @Test
  void equalsHashCodeToString() {
    Money a = Money.ofPaise(10);
    assertThat(a).isEqualTo(Money.ofPaise(10));
    assertThat(a).isNotEqualTo(Money.ofPaise(11));
    assertThat(a).isNotEqualTo("x");
    assertThat(a.equals(null)).isFalse();
    assertThat(a).isEqualTo(a);
    assertThat(a.hashCode()).isEqualTo(Money.ofPaise(10).hashCode());
    assertThat(a.toString()).contains("10");
  }

  @Test
  void rejectsNullOperand() {
    assertThatThrownBy(() -> Money.ofPaise(1).plus(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Money.ofPaise(1).minus(null)).isInstanceOf(NullPointerException.class);
  }
}
