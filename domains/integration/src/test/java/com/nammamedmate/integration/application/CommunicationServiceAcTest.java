package com.nammamedmate.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.nammamedmate.integration.adapter.out.client.StubCommunicationProvider;
import com.nammamedmate.integration.application.port.out.CommunicationProviderPort;
import com.nammamedmate.integration.application.port.out.IntegrationEventPort;
import com.nammamedmate.integration.domain.CommunicationChannelConfig;
import com.nammamedmate.integration.domain.CommunicationChannels;
import com.nammamedmate.integration.domain.CommunicationProviders;
import com.nammamedmate.integration.domain.CommunicationStatuses;
import com.nammamedmate.integration.support.InMemoryStores;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommunicationServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:38:00Z");

  private InMemoryStores.CommsConfigs configs;
  private InMemoryStores.CommsCosts costs;
  private InMemoryStores.CommsAudits audits;
  private InMemoryStores.CommsSecrets secrets;
  private StubCommunicationProvider providers;
  private IntegrationEventPort events;
  private AtomicReference<Instant> instant;
  private CommunicationService service;
  private MedmatePrincipal adminSuper;
  private MedmatePrincipal adminOps;

  @BeforeEach
  void setUp() {
    configs = new InMemoryStores.CommsConfigs().seedDefaults(NOW);
    costs = new InMemoryStores.CommsCosts();
    audits = new InMemoryStores.CommsAudits();
    secrets = new InMemoryStores.CommsSecrets();
    providers = new StubCommunicationProvider();
    events = mock(IntegrationEventPort.class);
    instant = new AtomicReference<>(NOW);
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant.get(), zone);
          }

          @Override
          public Instant instant() {
            return instant.get();
          }
        };
    service = new CommunicationService(configs, costs, audits, secrets, providers, events, clock);
    adminSuper =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    adminOps =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac001_statusDownAndOverallDegraded() {
    CommunicationChannelConfig sms = configs.findByChannel("SMS").orElseThrow();
    configs.update(
        new CommunicationChannelConfig(
            sms.channel(),
            sms.enabled(),
            sms.provider(),
            sms.fallbackProvider(),
            sms.secretsManagerKey(),
            sms.dailySendLimit(),
            sms.dailySentCount(),
            CommunicationStatuses.DOWN,
            sms.lastHealthCheckAt(),
            sms.updatedBy(),
            sms.updatedAt()));
    Map<String, Object> data = service.status(adminOps);
    assertThat(data.get("overall_status")).isEqualTo(CommunicationStatuses.DEGRADED);
    List<Map<String, Object>> channels = (List<Map<String, Object>>) data.get("channels");
    assertThat(channels).anyMatch(c -> "DOWN".equals(c.get("status")));
    assertThat(channels.get(0).get("last_successful_send")).isNull();
  }

  @Test
  void ac002_patchConfigFailedConnectivityRetainsSecrets() {
    String before = secrets.get("medmate/comms/sms").orElseThrow().get("api_key");
    assertThatThrownBy(
            () ->
                service.patchConfig(
                    adminSuper,
                    "SMS",
                    Map.of("api_credentials", Map.of("api_key", "fail-key", "sender_id", "X"))))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CONNECTIVITY_TEST_FAILED");
    assertThat(secrets.get("medmate/comms/sms").orElseThrow().get("api_key")).isEqualTo(before);
    assertThat(audits.findByChannel("SMS")).hasSize(1);
    assertThat(audits.findByChannel("SMS").get(0).connectivityTestResult()).isEqualTo("FAILED");
  }

  @Test
  void ac003_patchConfigMasksApiKeyPreview() {
    Map<String, Object> data =
        service.patchConfig(
            adminSuper,
            "SMS",
            Map.of(
                "api_credentials",
                Map.of("api_key", "new-msg91-api-key-here", "sender_id", "NMMATE")));
    assertThat(data.get("api_key_preview")).isEqualTo("new-****");
    assertThat(data.get("connectivity_test_result")).isEqualTo("PASSED");
    assertThat(data.toString()).doesNotContain("new-msg91-api-key-here");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac004_usageCostTodayFromRates() {
    service.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION");
    Map<String, Object> usage = service.usage(adminOps, "SMS");
    List<Map<String, Object>> rows = (List<Map<String, Object>>) usage.get("usage");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("cost_today_rs")).isEqualTo(new BigDecimal("0.12"));
  }

  @Test
  void ac005_testSendIsTestAndIncrementsDailyCount() {
    Map<String, Object> data =
        service.testSend(adminOps, "sms", "+919876543210", "otp_verification");
    assertThat(data.get("is_test")).isEqualTo(true);
    assertThat(data.get("status")).isEqualTo("SENT");
    assertThat(configs.findByChannel("SMS").orElseThrow().dailySentCount()).isEqualTo(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac006_msg91DownRoutesViaTwilioFallback() {
    CommunicationChannelConfig sms = configs.findByChannel("SMS").orElseThrow();
    configs.update(
        new CommunicationChannelConfig(
            sms.channel(),
            true,
            CommunicationProviders.MSG91,
            CommunicationProviders.TWILIO,
            sms.secretsManagerKey(),
            sms.dailySendLimit(),
            0,
            CommunicationStatuses.DOWN,
            sms.lastHealthCheckAt(),
            sms.updatedBy(),
            sms.updatedAt()));
    Map<String, Object> sent =
        service.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION");
    assertThat(sent.get("provider")).isEqualTo(CommunicationProviders.TWILIO);
    Map<String, Object> usage = service.usage(adminOps, "SMS");
    List<Map<String, Object>> rows = (List<Map<String, Object>>) usage.get("usage");
    assertThat(rows.get(0).get("fallback_sent_today")).isEqualTo(1);
  }

  @Test
  void ac007_channelLimitWarningAt80Percent() {
    CommunicationChannelConfig sms = configs.findByChannel("SMS").orElseThrow();
    configs.update(
        new CommunicationChannelConfig(
            sms.channel(),
            true,
            sms.provider(),
            sms.fallbackProvider(),
            sms.secretsManagerKey(),
            10,
            7,
            sms.currentStatus(),
            sms.lastHealthCheckAt(),
            sms.updatedBy(),
            sms.updatedAt()));
    service.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION");
    verify(events)
        .publish(
            eq("integration.comms.channel_limit_warning"),
            eq("communication_channel"),
            any(),
            any());
    // second send past 80% does not re-alert same day
    service.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION");
    verify(events)
        .publish(
            eq("integration.comms.channel_limit_warning"),
            eq("communication_channel"),
            any(),
            any());
  }

  @Test
  void ac008_configChangeWritesAuditWithMaskedCredentials() {
    service.patchConfig(
        adminSuper,
        "EMAIL",
        Map.of(
            "is_enabled",
            true,
            "daily_send_limit",
            99999,
            "api_credentials",
            Map.of("api_key", "sg-live-key-0001")));
    assertThat(audits.findByChannel("EMAIL")).hasSize(1);
    Map<String, Object> fields = audits.findByChannel("EMAIL").get(0).changedFields();
    assertThat(fields).containsKey("api_credentials");
    @SuppressWarnings("unchecked")
    Map<String, String> creds = (Map<String, String>) fields.get("api_credentials");
    assertThat(creds.get("api_key")).isEqualTo("sg-l****");
  }

  @Test
  void authAndValidationBranches() {
    assertThatThrownBy(() -> service.status(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.status(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.patchConfig(adminOps, "SMS", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.patchConfig(null, "SMS", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.testSend(adminSuper, "FAX", "x", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CHANNEL");
    assertThatThrownBy(() -> service.testSend(adminSuper, "SMS", " ", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.testSend(adminSuper, "SMS", null, "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.testSend(adminSuper, "SMS", "+91", "NOPE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_NOT_FOUND");
    assertThatThrownBy(() -> service.usage(adminSuper, "FAX"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_CHANNEL");
    assertThatThrownBy(() -> service.patchConfig(adminSuper, "FAX", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_NOT_FOUND");
  }

  @Test
  void testSendProviderFailuresAndDisabledChannel() {
    CommunicationChannelConfig push = configs.findByChannel("PUSH").orElseThrow();
    configs.update(
        new CommunicationChannelConfig(
            push.channel(),
            false,
            push.provider(),
            null,
            push.secretsManagerKey(),
            push.dailySendLimit(),
            0,
            push.currentStatus(),
            push.lastHealthCheckAt(),
            push.updatedBy(),
            push.updatedAt()));
    assertThatThrownBy(() -> service.testSend(adminOps, "PUSH", "token", "TEST_PUSH"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");

    providers.markDown(CommunicationProviders.MSG91);
    assertThatThrownBy(() -> service.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");

    CommunicationProviderPort mockProviders = mock(CommunicationProviderPort.class);
    doThrow(new RuntimeException("boom"))
        .when(mockProviders)
        .sendTest(any(), any(), any(), any(), anyBoolean());
    CommunicationService withMock =
        new CommunicationService(
            configs,
            costs,
            audits,
            secrets,
            mockProviders,
            events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    configs.update(
        new CommunicationChannelConfig(
            "SMS",
            true,
            CommunicationProviders.MSG91,
            CommunicationProviders.TWILIO,
            "medmate/comms/sms",
            50000,
            0,
            CommunicationStatuses.HEALTHY,
            NOW,
            null,
            NOW));
    assertThatThrownBy(
            () -> withMock.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");

    doReturn(null).when(mockProviders).sendTest(any(), any(), any(), any(), anyBoolean());
    assertThatThrownBy(
            () -> withMock.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");

    doReturn(new CommunicationProviderPort.SendResult(UUID.randomUUID(), "FAILED"))
        .when(mockProviders)
        .sendTest(any(), any(), any(), any(), anyBoolean());
    assertThatThrownBy(
            () -> withMock.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");
  }

  @Test
  void channelNotFoundOnTestSend() {
    InMemoryStores.CommsConfigs empty = new InMemoryStores.CommsConfigs();
    CommunicationService isolated =
        new CommunicationService(
            empty, costs, audits, secrets, providers, events, Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(
            () -> isolated.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_NOT_FOUND");
    assertThatThrownBy(() -> isolated.patchConfig(adminSuper, "SMS", Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CHANNEL_NOT_FOUND");
  }

  @Test
  @SuppressWarnings("unchecked")
  void usageFiltersLimitsAndDeliveryRates() {
    assertThat(((List<?>) service.usage(adminSuper, null).get("usage"))).hasSize(4);
    assertThat(((List<?>) service.usage(adminSuper, "  ").get("usage"))).hasSize(4);
    assertThat(((List<?>) service.usage(adminSuper, "EMAIL").get("usage"))).hasSize(1);

    CommunicationChannelConfig email = configs.findByChannel("EMAIL").orElseThrow();
    configs.update(
        new CommunicationChannelConfig(
            email.channel(),
            true,
            email.provider(),
            email.fallbackProvider(),
            email.secretsManagerKey(),
            0,
            5,
            email.currentStatus(),
            email.lastHealthCheckAt(),
            email.updatedBy(),
            email.updatedAt()));
    Map<String, Object> zeroLimit = service.usage(adminSuper, "EMAIL");
    List<Map<String, Object>> rows = (List<Map<String, Object>>) zeroLimit.get("usage");
    assertThat(rows.get(0).get("daily_limit_pct_used")).isEqualTo(0.0);
    assertThat(rows.get(0).get("delivery_rate_pct")).isEqualTo(100.0);

    costs.upsertIncrement(
        LocalDate.of(2026, 7, 24),
        "WHATSAPP",
        CommunicationProviders.META_CLOUD_API,
        10,
        8,
        0,
        new BigDecimal("8.50"));
    List<Map<String, Object>> wa =
        (List<Map<String, Object>>) service.usage(adminSuper, "WHATSAPP").get("usage");
    assertThat(wa.get(0).get("delivery_rate_pct")).isEqualTo(80.0);
  }

  @Test
  void patchConfigAllFieldBranches() {
    service.patchConfig(adminSuper, "SMS", null);
    service.patchConfig(adminSuper, "SMS", Map.of());
    service.patchConfig(adminSuper, "SMS", Map.of("is_enabled", true));
    service.patchConfig(adminSuper, "SMS", Map.of("is_enabled", false));
    service.patchConfig(
        adminSuper, "SMS", Map.of("is_enabled", true, "provider", CommunicationProviders.MSG91));
    assertThatThrownBy(() -> service.patchConfig(adminSuper, "SMS", Map.of("provider", "UNKNOWN")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.patchConfig(adminSuper, "SMS", Map.of("provider", CommunicationProviders.TWILIO));
    service.patchConfig(adminSuper, "SMS", Map.of("fallback_provider", ""));
    Map<String, Object> clearFallback = new HashMap<>();
    clearFallback.put("fallback_provider", null);
    service.patchConfig(adminSuper, "SMS", clearFallback); // already null → no-op
    service.patchConfig(
        adminSuper, "SMS", Map.of("fallback_provider", CommunicationProviders.MSG91));
    service.patchConfig(
        adminSuper, "SMS", Map.of("fallback_provider", CommunicationProviders.MSG91)); // same
    clearFallback.put("fallback_provider", null);
    service.patchConfig(adminSuper, "SMS", clearFallback);
    assertThatThrownBy(
            () -> service.patchConfig(adminSuper, "SMS", Map.of("fallback_provider", "NOPE")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.patchConfig(adminSuper, "SMS", Map.of("daily_send_limit", -1)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.patchConfig(adminSuper, "SMS", Map.of("daily_send_limit", 50000));
    service.patchConfig(adminSuper, "SMS", Map.of("daily_send_limit", "60000"));
    service.patchConfig(adminSuper, "SMS", Map.of("api_credentials", "not-a-map"));
    service.patchConfig(adminSuper, "SMS", Map.of("api_credentials", Map.of()));
    Map<String, Object> creds = new HashMap<>();
    creds.put("api_key", "ok-key-1234");
    creds.put("sender_id", null);
    service.patchConfig(adminSuper, "SMS", Map.of("api_credentials", creds));

    CommunicationService noSecretPreview =
        new CommunicationService(
            configs,
            costs,
            audits,
            new com.nammamedmate.integration.application.port.out.CommunicationSecretsStore() {
              @Override
              public java.util.Optional<Map<String, String>> get(String secretsManagerKey) {
                return java.util.Optional.empty();
              }

              @Override
              public void put(String secretsManagerKey, Map<String, String> credentials) {}
            },
            providers,
            events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> patched =
        noSecretPreview.patchConfig(adminSuper, "PUSH", Map.of("daily_send_limit", 1));
    assertThat(patched.get("api_key_preview")).isEqualTo("****");
  }

  @Test
  void healthChecksAndDailyResetAndLookup() {
    costs.upsertIncrement(
        LocalDate.of(2026, 7, 24),
        "SMS",
        CommunicationProviders.MSG91,
        100,
        90,
        0,
        new BigDecimal("12.00"));
    providers.markDown(CommunicationProviders.FIREBASE_FCM);
    service.runHealthChecks();
    assertThat(configs.findByChannel("PUSH").orElseThrow().currentStatus())
        .isEqualTo(CommunicationStatuses.DOWN);
    assertThat(configs.findByChannel("SMS").orElseThrow().currentStatus())
        .isEqualTo(CommunicationStatuses.DEGRADED);

    providers.markHealthy(CommunicationProviders.FIREBASE_FCM);
    costs.upsertIncrement(
        LocalDate.of(2026, 7, 24),
        "EMAIL",
        CommunicationProviders.SENDGRID,
        10,
        10,
        0,
        BigDecimal.ONE);
    // seed last successful send then age past 30 minutes with sent > 0 → DOWN
    service.testSend(adminOps, "EMAIL", "a@b.com", "TEST_EMAIL");
    instant.set(NOW.plusSeconds(31 * 60));
    service.runHealthChecks();
    assertThat(configs.findByChannel("EMAIL").orElseThrow().currentStatus())
        .isEqualTo(CommunicationStatuses.DOWN);

    // healthy: recent send
    instant.set(NOW.plusSeconds(32 * 60));
    service.testSend(adminOps, "WHATSAPP", "+9198", "OTP_VERIFICATION");
    service.runHealthChecks();
    assertThat(configs.findByChannel("WHATSAPP").orElseThrow().currentStatus())
        .isEqualTo(CommunicationStatuses.HEALTHY);

    // healthy: stale lastSuccessfulSend but rates sent == 0
    InMemoryStores.CommsCosts emptyCosts = new InMemoryStores.CommsCosts();
    AtomicReference<Instant> m2 = new AtomicReference<>(NOW);
    CommunicationService svc2 =
        new CommunicationService(
            configs,
            emptyCosts,
            audits,
            secrets,
            providers,
            events,
            new Clock() {
              @Override
              public ZoneOffset getZone() {
                return ZoneOffset.UTC;
              }

              @Override
              public Clock withZone(java.time.ZoneId zone) {
                return Clock.fixed(m2.get(), zone);
              }

              @Override
              public Instant instant() {
                return m2.get();
              }
            });
    try {
      var field = CommunicationService.class.getDeclaredField("lastSuccessfulSend");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      var map = (java.util.concurrent.ConcurrentHashMap<String, Instant>) field.get(svc2);
      map.put("PUSH", NOW.minusSeconds(40 * 60));
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    m2.set(NOW);
    svc2.runHealthChecks();
    assertThat(configs.findByChannel("PUSH").orElseThrow().currentStatus())
        .isEqualTo(CommunicationStatuses.HEALTHY);

    service.resetDailySentCounts();
    assertThat(configs.findByChannel("SMS").orElseThrow().dailySentCount()).isEqualTo(0);

    assertThat(service.find("fax")).isEmpty();
    assertThat(service.find("SMS")).isPresent();
    assertThat(service.resolveActiveProvider("SMS")).contains(CommunicationProviders.MSG91);

    CommunicationChannelConfig disabled =
        configs.findByChannel(CommunicationChannels.WHATSAPP).orElseThrow();
    configs.update(
        new CommunicationChannelConfig(
            disabled.channel(),
            false,
            disabled.provider(),
            null,
            disabled.secretsManagerKey(),
            disabled.dailySendLimit(),
            0,
            disabled.currentStatus(),
            disabled.lastHealthCheckAt(),
            disabled.updatedBy(),
            disabled.updatedAt()));
    assertThat(service.resolveActiveProvider("WHATSAPP")).isEmpty();

    // disabled with fallback
    configs.update(
        new CommunicationChannelConfig(
            "WHATSAPP",
            false,
            CommunicationProviders.META_CLOUD_API,
            CommunicationProviders.TWILIO,
            "medmate/comms/whatsapp",
            20000,
            0,
            CommunicationStatuses.HEALTHY,
            NOW,
            null,
            NOW));
    assertThat(service.resolveActiveProvider("WHATSAPP")).contains(CommunicationProviders.TWILIO);

    // DOWN without fallback keeps primary
    configs.update(
        new CommunicationChannelConfig(
            "PUSH",
            true,
            CommunicationProviders.FIREBASE_FCM,
            null,
            "medmate/comms/push",
            100000,
            0,
            CommunicationStatuses.DOWN,
            NOW,
            null,
            NOW));
    assertThat(service.resolveActiveProvider("PUSH")).contains(CommunicationProviders.FIREBASE_FCM);

    // day rollover resets
    service.status(adminSuper); // sets lastResetIstDate
    instant.set(Instant.parse("2026-07-25T18:31:00Z")); // next IST day
    configs.update(
        new CommunicationChannelConfig(
            "SMS",
            true,
            CommunicationProviders.MSG91,
            CommunicationProviders.TWILIO,
            "medmate/comms/sms",
            50000,
            9,
            CommunicationStatuses.HEALTHY,
            NOW,
            null,
            NOW));
    service.status(adminSuper);
    assertThat(configs.findByChannel("SMS").orElseThrow().dailySentCount()).isEqualTo(0);

    // maybeWarnLimit when dailySendLimit <= 0
    configs.update(
        new CommunicationChannelConfig(
            "SMS",
            true,
            CommunicationProviders.MSG91,
            CommunicationProviders.TWILIO,
            "medmate/comms/sms",
            0,
            0,
            CommunicationStatuses.HEALTHY,
            NOW,
            null,
            NOW));
    service.testSend(adminOps, "SMS", "+91", "OTP_VERIFICATION");
    verify(events, never())
        .publish(eq("integration.comms.channel_limit_warning"), anyString(), any(), any());

    // pct < 80
    configs.update(
        new CommunicationChannelConfig(
            "SMS",
            true,
            CommunicationProviders.MSG91,
            CommunicationProviders.TWILIO,
            "medmate/comms/sms",
            100,
            0,
            CommunicationStatuses.HEALTHY,
            NOW,
            null,
            NOW));
    service.testSend(adminOps, "SMS", "+91", "OTP_VERIFICATION");
  }

  @Test
  void statusIncludesLastSuccessfulSendIso() {
    service.testSend(adminOps, "SMS", "+919876543210", "OTP_VERIFICATION");
    Map<String, Object> data = service.status(adminSuper);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> channels = (List<Map<String, Object>>) data.get("channels");
    assertThat(channels).anyMatch(c -> c.get("last_successful_send") != null);
    assertThat(data.get("overall_status")).isEqualTo(CommunicationStatuses.HEALTHY);
  }
}
