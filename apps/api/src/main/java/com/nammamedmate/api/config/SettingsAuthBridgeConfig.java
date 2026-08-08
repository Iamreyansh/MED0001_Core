package com.nammamedmate.api.config;

import com.nammamedmate.auth.application.port.out.AuthSessionStore;
import com.nammamedmate.settings.application.port.out.AdminSessionRevokePort;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Composition-root bridge: settings AdminSessionRevokePort → auth
 * AuthSessionStore.revokeAllForUser.
 */
@Configuration
public class SettingsAuthBridgeConfig {

  @Bean
  @Primary
  AdminSessionRevokePort adminSessionRevokePort(AuthSessionStore sessionStore, Clock clock) {
    return staffId -> {
      if (staffId != null) {
        sessionStore.revokeAllForUser(staffId, clock.instant());
      }
    };
  }
}
