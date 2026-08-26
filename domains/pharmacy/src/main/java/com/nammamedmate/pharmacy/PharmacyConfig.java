package com.nammamedmate.pharmacy;

import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubPennyDropClient;
import com.nammamedmate.pharmacy.adapter.out.messaging.StubNotificationDispatchClient;
import com.nammamedmate.pharmacy.adapter.out.metrics.StubPharmacyOrderMetricsClient;
import com.nammamedmate.pharmacy.adapter.out.payout.StubCashfreePayoutClient;
import com.nammamedmate.pharmacy.application.port.out.CashfreePayoutPort;
import com.nammamedmate.pharmacy.application.port.out.NotificationDispatchPort;
import com.nammamedmate.pharmacy.application.port.out.PennyDropPort;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOrderMetricsPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PharmacyConfig {

  @Bean
  @ConditionalOnMissingBean(PennyDropPort.class)
  PennyDropPort pennyDropPort() {
    return new StubPennyDropClient();
  }

  @Bean
  @ConditionalOnMissingBean(PharmacyOrderMetricsPort.class)
  PharmacyOrderMetricsPort pharmacyOrderMetricsPort() {
    return new StubPharmacyOrderMetricsClient();
  }

  // CatalogueVisibilityPort + PharmacyCatalogueStatsPort: real beans in apps/api
  // CataloguePharmacyBridgeConfig (no domain→domain deps). Stubs remain for unit tests.

  @Bean
  @ConditionalOnMissingBean(NotificationDispatchPort.class)
  NotificationDispatchPort notificationDispatchPort(OutboxPublisher outbox) {
    return new StubNotificationDispatchClient(outbox);
  }

  @Bean
  @ConditionalOnMissingBean(CashfreePayoutPort.class)
  CashfreePayoutPort cashfreeXPayoutPort() {
    return new StubCashfreePayoutClient();
  }
}
