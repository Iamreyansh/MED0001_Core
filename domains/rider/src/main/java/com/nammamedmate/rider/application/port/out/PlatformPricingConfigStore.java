package com.nammamedmate.rider.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlatformPricingConfigStore {

  Optional<String> get(String key);

  BigDecimal handlingFeeRupees();

  void upsert(String key, String value, String description, UUID updatedBy, Instant now);
}
