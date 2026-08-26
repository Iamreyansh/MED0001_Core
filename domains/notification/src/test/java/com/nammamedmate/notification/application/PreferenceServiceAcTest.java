package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.notification.application.port.out.CustomerPreferenceStore;
import com.nammamedmate.notification.application.port.out.PharmacyPreferenceStore;
import com.nammamedmate.notification.application.port.out.PreferenceAuditStore;
import com.nammamedmate.notification.domain.CustomerNotificationPreferences;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreferenceServiceAcTest {

  @Test
  void whatsappAndEmailChannelsMarkedUnavailable() {
    CustomerPreferenceStore customers = mock(CustomerPreferenceStore.class);
    PharmacyPreferenceStore pharmacies = mock(PharmacyPreferenceStore.class);
    PreferenceAuditStore audits = mock(PreferenceAuditStore.class);
    UUID customerId = UUID.randomUUID();
    Instant now = Instant.parse("2026-06-15T10:00:00Z");
    when(customers.findByCustomerId(customerId))
        .thenReturn(
            Optional.of(
                CustomerNotificationPreferences.defaults(UUID.randomUUID(), customerId, now)));
    PreferenceService service =
        new PreferenceService(customers, pharmacies, audits, Clock.fixed(now, ZoneOffset.UTC));
    Map<String, Object> data = service.getCustomerPreferences(customerId);
    @SuppressWarnings("unchecked")
    Map<String, Object> channels = (Map<String, Object>) data.get("channels");
    @SuppressWarnings("unchecked")
    Map<String, Object> wa = (Map<String, Object>) channels.get("whatsapp");
    assertThat(wa.get("status")).isEqualTo("CHANNEL_UNAVAILABLE");
  }
}
