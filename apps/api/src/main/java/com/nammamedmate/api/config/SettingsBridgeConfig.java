package com.nammamedmate.api.config;

import com.nammamedmate.customer.application.port.out.WalletCreditLimitPort;
import com.nammamedmate.settings.application.PlatformConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Platform config → runtime consumers (EPIC-021 / R15). */
@Configuration
public class SettingsBridgeConfig {

  @Bean
  @Primary
  WalletCreditLimitPort platformWalletCreditLimit(
      PlatformConfigService config,
      @Value("${medmate.wallet.max-credit-paise:100000}") long fallbackPaise) {
    return () ->
        config
            .getRaw("payments.max_wallet_credit_per_transaction")
            .flatMap(SettingsBridgeConfig::rupeesToPaise)
            .orElse(fallbackPaise);
  }

  private static java.util.Optional<Long> rupeesToPaise(String raw) {
    if (raw == null || raw.isBlank()) {
      return java.util.Optional.empty();
    }
    try {
      long rupees = Long.parseLong(raw.trim());
      return java.util.Optional.of(rupees * 100L);
    } catch (NumberFormatException ex) {
      return java.util.Optional.empty();
    }
  }
}
