package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.MetricSourcePort;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Deterministic V1 stub. Mutable for tests via package setters; production defaults stay healthy.
 */
@Component
public class StubMetricSourceAdapter implements MetricSourcePort {

  private final AtomicLong gmvLastHour = new AtomicLong(612_000);
  private final AtomicLong gmvCurrentHour = new AtomicLong(612_000);
  private final AtomicLong gmvDowAvg = new AtomicLong(1_053_400);
  private final AtomicReference<Double> ordersPerMinute = new AtomicReference<>(2.8);
  private final AtomicReference<BigDecimal> dispatchRate =
      new AtomicReference<>(new BigDecimal("97.4"));
  private final AtomicReference<BigDecimal> slaLastHour =
      new AtomicReference<>(new BigDecimal("92.8"));
  private final AtomicReference<BigDecimal> payment15m =
      new AtomicReference<>(new BigDecimal("98.9"));
  private final AtomicInteger paymentAttempts = new AtomicInteger(40);
  private final AtomicLong payoutLastHour = new AtomicLong(50_000);
  private final AtomicLong payoutAvg7d = new AtomicLong(40_000);
  private final AtomicInteger activeAutomations = new AtomicInteger(8);
  private final AtomicInteger pendingApprovals = new AtomicInteger(3);
  private final AtomicReference<BigDecimal> apiP99 = new AtomicReference<>(new BigDecimal("99.2"));
  private final AtomicReference<BigDecimal> orderSla30d =
      new AtomicReference<>(new BigDecimal("93.2"));
  private final AtomicReference<BigDecimal> payment30d =
      new AtomicReference<>(new BigDecimal("99.4"));
  private final AtomicReference<BigDecimal> dispatch30d =
      new AtomicReference<>(new BigDecimal("97.8"));
  private final CopyOnWriteArrayList<ZoneRiderSnapshot> zones = new CopyOnWriteArrayList<>();

  public StubMetricSourceAdapter() {
    zones.add(
        new ZoneRiderSnapshot(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Indiranagar", 5, 3));
    zones.add(
        new ZoneRiderSnapshot(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "Whitefield", 0, 4));
  }

  @Override
  public long gmvLastHourPaise() {
    return gmvLastHour.get();
  }

  @Override
  public long gmvCurrentHourPaise() {
    return gmvCurrentHour.get();
  }

  @Override
  public long gmvSameHourDowAvgPaise() {
    return gmvDowAvg.get();
  }

  @Override
  public double ordersPerMinute() {
    return ordersPerMinute.get();
  }

  @Override
  public BigDecimal dispatchSuccessRatePct() {
    return dispatchRate.get();
  }

  @Override
  public BigDecimal slaAdherencePctLastHour() {
    return slaLastHour.get();
  }

  @Override
  public BigDecimal paymentSuccessRatePct15m() {
    return payment15m.get();
  }

  @Override
  public int paymentAttempts15m() {
    return paymentAttempts.get();
  }

  @Override
  public long payoutVolumeLastHourPaise() {
    return payoutLastHour.get();
  }

  @Override
  public long payoutHourlyAvg7dPaise() {
    return payoutAvg7d.get();
  }

  @Override
  public List<ZoneRiderSnapshot> zoneRiders() {
    return List.copyOf(zones);
  }

  @Override
  public int activeAutomations() {
    return activeAutomations.get();
  }

  @Override
  public int pendingApprovals() {
    return pendingApprovals.get();
  }

  @Override
  public BigDecimal apiP99CompliancePct30d() {
    return apiP99.get();
  }

  @Override
  public BigDecimal orderSlaPct30d() {
    return orderSla30d.get();
  }

  @Override
  public BigDecimal paymentSuccessPct30d() {
    return payment30d.get();
  }

  @Override
  public BigDecimal dispatchSuccessPct30d() {
    return dispatch30d.get();
  }

  public void setGmvCurrentHourPaise(long v) {
    gmvCurrentHour.set(v);
    gmvLastHour.set(v);
  }

  public void setGmvSameHourDowAvgPaise(long v) {
    gmvDowAvg.set(v);
  }

  public void setPaymentSuccessRatePct15m(BigDecimal v) {
    payment15m.set(v);
  }

  public void setPaymentAttempts15m(int v) {
    paymentAttempts.set(v);
  }

  public void setPayoutVolumeLastHourPaise(long v) {
    payoutLastHour.set(v);
  }

  public void setPayoutHourlyAvg7dPaise(long v) {
    payoutAvg7d.set(v);
  }

  public void setSlaAdherencePctLastHour(BigDecimal v) {
    slaLastHour.set(v);
  }

  public void setOrderSlaPct30d(BigDecimal v) {
    orderSla30d.set(v);
  }

  public void setZones(List<ZoneRiderSnapshot> next) {
    zones.clear();
    zones.addAll(next);
  }

  public void setDispatchSuccessRatePct(BigDecimal v) {
    dispatchRate.set(v);
  }

  public void setApiP99CompliancePct30d(BigDecimal v) {
    apiP99.set(v);
  }

  public void setPaymentSuccessPct30d(BigDecimal v) {
    payment30d.set(v);
  }

  public void setDispatchSuccessPct30d(BigDecimal v) {
    dispatch30d.set(v);
  }
}
