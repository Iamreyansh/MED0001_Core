package com.nammamedmate.integration.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nammamedmate.integration.application.port.in.CommunicationChannelLookupPort;
import com.nammamedmate.integration.application.port.out.CommunicationProviderPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Minimal coverage for STORY-006 domain stubs that share the integration JaCoCo bundle. */
class CommunicationDomainCoverageTest {

  @Test
  void channelsProvidersTemplatesAndRates() {
    assertTrue(CommunicationChannels.isValid("sms"));
    assertFalse(CommunicationChannels.isValid(null));
    assertFalse(CommunicationChannels.isValid("fax"));
    assertEquals("SMS", CommunicationChannels.normalize("sms"));
    assertNull(CommunicationChannels.normalize(null));

    assertTrue(CommunicationProviders.isValid("msg91"));
    assertFalse(CommunicationProviders.isValid(null));
    assertFalse(CommunicationProviders.isValid("unknown"));
    assertEquals("MSG91", CommunicationProviders.normalize("msg91"));
    assertNull(CommunicationProviders.normalize(null));

    assertTrue(CommunicationTemplates.isValid("SMS", "otp_verification"));
    assertTrue(CommunicationTemplates.isValid("WHATSAPP", "MARKETING_PROMO"));
    assertTrue(CommunicationTemplates.isValid("PUSH", "TEST_PUSH"));
    assertTrue(CommunicationTemplates.isValid("EMAIL", "TEST_EMAIL"));
    assertFalse(CommunicationTemplates.isValid("SMS", null));
    assertFalse(CommunicationTemplates.isValid("SMS", " "));
    assertFalse(CommunicationTemplates.isValid("fax", "OTP_VERIFICATION"));
    assertFalse(CommunicationTemplates.isValid("SMS", "UNKNOWN_TEMPLATE"));
    assertEquals("OTP_VERIFICATION", CommunicationTemplates.normalize("otp_verification"));
    assertNull(CommunicationTemplates.normalize(null));

    for (Class<?> type :
        List.of(
            CommunicationChannels.class,
            CommunicationProviders.class,
            CommunicationTemplates.class,
            CommunicationRates.class,
            CommunicationStatuses.class,
            CommunicationCredentialMask.class)) {
      try {
        var ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(type.getSimpleName(), e);
      }
    }

    assertEquals(CommunicationRates.SMS, CommunicationRates.rateFor("SMS", null));
    assertEquals(CommunicationRates.EMAIL, CommunicationRates.rateFor("EMAIL", null));
    assertEquals(CommunicationRates.PUSH, CommunicationRates.rateFor("PUSH", null));
    assertEquals(
        CommunicationRates.WHATSAPP_MARKETING,
        CommunicationRates.rateFor("WHATSAPP", "MARKETING_X"));
    assertEquals(
        CommunicationRates.WHATSAPP_UTILITY,
        CommunicationRates.rateFor("WHATSAPP", "UTILITY_ORDER"));
    assertEquals(CommunicationRates.WHATSAPP_UTILITY, CommunicationRates.rateFor("WHATSAPP", null));
    assertEquals(0, CommunicationRates.rateFor("FAX", null).compareTo(BigDecimal.ZERO));
    assertEquals(new BigDecimal("0.24"), CommunicationRates.cost("SMS", null, 2));

    assertTrue(CommunicationStatuses.isHealthy(CommunicationStatuses.HEALTHY));
    assertFalse(CommunicationStatuses.isHealthy(CommunicationStatuses.DOWN));
    assertNotNull(CommunicationStatuses.DEGRADED);
  }

  @Test
  void credentialMaskAndRecords() {
    assertEquals("****", CommunicationCredentialMask.apiKeyPreview(null));
    assertEquals("****", CommunicationCredentialMask.apiKeyPreview("  "));
    assertEquals("ab****", CommunicationCredentialMask.apiKeyPreview("ab"));
    assertEquals("abcd****", CommunicationCredentialMask.apiKeyPreview("abcdefgh"));

    Instant now = Instant.parse("2026-08-10T00:00:00Z");
    assertEquals(
        1,
        new CommunicationCostDaily(
                UUID.randomUUID(),
                LocalDate.of(2026, 8, 10),
                "SMS",
                "MSG91",
                1,
                1,
                0,
                new BigDecimal("0.12"),
                now)
            .sentCount());
    assertEquals(
        "SMS",
        new CommunicationConfigAudit(
                UUID.randomUUID(), "SMS", UUID.randomUUID(), Map.of("enabled", true), "OK", now)
            .channel());

    assertEquals("ok", new CommunicationProviderPort.SendResult(UUID.randomUUID(), "ok").status());
    assertEquals(
        "PUSH",
        new CommunicationChannelLookupPort.ChannelSnapshot(
                "PUSH", true, "FIREBASE_FCM", null, "HEALTHY", 100, 0)
            .channel());
  }
}
