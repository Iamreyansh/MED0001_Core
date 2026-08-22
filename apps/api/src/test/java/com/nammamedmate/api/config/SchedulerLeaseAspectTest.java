package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
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
  }
}
