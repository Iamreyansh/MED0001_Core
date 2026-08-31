package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.messaging.SchedulerLease;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;

class SchedulerLeaseAspectTest {

  @Test
  void skipsWhenLeaseNotAcquired() throws Throwable {
    SchedulerLease lease = mock(SchedulerLease.class);
    when(lease.tryAcquire(anyString(), any(Duration.class))).thenReturn(false);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    Signature sig = mock(Signature.class);
    when(pjp.getSignature()).thenReturn(sig);
    when(sig.toShortString()).thenReturn("Job.run()");
    assertThat(new SchedulerLeaseAspect(lease).aroundScheduled(pjp)).isNull();
  }

  @Test
  void proceedsWhenLeaseAcquired() throws Throwable {
    SchedulerLease lease = mock(SchedulerLease.class);
    when(lease.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    Signature sig = mock(Signature.class);
    when(pjp.getSignature()).thenReturn(sig);
    when(sig.toShortString()).thenReturn("Job.run()");
    when(pjp.proceed()).thenReturn("ok");
    assertThat(new SchedulerLeaseAspect(lease).aroundScheduled(pjp)).isEqualTo("ok");
    verify(pjp).proceed();
    verify(lease).release("Job.run()");
  }

  @Test
  void releasesLeaseWhenJobThrows() throws Throwable {
    SchedulerLease lease = mock(SchedulerLease.class);
    when(lease.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    Signature sig = mock(Signature.class);
    when(pjp.getSignature()).thenReturn(sig);
    when(sig.toShortString()).thenReturn("OutboxDispatchScheduler.dispatch()");
    when(pjp.proceed()).thenThrow(new IllegalStateException("boom"));
    assertThatThrownBy(() -> new SchedulerLeaseAspect(lease).aroundScheduled(pjp))
        .isInstanceOf(IllegalStateException.class);
    verify(lease).release("OutboxDispatchScheduler.dispatch()");
  }

  @Test
  void jobSpecificTtl() {
    assertThat(SchedulerLeaseAspect.ttlFor("OutboxDispatchScheduler.dispatch()"))
        .isEqualTo(Duration.ofMinutes(2));
    assertThat(SchedulerLeaseAspect.ttlFor("OutboxWorker.run()")).isEqualTo(Duration.ofMinutes(2));
    assertThat(SchedulerLeaseAspect.ttlFor("dispatch()")).isEqualTo(Duration.ofMinutes(2));
    assertThat(SchedulerLeaseAspect.ttlFor("CustomerMaintenanceScheduler.anonymise()"))
        .isEqualTo(Duration.ofMinutes(30));
    assertThat(SchedulerLeaseAspect.ttlFor("anonymise()")).isEqualTo(Duration.ofMinutes(30));
    assertThat(SchedulerLeaseAspect.ttlFor("MaintenanceJob")).isEqualTo(Duration.ofMinutes(30));
    assertThat(SchedulerLeaseAspect.ttlFor("Other.job()")).isEqualTo(Duration.ofMinutes(10));
    assertThat(SchedulerLeaseAspect.ttlFor(null)).isEqualTo(Duration.ofMinutes(10));
  }
}
