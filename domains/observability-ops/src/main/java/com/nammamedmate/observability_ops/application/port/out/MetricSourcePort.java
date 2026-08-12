package com.nammamedmate.observability_ops.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Live metric inputs for collection / alert evaluation (stubbed in V1). */
public interface MetricSourcePort {

  long gmvLastHourPaise();

  long gmvCurrentHourPaise();

  /** Same clock-hour average for this day-of-week over the previous 4 weeks (paise). */
  long gmvSameHourDowAvgPaise();

  double ordersPerMinute();

  BigDecimal dispatchSuccessRatePct();

  BigDecimal slaAdherencePctLastHour();

  BigDecimal paymentSuccessRatePct15m();

  int paymentAttempts15m();

  long payoutVolumeLastHourPaise();

  long payoutHourlyAvg7dPaise();

  List<ZoneRiderSnapshot> zoneRiders();

  int activeAutomations();

  int pendingApprovals();

  /** Synthetic compliance % for API P99 &lt; 500ms SLO. */
  BigDecimal apiP99CompliancePct30d();

  BigDecimal orderSlaPct30d();

  BigDecimal paymentSuccessPct30d();

  BigDecimal dispatchSuccessPct30d();

  record ZoneRiderSnapshot(UUID zoneId, String zoneName, int ridersOnline, int demandThreshold) {}
}
