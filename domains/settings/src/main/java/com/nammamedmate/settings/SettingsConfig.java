package com.nammamedmate.settings;

import com.nammamedmate.settings.application.port.out.AdminSessionRevokePort;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SettingsConfig {

  /** No-op until apps/api wires AuthSessionStore via SettingsAuthBridgeConfig. */
  @Bean
  @ConditionalOnMissingBean(AdminSessionRevokePort.class)
  AdminSessionRevokePort stubAdminSessionRevokePort() {
    return staffId -> {};
  }

  @Bean(name = "auditExecutor")
  @ConditionalOnMissingBean(name = "auditExecutor")
  Executor auditExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
