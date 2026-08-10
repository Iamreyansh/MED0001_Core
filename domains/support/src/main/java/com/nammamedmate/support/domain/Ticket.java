package com.nammamedmate.support.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record Ticket(
    UUID id,
    String ticketId,
    UUID customerId,
    UUID pharmacyId,
    UUID orderId,
    TicketCategory category,
    String subject,
    TicketStatus status,
    TicketPriority priority,
    SlaLevel slaLevel,
    Instant slaDueAt,
    Instant firstResponseDueAt,
    Instant resolutionDueAt,
    UUID assignedAgentId,
    TicketChannel channel,
    Instant firstResponseAt,
    Instant resolvedAt,
    String resolutionSummary,
    Integer csatScore,
    String csatFeedback,
    Instant csatSurveyScheduledAt,
    Instant csatSurveySentAt,
    UUID createdByAdminId,
    Instant slaPausedAt,
    Instant slaL4NotifiedAt,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt) {

  public Ticket {
    if (firstResponseDueAt == null) {
      firstResponseDueAt = slaDueAt;
    }
    if (resolutionDueAt == null) {
      resolutionDueAt = slaDueAt;
    }
  }

  public boolean slaBreached(Instant now) {
    return firstResponseBreached(now);
  }

  public boolean firstResponseBreached(Instant now) {
    if (firstResponseAt != null) {
      return false;
    }
    if (isSlaPausedOrClosed()) {
      return false;
    }
    return now.isAfter(firstResponseDueAt);
  }

  public boolean resolutionBreached(Instant now) {
    if (resolvedAt != null) {
      return false;
    }
    if (isSlaPausedOrClosed()) {
      return false;
    }
    return now.isAfter(resolutionDueAt);
  }

  public long minutesBreachedFirstResponse(Instant now) {
    if (!firstResponseBreached(now)) {
      return 0L;
    }
    return Math.max(0L, Duration.between(firstResponseDueAt, now).toMinutes());
  }

  public long minutesBreachedResolution(Instant now) {
    if (!resolutionBreached(now)) {
      return 0L;
    }
    return Math.max(0L, Duration.between(resolutionDueAt, now).toMinutes());
  }

  private boolean isSlaPausedOrClosed() {
    return status == TicketStatus.AWAITING_CUSTOMER
        || status == TicketStatus.RESOLVED
        || status == TicketStatus.CLOSED
        || slaPausedAt != null;
  }

  public Ticket withStatus(TicketStatus s, Instant updatedAt) {
    return copy(
        s,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        slaPausedAt,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withAssignment(UUID agentId, Instant updatedAt) {
    return copy(
        status == TicketStatus.OPEN ? TicketStatus.IN_PROGRESS : status,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        agentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        slaPausedAt,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withPriority(TicketPriority p, SlaLevel level, Instant due, Instant updatedAt) {
    return copy(
        status,
        p,
        level,
        due,
        due,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        slaPausedAt,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withSla(SlaLevel level, Instant due, Instant updatedAt) {
    return copy(
        status,
        priority,
        level,
        due,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        slaPausedAt,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withSlaLevel(SlaLevel level, Instant updatedAt) {
    return copy(
        status,
        priority,
        level,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        slaPausedAt,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withFirstResponse(Instant at, TicketStatus s, Instant updatedAt) {
    Instant paused =
        s == TicketStatus.AWAITING_CUSTOMER
            ? (slaPausedAt == null ? updatedAt : slaPausedAt)
            : null;
    return copy(
        s,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        at,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        paused,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withResolved(
      Instant resolvedAt, String summary, Instant csatAt, Instant updatedAt) {
    return copy(
        TicketStatus.RESOLVED,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        summary,
        csatAt,
        csatSurveySentAt,
        null,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withCsatSent(Instant sentAt) {
    return copy(
        status,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        sentAt,
        slaPausedAt,
        slaL4NotifiedAt,
        sentAt);
  }

  public Ticket withReopened(Instant due, Instant updatedAt) {
    return copy(
        TicketStatus.IN_PROGRESS,
        priority,
        slaLevel,
        due,
        due,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        null,
        null,
        null,
        null,
        null,
        null,
        updatedAt);
  }

  public Ticket withSlaPause(Instant pausedAt, Instant updatedAt) {
    return copy(
        status,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        pausedAt,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withSlaResume(Duration paused, Instant updatedAt) {
    Instant frDue = firstResponseDueAt.plus(paused);
    Instant resDue = resolutionDueAt.plus(paused);
    Instant slaDue = slaDueAt.plus(paused);
    return copy(
        TicketStatus.IN_PROGRESS,
        priority,
        slaLevel,
        slaDue,
        frDue,
        resDue,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        null,
        slaL4NotifiedAt,
        updatedAt);
  }

  public Ticket withL4Notified(Instant at) {
    return copy(
        status,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        slaPausedAt,
        at,
        at);
  }

  private Ticket copy(
      TicketStatus status,
      TicketPriority priority,
      SlaLevel slaLevel,
      Instant slaDueAt,
      Instant firstResponseDueAt,
      Instant resolutionDueAt,
      UUID assignedAgentId,
      Instant firstResponseAt,
      Instant resolvedAt,
      String resolutionSummary,
      Instant csatSurveyScheduledAt,
      Instant csatSurveySentAt,
      Instant slaPausedAt,
      Instant slaL4NotifiedAt,
      Instant updatedAt) {
    return new Ticket(
        id,
        ticketId,
        customerId,
        pharmacyId,
        orderId,
        category,
        subject,
        status,
        priority,
        slaLevel,
        slaDueAt,
        firstResponseDueAt,
        resolutionDueAt,
        assignedAgentId,
        channel,
        firstResponseAt,
        resolvedAt,
        resolutionSummary,
        csatScore,
        csatFeedback,
        csatSurveyScheduledAt,
        csatSurveySentAt,
        createdByAdminId,
        slaPausedAt,
        slaL4NotifiedAt,
        deletedAt,
        createdAt,
        updatedAt);
  }
}
