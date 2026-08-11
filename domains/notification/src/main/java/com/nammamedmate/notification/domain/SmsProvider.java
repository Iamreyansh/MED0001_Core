package com.nammamedmate.notification.domain;

import java.math.BigDecimal;

public enum SmsProvider {
  MSG91(new BigDecimal("0.12")),
  TWILIO(new BigDecimal("0.20"));

  private final BigDecimal costRs;

  SmsProvider(BigDecimal costRs) {
    this.costRs = costRs;
  }

  public BigDecimal costRs() {
    return costRs;
  }
}
