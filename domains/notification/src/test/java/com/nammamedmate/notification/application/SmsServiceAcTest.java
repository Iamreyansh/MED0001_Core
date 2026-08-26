package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.notification.adapter.out.client.StubTwilioClient;
import com.nammamedmate.notification.application.port.out.PreferenceGatePort;
import com.nammamedmate.notification.application.port.out.SmsDeliveryLogStore;
import com.nammamedmate.notification.application.port.out.SmsTemplateStore;
import com.nammamedmate.notification.application.port.out.TwilioClientPort;
import com.nammamedmate.notification.domain.SmsCategory;
import com.nammamedmate.notification.domain.SmsDeliveryLog;
import com.nammamedmate.notification.domain.SmsLogStatus;
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

  private SmsTemplateStore templates;
  private SmsDeliveryLogStore logs;
  private TwilioClientPort twilio;
  private PreferenceGatePort preferences;
  private SmsSendService service;

  @BeforeEach
  void setUp() {
    templates = new FakeSmsTemplateStore();
    logs = new FakeSmsDeliveryLogStore();
    StubTwilioClient stub = new StubTwilioClient();
    twilio = stub;
    preferences = AllowAllPreferenceGate.INSTANCE;
    service =
        new SmsSendService(
            templates,
            logs,
            twilio,
            preferences,
            Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC));
    ((FakeSmsTemplateStore) templates)
        .insert(
            new SmsTemplate(
                "T1",
                "hi {{name}}",
                SmsCategory.TRANSACTIONAL,
                "DLT1",
                "NMMATE",
                true,
                UUID.randomUUID(),
                Instant.now()));
  }

  @Test
  void sendsViaTwilio() {
    Map<String, Object> data =
        service.send(
            new SmsSendService.SendCommand("+919876543210", "T1", Map.of("name", "A"), null));
    assertThat(data.get("provider")).isEqualTo("TWILIO");
    assertThat(data.get("fallback_used")).isEqualTo(false);
  }

  @Test
  void failsWhenTwilioDown() {
    ((StubTwilioClient) twilio).setFail(true);
    assertThatThrownBy(
            () ->
                service.send(new SmsSendService.SendCommand("+919876543210", "T1", Map.of(), null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("ALL_PROVIDERS_FAILED");
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
              .filter(
                  l ->
                      filter.dateFrom() == null
                          || (l.sentAt() != null && !l.sentAt().isBefore(filter.dateFrom())))
              .filter(
                  l ->
                      filter.dateTo() == null
                          || (l.sentAt() != null && !l.sentAt().isAfter(filter.dateTo())))
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
          .filter(l -> l.sentAt() != null)
          .filter(l -> !l.sentAt().isBefore(fromInclusive) && l.sentAt().isBefore(toExclusive))
          .map(SmsDeliveryLog::costRs)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }
}
