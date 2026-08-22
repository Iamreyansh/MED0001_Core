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
    if (!lease.tryAcquire(name, Duration.ofMinutes(10))) {
      return null;
    }
    return pjp.proceed();
  }
}
