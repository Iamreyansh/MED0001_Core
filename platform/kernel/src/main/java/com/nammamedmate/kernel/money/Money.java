package com.nammamedmate.kernel.money;

import java.util.Objects;

/** Indian Rupee money stored as paise (1 Rs = 100 paise). Never use floating point for currency. */
public final class Money {

  private final long paise;

  private Money(long paise) {
    this.paise = paise;
  }

  public static Money ofPaise(long paise) {
    return new Money(paise);
  }

  public static Money ofRupees(long rupees) {
    return new Money(Math.multiplyExact(rupees, 100L));
  }

  public long paise() {
    return paise;
  }

  public long rupeesFloor() {
    return paise / 100L;
  }

  public Money plus(Money other) {
    Objects.requireNonNull(other, "other");
    return ofPaise(Math.addExact(this.paise, other.paise));
  }

  public Money minus(Money other) {
    Objects.requireNonNull(other, "other");
    return ofPaise(Math.subtractExact(this.paise, other.paise));
  }

  public boolean isNegative() {
    return paise < 0;
  }

  public boolean isZero() {
    return paise == 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Money money)) {
      return false;
    }
    return paise == money.paise;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(paise);
  }

  @Override
  public String toString() {
    return "Money{paise=" + paise + '}';
  }
}
