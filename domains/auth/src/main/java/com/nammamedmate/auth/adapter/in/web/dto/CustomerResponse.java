package com.nammamedmate.auth.adapter.in.web.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomerResponse(
    UUID id,
    String phone,
    String name,
    String avatarUrl,
    LocalDate dateOfBirth,
    String gender,
    String preferredLanguage,
    String segment,
    BigDecimal walletBalance,
    int loyaltyPoints,
    Instant createdAt) {

  public static CustomerResponse from(CustomerRecord customer) {
    return new CustomerResponse(
        customer.id(),
        customer.phone(),
        customer.name(),
        customer.avatarUrl(),
        customer.dateOfBirth(),
        customer.gender(),
        customer.preferredLanguage(),
        customer.segment(),
        BigDecimal.valueOf(customer.walletBalancePaise()).movePointLeft(2),
        customer.loyaltyPoints(),
        customer.createdAt());
  }
}
