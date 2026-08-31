package com.nammamedmate.support.application.port.out;

import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketMessage;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketStore {

  record ListFilter(
      TicketStatus status,
      TicketPriority priority,
      TicketCategory category,
      TicketChannel channel,
      String q,
      UUID assignedAgentId,
      int offset,
      int limit) {}

  record Chips(
      long open,
      long inProgress,
      long slaBreached,
      long openDisputes,
      long refundExposureRs,
      double csatPct) {}

  int nextTicketSeq(LocalDate day);

  Ticket insert(Ticket ticket);

  void update(Ticket ticket);

  Optional<Ticket> findById(UUID id);

  Optional<Ticket> findByTicketId(String ticketId);

  List<Ticket> list(ListFilter filter);

  long count(ListFilter filter);

  List<Ticket> listForPharmacy(UUID pharmacyId, int offset, int limit);

  long countForPharmacy(UUID pharmacyId);

  Chips chips(Instant now);

  TicketMessage insertMessage(TicketMessage message);

  List<TicketMessage> listMessages(UUID ticketId);

  int countOpenAssigned(UUID agentId);

  /** OPEN tickets with no assignee (overflow queue). */
  int countUnassignedOpen();

  List<Ticket> listAssignedOpen(UUID agentId);

  /** Resolved/closed tickets for agent with resolved_at in [from, to). */
  List<Ticket> listResolvedByAgent(UUID agentId, Instant fromInclusive, Instant toExclusive);

  List<Ticket> findDueCsatSurveys(Instant now, int limit);

  List<Ticket> findSlaBreachedWithoutFirstResponse(Instant now, int limit);

  /** Open tickets eligible for SLA scan (excludes AWAITING_CUSTOMER / resolved / closed). */
  List<Ticket> findOpenForSlaScan(int limit);

  record ResolvedSlaStats(long withinSla, long totalResolved) {}

  ResolvedSlaStats resolvedSlaStats();
}
