package com.nammamedmate.crm.adapter.out.client;

import com.nammamedmate.crm.application.port.out.BusinessPerformancePort;
import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.domain.HealthMath;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Business performance from ERP POS invoice volume growth (marketplace GMV deferred). ponytail:
 * falls back to {@link HealthMath#DEFAULT_BUSINESS} when both periods are empty.
 */
@Component
public class ErpBusinessPerformanceAdapter implements BusinessPerformancePort {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final SaasModuleUsageStore usage;
  private final Clock clock;

  public ErpBusinessPerformanceAdapter(SaasModuleUsageStore usage, Clock clock) {
    this.usage = usage;
    this.clock = clock;
  }

  @Override
  public double scoreForAccount(UUID accountId, UUID pharmacyId) {
    if (pharmacyId == null) {
      return HealthMath.DEFAULT_BUSINESS;
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), IST);
    LocalDate thisMonth = today.withDayOfMonth(1);
    LocalDate priorMonth = thisMonth.minusMonths(1);
    Instant thisStart = thisMonth.atStartOfDay(IST).toInstant();
    Instant thisEnd = thisMonth.plusMonths(1).atStartOfDay(IST).toInstant();
    Instant priorStart = priorMonth.atStartOfDay(IST).toInstant();
    Instant priorEnd = thisStart;
    long current = usage.countInvoicesThisMonth(pharmacyId, thisStart, thisEnd);
    long prior = usage.countInvoicesThisMonth(pharmacyId, priorStart, priorEnd);
    return HealthMath.businessFromInvoiceGrowth(current, prior);
  }
}
