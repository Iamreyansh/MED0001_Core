package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.settings.application.PlatformConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettingsBridgeConfigTest {

  @Test
  void readsPlatformConfigWalletCap() {
    PlatformConfigService config = mock(PlatformConfigService.class);
    when(config.getRaw("payments.max_wallet_credit_per_transaction"))
        .thenReturn(Optional.of("1500"));
    SettingsBridgeConfig bridge = new SettingsBridgeConfig();
    assertThat(bridge.platformWalletCreditLimit(config, 100_000L).maxCreditPaise())
        .isEqualTo(150_000L);
  }

  @Test
  void fallsBackWhenConfigMissing() {
    PlatformConfigService config = mock(PlatformConfigService.class);
    when(config.getRaw("payments.max_wallet_credit_per_transaction")).thenReturn(Optional.empty());
    SettingsBridgeConfig bridge = new SettingsBridgeConfig();
    assertThat(bridge.platformWalletCreditLimit(config, 100_000L).maxCreditPaise())
        .isEqualTo(100_000L);
  }
}
