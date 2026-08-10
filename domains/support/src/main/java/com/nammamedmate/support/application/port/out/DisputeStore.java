package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.Dispute;
import com.nammamedmate.support.domain.DisputeEvent;
import com.nammamedmate.support.domain.DisputeStatus;
import com.nammamedmate.support.domain.DisputeType;
import com.nammamedmate.support.domain.LiableParty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeStore {

  record ListFilter(
      DisputeStatus status,
      LiableParty liableParty,
      DisputeType disputeType,
      int offset,
      int limit) {}

  record Chips(
      long openDisputes, long refundExposureRs, double avgResolutionHours, long resolvedToday) {}

  int nextDisputeSeq(LocalDate day);

  Dispute insert(Dispute dispute);

  void update(Dispute dispute);

  Optional<Dispute> findById(UUID id);

  Optional<Dispute> findByOrderId(UUID orderId);

  List<Dispute> list(ListFilter filter);

  long count(ListFilter filter);

  List<Dispute> listForCustomer(UUID customerId, int offset, int limit);

  long countForCustomer(UUID customerId);

  Chips chips(Instant now);

  DisputeEvent insertEvent(DisputeEvent event);

  List<DisputeEvent> listEvents(UUID disputeId);

  List<Dispute> findSlaBreachedOpen(Instant now, int limit);

  /** Open or resolved (not closed/deleted) dispute for order banner. */
  Optional<Dispute> findBannerDispute(UUID orderId);
}
