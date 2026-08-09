package com.nammamedmate.crm;

import com.nammamedmate.crm.application.port.out.PharmacyPlanSyncPort;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CrmConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock crmClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean(PharmacyPlanSyncPort.class)
  PharmacyPlanSyncPort noopPharmacyPlanSyncPort() {
    return (UUID pharmacyId, String crmPlanName) -> {};
  }
}
