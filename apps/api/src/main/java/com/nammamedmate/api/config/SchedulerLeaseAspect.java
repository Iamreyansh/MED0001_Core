package com.nammamedmate.api.config;

import com.nammamedmate.messaging.SchedulerLease;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Ensures only one API instance runs a given {@code @Scheduled} method at a time. */
@Aspect
@Component
@ConditionalOnBean(SchedulerLease.class)
public class SchedulerLeaseAspect {

  private final SchedulerLease lease;

  public SchedulerLeaseAspect(SchedulerLease lease) {
    this.lease = lease;
  }

  @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
  public Object aroundScheduled(ProceedingJoinPoint pjp) throws Throwable {
    String name = pjp.getSignature().toShortString();
    if (!lease.tryAcquire(name, ttlFor(name))) {
      return null;
    }
    try {
      return pjp.proceed();
    } finally {
      lease.release(name);
    }
  }

  /** API is the sole scheduler owner; worker has no @Scheduled jobs. */
  static Duration ttlFor(String jobName) {
    String name = jobName == null ? "" : jobName;
    if (name.contains("Outbox") || name.contains("dispatch")) {
      return Duration.ofMinutes(2);
    }
    if (name.contains("anonymise") || name.contains("Maintenance")) {
      return Duration.ofMinutes(30);
    }
    return Duration.ofMinutes(10);
  }
}
