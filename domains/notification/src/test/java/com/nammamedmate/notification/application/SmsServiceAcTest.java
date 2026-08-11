package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.adapter.out.client.StubMsg91Client;
import com.nammamedmate.notification.adapter.out.client.StubTwilioClient;
import com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.SmsTemplateStore;
import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsDeliveryLog;
import com.nammamedmate.notification.domain.SmsLogStatus;
import com.nammamedmate.notification.domain.SmsProvider;
import com.nammamedmate.notification.domain.SmsTemplate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmsServiceAcTest {

  private static final Instant DAYTIME_UTC = Instant.parse("2026-07-24T08:20:00Z"); // 13:50 IST
  private static final Instant NIGHT_UTC = Instant.parse("2026-07-24T16:00:00Z"); // 21:30 IST
  private static final UUID ADMIN = UUID.fromString("a0000001-0000-4000-8000-000000000001");
  private static final String PHONE = "+919876543210";

  private FakeSmsTemplateStore templates;
  private FakeSmsDeliveryLogStore logs;
  private StubMsg91Client msg91;
  private StubTwilioClient twilio;
  private SmsSendService send;
  private SmsAdminService admin;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(DAYTIME_UTC, ZoneOffset.UTC);
    templates = new FakeSmsTemplateStore();
    logs = new FakeSmsDeliveryLogStore();
    msg91 = new StubMsg91Client();
    twilio = new StubTwilioClient();
    seedOtp();
    seedPromo();
    send =
        new SmsSendService(
            templates,
            logs,
            msg91,
            twilio,
            AllowAllPreferenceGate.INSTANCE,
            channel -> Optional.of("MSG91"),
            clock);
    admin = new SmsAdminService(templates, logs, clock);
  }

  @Test
  void ac001_promotionalNightBlocked() {
    Clock night = Clock.fixed(NIGHT_UTC, ZoneOffset.UTC);
    SmsSendService nightSend =
        new SmsSendService(
            templates,
            logs,
            msg91,
            twilio,
            AllowAllPreferenceGate.INSTANCE,
            channel -> Optional.of("MSG91"),
            night);
    assertThatThrownBy(
            () ->
                nightSend.send(
                    new SmsSendService.SendCommand(
                        PHONE, "PROMO_OFFER", Map.of("1", "10%"), "NORMAL")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PROMOTIONAL_TIME_RESTRICTED");
    assertThat(msg91.sendCallCount()).isZero();
  }

  @Test
  void ac002_nonE164Rejected() {
    assertThatThrownBy(
            () ->
                send.send(
                    new SmsSendService.SendCommand(
                        "9876543210", "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP")))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_PHONE_FORMAT");
  }

  @Test
  void ac003_msg91TimeoutFallsBackToTwilio() {
    msg91.setTimeout(true);
    Map<String, Object> result =
        send.send(
            new SmsSendService.SendCommand(
                PHONE, "OTP_VERIFICATION", Map.of("1", "482910", "2", "10"), "OTP"));
    assertThat(result.get("provider")).isEqualTo("TWILIO");
    assertThat(result.get("fallback_used")).isEqualTo(true);
    assertThat(logs.all()).hasSize(1);
    assertThat(logs.all().get(0).fallbackUsed()).isTrue();
    assertThat(logs.all().get(0).provider()).isEqualTo(SmsProvider.TWILIO);
    assertThat(twilio.sendCallCount()).isEqualTo(1);
  }

  @Test
  void ac004_dndPromoSkippedWithoutMsg91Send() {
    msg91.markDnd(PHONE);
    Map<String, Object> result =
        send.send(
            new SmsSendService.SendCommand(PHONE, "PROMO_OFFER", Map.of("1", "10%"), "NORMAL"));
    assertThat(result.get("status")).isEqualTo("SKIPPED_DND");
    assertThat(msg91.sendCallCount()).isZero();
    assertThat(msg91.dndCallCount()).isEqualTo(1);
    assertThat(logs.all().get(0).status()).isEqualTo(SmsLogStatus.SKIPPED_DND);
  }

  @Test
  void ac005_isActiveFilter() {
    templates.insert(
        new SmsTemplate(
            "INACTIVE_TPL",
            "x",
            SmsCategory.TRANSACTIONAL,
            "1007",
            "NMMATE",
            false,
            ADMIN,
            DAYTIME_UTC));
    Map<String, Object> activeOnly = admin.listTemplates(null, true);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) activeOnly.get("templates");
    assertThat(rows).isNotEmpty();
    assertThat(rows).allMatch(r -> Boolean.TRUE.equals(r.get("is_active")));
    assertThat(rows).noneMatch(r -> "INACTIVE_TPL".equals(r.get("template_id")));
  }

  @Test
  void ac006_duplicateTemplateId() {
    assertThatThrownBy(
            () ->
                admin.createTemplate(
                    ADMIN, "OTP_VERIFICATION", "dup", "OTP", "1007164875432101", "NMMATE"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TEMPLATE_ALREADY_EXISTS");
  }

  @Test
  void ac007_logsFilterByTemplateId() {
    send.send(
        new SmsSendService.SendCommand(
            PHONE, "OTP_VERIFICATION", Map.of("1", "1", "2", "10"), "OTP"));
    send.send(new SmsSendService.SendCommand(PHONE, "PROMO_OFFER", Map.of("1", "5%"), "NORMAL"));
    SmsAdminService.LogPage page =
        admin.listLogs(null, "OTP_VERIFICATION", null, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) page.data().get("logs");
    assertThat(rows).isNotEmpty();
    assertThat(rows).allMatch(r -> "OTP_VERIFICATION".equals(r.get("template_id")));
  }

  @Test
  void ac008_monthlyCostWithinFiveRupeesOfMsg91Rate() {
    for (int i = 0; i < 10; i++) {
      send.send(
          new SmsSendService.SendCommand(
              PHONE, "OTP_VERIFICATION", Map.of("1", String.valueOf(i), "2", "10"), "OTP"));
    }
    BigDecimal expected = SmsProvider.MSG91.costRs().multiply(BigDecimal.valueOf(10));
    BigDecimal actual =
        send.monthlyCost(
            Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(actual).isCloseTo(expected, within(new BigDecimal("5.00")));
    assertThat(actual).isEqualByComparingTo(expected);
  }

  private void seedOtp() {
    templates.insert(
        new SmsTemplate(
            "OTP_VERIFICATION",
            "Your Namma MedMate OTP is {{1}}. Valid for {{2}} minutes. - NMMATE",
            SmsCategory.OTP,
            "1007164875432101",
            "NMMATE",
            true,
            ADMIN,
            DAYTIME_UTC));
  }

  private void seedPromo() {
    templates.insert(
        new SmsTemplate(
            "PROMO_OFFER",
            "Save {{1}} today on medicines. - NMMATE",
            SmsCategory.PROMOTIONAL,
            "1007164875432199",
            "NMMATE",
            true,
            ADMIN,
            DAYTIME_UTC));
  }

  static final class FakeSmsTemplateStore implements SmsTemplateStore {
    private final ConcurrentHashMap<String, SmsTemplate> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<SmsTemplate> findById(String templateId) {
      return Optional.ofNullable(byId.get(templateId));
    }

    @Override
    public boolean exists(String templateId) {
      return byId.containsKey(templateId);
    }

    @Override
    public void insert(SmsTemplate template) {
      byId.put(template.templateId(), template);
    }

    @Override
    public List<SmsTemplate> list(SmsCategory category, Boolean active) {
      return byId.values().stream()
          .filter(t -> category == null || t.category() == category)
          .filter(t -> active == null || t.active() == active)
          .collect(Collectors.toCollection(ArrayList::new));
    }
  }

  static class FakeSmsDeliveryLogStore implements SmsDeliveryLogStore {
    private final List<SmsDeliveryLog> rows = new ArrayList<>();

    List<SmsDeliveryLog> all() {
      return List.copyOf(rows);
    }

    @Override
    public void insert(SmsDeliveryLog log) {
      rows.add(log);
    }

    @Override
    public Optional<SmsDeliveryLog> findById(UUID id) {
      return rows.stream().filter(l -> l.id().equals(id)).findFirst();
    }

    @Override
    public Optional<SmsDeliveryLog> findByProviderMessageId(String providerMessageId) {
      return rows.stream().filter(l -> providerMessageId.equals(l.providerMessageId())).findFirst();
    }

    @Override
    public boolean markDelivered(String providerMessageId, Instant deliveredAt) {
      for (int i = 0; i < rows.size(); i++) {
        SmsDeliveryLog log = rows.get(i);
        if (providerMessageId.equals(log.providerMessageId())) {
          rows.set(
              i,
              new SmsDeliveryLog(
                  log.id(),
                  log.toPhone(),
                  log.templateId(),
                  log.variables(),
                  log.provider(),
                  log.providerMessageId(),
                  log.fallbackUsed(),
                  SmsLogStatus.DELIVERED,
                  log.costRs(),
                  log.sentAt(),
                  deliveredAt,
                  log.errorMessage()));
          return true;
        }
      }
      return false;
    }

    @Override
    public Page list(ListFilter filter) {
      List<SmsDeliveryLog> filtered =
          rows.stream()
              .filter(l -> filter.toPhone() == null || filter.toPhone().equals(l.toPhone()))
              .filter(
                  l -> filter.templateId() == null || filter.templateId().equals(l.templateId()))
              .filter(l -> filter.status() == null || filter.status() == l.status())
              .filter(l -> filter.dateFrom() == null || !l.sentAt().isBefore(filter.dateFrom()))
              .filter(l -> filter.dateTo() == null || !l.sentAt().isAfter(filter.dateTo()))
              .collect(Collectors.toCollection(ArrayList::new));
      int from = Math.min((filter.page() - 1) * filter.limit(), filtered.size());
      int to = Math.min(from + filter.limit(), filtered.size());
      return new Page(filtered.subList(from, to), filtered.size());
    }

    @Override
    public BigDecimal sumCostBetween(Instant fromInclusive, Instant toExclusive) {
      return rows.stream()
          .filter(l -> l.costRs() != null)
          .filter(l -> l.status() == SmsLogStatus.SENT || l.status() == SmsLogStatus.DELIVERED)
          .filter(l -> !l.sentAt().isBefore(fromInclusive) && l.sentAt().isBefore(toExclusive))
          .map(SmsDeliveryLog::costRs)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }
}
