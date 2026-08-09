package com.nammamedmate.api.config;

import com.nammamedmate.integration.application.EinvoiceService;
import com.nammamedmate.integration.application.port.in.EinvoicePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition-root bridge: expose {@link EinvoicePort} for POS/finance finalize hooks later. {@link
 * EinvoiceService} already implements the port; this bean is for {@code @ConditionalOnMissingBean}
 * replacement by tests/stubs.
 */
@Configuration
public class IntegrationEinvoiceBridgeConfig {

  @Bean
  @ConditionalOnMissingBean(EinvoicePort.class)
  EinvoicePort integrationEinvoicePort(EinvoiceService service) {
    return service::generateIrn;
  }
}
