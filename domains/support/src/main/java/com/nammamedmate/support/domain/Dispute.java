package com.nammamedmate.support.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Dispute(
    UUID id,
    String disputeId,
    UUID orderId,
    UUID customerId,
    DisputeType disputeType,
    String description,
    List<String> evidenceUrls,
    DisputeStatus status,
    LiableParty liableParty,
    Long refundAmountPaise,
    RefundDestination refundTo,
    String resolutionNotes,
    String rejectionReason,
    UUID investigatedBy,
    Instant resolvedAt,
    Instant resolutionSlaAt,
    LiableParty recommendedLiableParty,
    boolean autoProcessed,
    String refundTxnId,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public Dispute {
    evidenceUrls = evidenceUrls == null ? List.of() : List.copyOf(evidenceUrls);
  }

  public boolean slaBreached(Instant now) {
    if (status == DisputeStatus.RESOLVED || status == DisputeStatus.CLOSED) {
      return false;
    }
    return now.isAfter(resolutionSlaAt);
  }

  public Dispute withInvestigating(UUID investigatorId, Instant updatedAt) {
    return copy(
        DisputeStatus.INVESTIGATING,
        liableParty,
        refundAmountPaise,
        refundTo,
        resolutionNotes,
        rejectionReason,
        investigatorId,
        resolvedAt,
        autoProcessed,
        refundTxnId,
        updatedAt);
  }

  public Dispute withApproved(
      LiableParty liable,
      long refundPaise,
      RefundDestination dest,
      String notes,
      boolean auto,
      String txnId,
      Instant resolvedAt,
      Instant updatedAt) {
    return copy(
        DisputeStatus.RESOLVED,
        liable,
        refundPaise,
        dest,
        notes,
        null,
        investigatedBy,
        resolvedAt,
        auto,
        txnId,
        updatedAt);
  }

  public Dispute withRejected(String reason, String notes, Instant resolvedAt, Instant updatedAt) {
    return copy(
        DisputeStatus.RESOLVED,
        LiableParty.CUSTOMER,
        0L,
        null,
        notes,
        reason,
        investigatedBy,
        resolvedAt,
        false,
        null,
        updatedAt);
  }

  private Dispute copy(
      DisputeStatus status,
      LiableParty liableParty,
      Long refundAmountPaise,
      RefundDestination refundTo,
      String resolutionNotes,
      String rejectionReason,
      UUID investigatedBy,
      Instant resolvedAt,
      boolean autoProcessed,
      String refundTxnId,
      Instant updatedAt) {
    return new Dispute(
        id,
        disputeId,
        orderId,
        customerId,
        disputeType,
        description,
        evidenceUrls,
        status,
        liableParty,
        refundAmountPaise,
        refundTo,
        resolutionNotes,
        rejectionReason,
        investigatedBy,
        resolvedAt,
        resolutionSlaAt,
        recommendedLiableParty,
        autoProcessed,
        refundTxnId,
        createdAt,
        updatedAt,
        deletedAt);
  }
}
