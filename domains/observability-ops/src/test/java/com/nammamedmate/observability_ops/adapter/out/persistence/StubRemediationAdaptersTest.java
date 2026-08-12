package com.nammamedmate.observability_ops.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.observability_ops.application.port.out.ApiErrorRatePort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class StubRemediationAdaptersTest {

  @Test
  void riderNotifyAndAudit() {
    OutboxPublisher publisher = mock(OutboxPublisher.class);
    ObjectProvider<OutboxPublisher> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(publisher);
    StubRiderNotifyAdapter riders = new StubRiderNotifyAdapter(provider);
    UUID zone = UUID.fromString("11111111-1111-4111-8111-111111111111");
    assertThat(riders.zoneExists(zone)).isTrue();
    assertThat(riders.zoneName(zone)).isEqualTo("Whitefield");
    assertThat(riders.zoneName(UUID.randomUUID())).isNotBlank();
    assertThat(riders.notifyOfflineRiders(zone, 3, 2).ridersNotified()).isEqualTo(8);
    assertThat(riders.notifyOfflineRiders(UUID.randomUUID(), 1, 1).ridersNotified()).isZero();
    verify(publisher).publish(any());
    riders.clearNotified();
    assertThat(riders.notified()).isEmpty();

    InMemoryPlaybookAuditAdapter audit = new InMemoryPlaybookAuditAdapter();
    audit.record(UUID.randomUUID(), UUID.randomUUID(), null, null);
    audit.record(UUID.randomUUID(), UUID.randomUUID(), Map.of("a", 1), Map.of("a", 2));
    assertThat(audit.entries()).hasSize(2);
    audit.clear();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  void pharmacyPaymentApiStubs() {
    StubPharmacyThrottleAdapter ph = new StubPharmacyThrottleAdapter();
    UUID id = UUID.fromString("22222222-2222-4222-8222-222222222222");
    assertThat(ph.pharmacyExists(id)).isTrue();
    assertThat(ph.pharmacyName(id)).contains("Medplus");
    assertThat(ph.throttleByPercent(id, 30)).isPresent();
    assertThat(ph.recoverCap(id)).isPresent();
    assertThat(ph.throttleByPercent(UUID.randomUUID(), 30)).isEmpty();
    assertThat(ph.pharmacyName(UUID.randomUUID())).isNotBlank();
    assertThat(ph.recoverCap(UUID.randomUUID())).isEmpty();
    assertThat(ph.recoverCap(id)).isEmpty();
    ph.put(id, "Medplus - HSR Layout", new BigDecimal("60"), 1, 0, 20, false);
    assertThat(ph.candidatesForThrottle(new BigDecimal("70"), 3)).isEmpty();
    ph.put(id, "Medplus - HSR Layout", new BigDecimal("60"), 3, 0, 20, false);
    assertThat(ph.candidatesForThrottle(new BigDecimal("70"), 3)).hasSize(1);
    ph.put(id, "Medplus - HSR Layout", new BigDecimal("90"), 3, 0, 20, false);
    assertThat(ph.candidatesForThrottle(new BigDecimal("70"), 3)).isEmpty();
    ph.put(id, "Medplus - HSR Layout", new BigDecimal("85"), 0, 1, 14, true);
    assertThat(ph.candidatesForRecovery(new BigDecimal("80"), 2)).isEmpty();
    ph.put(id, "Medplus - HSR Layout", new BigDecimal("85"), 0, 2, 14, true);
    assertThat(ph.candidatesForRecovery(new BigDecimal("80"), 2)).hasSize(1);
    ph.put(id, "Medplus - HSR Layout", new BigDecimal("70"), 0, 2, 14, true);
    assertThat(ph.candidatesForRecovery(new BigDecimal("80"), 2)).isEmpty();
    ph.setForceThrottleMiss(true);
    assertThat(ph.throttleByPercent(id, 30)).isEmpty();
    ph.setForceThrottleMiss(false);

    StubPaymentJobRetryAdapter pay = new StubPaymentJobRetryAdapter();
    UUID job = UUID.randomUUID();
    Instant t0 = Instant.parse("2026-07-24T10:00:00Z");
    assertThat(pay.retry(job)).isFalse();
    assertThat(pay.failedRetryCount(job)).isEqualTo(-1);
    pay.putFailed(job, t0, 0);
    assertThat(pay.jobsReadyForRetry(t0.minusSeconds(1), 5, 3)).isEmpty();
    assertThat(pay.jobExists(job)).isTrue();
    assertThat(pay.jobsReadyForRetry(t0, 5, 3)).isEmpty();
    pay.putFailed(job, t0.minusSeconds(600), 0);
    assertThat(pay.jobsReadyForRetry(t0, 5, 3)).hasSize(1);
    assertThat(pay.retry(job)).isFalse();
    assertThat(pay.failedRetryCount(job)).isEqualTo(1);
    pay.markExhausted(job);
    assertThat(pay.retry(job)).isFalse();
    assertThat(pay.jobsReadyForRetry(t0, 5, 3)).isEmpty();
    pay.markExhausted(UUID.randomUUID());
    pay.putFailed(job, t0.minusSeconds(600), 0);
    pay.setNextRetrySucceeds(true);
    assertThat(pay.retry(job)).isTrue();

    StubApiErrorRateAdapter api = new StubApiErrorRateAdapter();
    api.setHot(
        List.of(
            new ApiErrorRatePort.HotEndpoint("/x", new BigDecimal("9"), UUID.randomUUID()),
            new ApiErrorRatePort.HotEndpoint("/y", new BigDecimal("1"), UUID.randomUUID())));
    assertThat(api.endpointsAbove(new BigDecimal("5"), 5)).hasSize(1);
    api.clear();
    assertThat(api.endpointsAbove(new BigDecimal("5"), 5)).isEmpty();
  }
}
