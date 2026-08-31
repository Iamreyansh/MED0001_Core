package com.nammamedmate.support.application;

import com.nammamedmate.kernel.api.PageRequest;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.port.out.AgentStore;
import com.nammamedmate.support.application.port.out.CannedResponseStore;
import com.nammamedmate.support.application.port.out.CustomerLookupPort;
import com.nammamedmate.support.application.port.out.CustomerLookupPort.CustomerContext;
import com.nammamedmate.support.application.port.out.NotificationDispatchPort;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.application.port.out.SlaPolicyStore;
import com.nammamedmate.support.application.port.out.TicketStore;
import com.nammamedmate.support.application.port.out.TicketStore.Chips;
import com.nammamedmate.support.application.port.out.TicketStore.ListFilter;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.SenderType;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.SlaPolicy;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketIds;
import com.nammamedmate.support.domain.TicketMessage;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

  private static final Set<AuthRole> ADMIN_ROLES =
      EnumSet.of(AuthRole.ADMIN_SUPER, AuthRole.ADMIN_OPERATIONS, AuthRole.ADMIN_SUPPORT);
  private static final Set<AuthRole> CREATE_ROLES =
      EnumSet.of(
          AuthRole.CUSTOMER,
          AuthRole.PHARMACY_OWNER,
          AuthRole.PHARMACY_STAFF,
          AuthRole.ADMIN_SUPER,
          AuthRole.ADMIN_OPERATIONS,
          AuthRole.ADMIN_SUPPORT);

  private final TicketStore tickets;
  private final AgentStore agents;
  private final CustomerLookupPort customers;
  private final NotificationDispatchPort notifications;
  private final SlaPolicyStore slaPolicies;
  private final CannedResponseStore cannedResponses;
  private final OrderContextPort orders;
  private final Clock clock;
  private final AgentService agentService;

  public TicketService(
      TicketStore tickets,
      AgentStore agents,
      CustomerLookupPort customers,
      NotificationDispatchPort notifications,
      SlaPolicyStore slaPolicies,
      CannedResponseStore cannedResponses,
      OrderContextPort orders,
      Clock clock,
      AgentService agentService) {
    this.tickets = tickets;
    this.agents = agents;
    this.customers = customers;
    this.notifications = notifications;
    this.slaPolicies = slaPolicies;
    this.cannedResponses = cannedResponses;
    this.orders = orders;
    this.clock = clock;
    this.agentService = agentService;
  }

  public record ListResult(Map<String, Object> data, PaginationMeta meta) {
    public ListResult {
      data = Map.copyOf(data);
    }
  }

  public record CreateCommand(
      String category,
      String subject,
      String description,
      String channel,
      UUID orderId,
      UUID pharmacyId,
      List<String> attachments,
      String priority) {
    public CreateCommand {
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
  }

  public record AdminCreateCommand(
      UUID customerId,
      String category,
      String subject,
      String description,
      UUID orderId,
      String channel) {}

  public record ReplyCommand(
      String message, Boolean internalNote, List<String> attachments, UUID cannedResponseId) {
    public ReplyCommand {
      attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
  }

  @Transactional(readOnly = true)
  public ListResult listAdmin(
      MedmatePrincipal principal,
      String status,
      String priority,
      String category,
      String channel,
      String q,
      UUID assignedAgentId,
      Integer page,
      Integer limit,
      Boolean export) {
    requireAdmin(principal);
    Instant now = clock.instant();
    ListFilter filter =
        new ListFilter(
            parseStatus(status),
            parsePriority(priority),
            parseCategory(category),
            parseChannel(channel),
            blankToNull(q),
            assignedAgentId,
            0,
            100_000);
    if (Boolean.TRUE.equals(export)) {
      List<Ticket> rows = tickets.list(filter);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("csv", buildCsv(rows, now));
      data.put("record_count", rows.size());
      return new ListResult(data, null);
    }
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    ListFilter paged =
        new ListFilter(
            filter.status(),
            filter.priority(),
            filter.category(),
            filter.channel(),
            filter.q(),
            filter.assignedAgentId(),
            pr.offset(),
            pr.limit());
    long total = tickets.count(paged);
    Chips chips = tickets.chips(now);
    List<Map<String, Object>> items = new ArrayList<>();
    for (Ticket t : tickets.list(paged)) {
      items.add(toListItem(t, now));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("chips", toChipsMap(chips));
    data.put("tickets", items);
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  @Transactional(readOnly = true)
  public ListResult listPharmacy(MedmatePrincipal principal, Integer page, Integer limit) {
    requirePrincipal(principal);
    if (principal.role() != AuthRole.PHARMACY_OWNER
        && principal.role() != AuthRole.PHARMACY_STAFF) {
      throw new AppException("FORBIDDEN", "Pharmacy role required", 403);
    }
    if (principal.pharmacyId() == null) {
      throw new AppException("FORBIDDEN", "Pharmacy context required", 403);
    }
    PageRequest pr = PageRequest.normalize(page, limit, null, "desc");
    Instant now = clock.instant();
    long total = tickets.countForPharmacy(principal.pharmacyId());
    List<Map<String, Object>> items = new ArrayList<>();
    for (Ticket t : tickets.listForPharmacy(principal.pharmacyId(), pr.offset(), pr.limit())) {
      items.add(toListItem(t, now));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("tickets", items);
    return new ListResult(data, PaginationMeta.of(pr.page(), pr.limit(), total));
  }

  @Transactional
  public Map<String, Object> create(MedmatePrincipal principal, CreateCommand cmd) {
    requireCreateRole(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "request body is required", 400);
    }
    Instant now = clock.instant();
    TicketCategory cat = requireCategory(cmd.category());
    String subject = requireSubject(cmd.subject());
    String description = requireDescription(cmd.description());
    TicketChannel channel = parseChannelOrDefault(cmd.channel());
    TicketPriority priority = resolvePriority(cmd.priority(), cat);
    SlaPolicy policy = resolvePolicy(cat, priority);
    UUID customerId = resolveCustomerIdForCreate(principal);
    UUID pharmacyId = cmd.pharmacyId() != null ? cmd.pharmacyId() : principal.pharmacyId();
    Ticket ticket =
        createTicket(
            customerId,
            pharmacyId,
            cmd.orderId(),
            cat,
            subject,
            priority,
            policy,
            channel,
            null,
            now);
    insertInitialMessage(ticket, principal, description, cmd.attachments());
    return createdPayload(ticket);
  }

  @Transactional
  public Map<String, Object> createOnBehalf(MedmatePrincipal principal, AdminCreateCommand cmd) {
    requireAdmin(principal);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id is required", 400);
    }
    if (cmd.customerId() == null) {
      throw new AppException("VALIDATION_ERROR", "customer_id is required", 400);
    }
    Instant now = clock.instant();
    TicketCategory cat = requireCategory(cmd.category());
    String subject = requireSubject(cmd.subject());
    String description = requireDescription(cmd.description());
    TicketChannel channel = parseChannelOrDefault(cmd.channel());
    TicketPriority priority = resolvePriority(null, cat);
    SlaPolicy policy = resolvePolicy(cat, priority);
    Ticket ticket =
        createTicket(
            cmd.customerId(),
            null,
            cmd.orderId(),
            cat,
            subject,
            priority,
            policy,
            channel,
            principal.subject(),
            now);
    String adminName =
        agents.findById(principal.subject()).map(AgentProfile::displayName).orElse("Support Agent");
    tickets.insertMessage(
        new TicketMessage(
            Ids.newId(),
            ticket.id(),
            SenderType.AGENT,
            principal.subject(),
            adminName,
            description,
            false,
            null,
            List.of(),
            now));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("created_for_customer_id", ticket.customerId());
    data.put("created_by_admin_id", principal.subject());
    data.put("status", ticket.status().name());
    return data;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(MedmatePrincipal principal, UUID id) {
    requirePrincipal(principal);
    Ticket ticket = requireTicket(id);
    assertCanView(principal, ticket);
    Instant now = clock.instant();
    boolean admin = isAdmin(principal);
    List<TicketMessage> messages = tickets.listMessages(ticket.id());
    List<Map<String, Object>> conversation = new ArrayList<>();
    for (TicketMessage m : messages) {
      if (!admin && m.internalNote()) {
        continue;
      }
      conversation.add(toMessageMap(m));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", ticket.id());
    data.put("ticket_id", ticket.ticketId());
    data.put("subject", ticket.subject());
    data.put("status", ticket.status().name());
    data.put("priority", ticket.priority().name());
    data.put("sla_level", ticket.slaLevel().name());
    data.put("sla_due_at", ticket.slaDueAt());
    data.put("sla_breached", ticket.slaBreached(now));
    CustomerContext ctx =
        customers
            .find(ticket.customerId())
            .orElse(new CustomerContext(ticket.customerId(), "Customer", 0, 0));
    Map<String, Object> customerContext = new LinkedHashMap<>();
    customerContext.put("customer_id", ctx.customerId());
    customerContext.put("customer_name", ctx.customerName());
    customerContext.put("total_orders", ctx.totalOrders());
    customerContext.put("ltv_rs", ctx.ltvRs());
    data.put("customer_context", customerContext);
    data.put("order_id", ticket.orderId());
    data.put("conversation", conversation);
    data.put("assigned_agent_id", ticket.assignedAgentId());
    data.put(
        "assigned_agent_name",
        ticket.assignedAgentId() == null
            ? null
            : agents
                .findById(ticket.assignedAgentId())
                .map(AgentProfile::displayName)
                .orElse(null));
    data.put("first_response_at", ticket.firstResponseAt());
    data.put("created_at", ticket.createdAt());
    return data;
  }

  @Transactional
  public Map<String, Object> reply(MedmatePrincipal principal, UUID id, ReplyCommand cmd) {
    requirePrincipal(principal);
    Ticket ticket = requireTicket(id);
    assertCanReply(principal, ticket);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "message is required", 400);
    }
    Instant now = clock.instant();
    String body = resolveReplyBody(ticket, cmd, now);
    boolean admin = isAdmin(principal);
    boolean internal = admin && Boolean.TRUE.equals(cmd.internalNote());
    SenderType senderType = resolveSenderType(principal);
    String senderName = resolveSenderName(principal);
    TicketMessage msg =
        new TicketMessage(
            Ids.newId(),
            ticket.id(),
            senderType,
            principal.subject(),
            senderName,
            body,
            internal,
            cmd.cannedResponseId(),
            cmd.attachments(),
            now);
    tickets.insertMessage(msg);

    Ticket updated = ticket;
    if (admin && !internal) {
      Instant first = ticket.firstResponseAt() == null ? now : ticket.firstResponseAt();
      final TicketStatus status = ticket.status();
      final TicketStatus next =
          switch (status) {
            case OPEN, IN_PROGRESS -> TicketStatus.AWAITING_CUSTOMER;
            default -> status;
          };
      if (ticket.firstResponseAt() == null) {
        updated = ticket.withFirstResponse(first, next, now);
      } else if (next != ticket.status()) {
        updated = ticket.withStatus(next, now).withSlaPause(now, now);
      }
    } else if (!admin) {
      if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.CLOSED) {
        SlaPolicy policy = resolvePolicy(ticket.category(), ticket.priority());
        Instant due = now.plusSeconds(policy.firstResponseSlaMinutes() * 60L);
        updated = ticket.withReopened(due, now);
      } else if (ticket.status() == TicketStatus.AWAITING_CUSTOMER) {
        java.time.Duration paused =
            ticket.slaPausedAt() == null
                ? java.time.Duration.ZERO
                : java.time.Duration.between(ticket.slaPausedAt(), now);
        updated = ticket.withSlaResume(paused, now);
      } else if (ticket.status() == TicketStatus.OPEN) {
        updated = ticket.withStatus(TicketStatus.IN_PROGRESS, now);
      }
    }
    if (updated != ticket) {
      tickets.update(updated);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message_id", msg.id());
    data.put("ticket_id", ticket.ticketId());
    data.put("sender", senderType.name().toLowerCase(Locale.ROOT));
    data.put("created_at", msg.createdAt());
    return data;
  }

  @Transactional
  public Map<String, Object> assign(MedmatePrincipal principal, UUID id, UUID agentId) {
    requireAdmin(principal);
    if (agentId == null) {
      throw new AppException("VALIDATION_ERROR", "agent_id is required", 400);
    }
    Ticket ticket = requireTicket(id);
    AgentProfile agent =
        agents
            .findById(agentId)
            .orElseThrow(() -> new AppException("AGENT_NOT_FOUND", "Agent ID does not exist", 404));
    int open = tickets.countOpenAssigned(agentId);
    int cap = agent.maxLoad();
    if (open >= cap) {
      UUID current = ticket.assignedAgentId();
      if (current == null) {
        throw new AppException("AGENT_AT_CAPACITY", "Agent has " + cap + " open tickets", 400);
      }
      if (!current.equals(agentId)) {
        throw new AppException("AGENT_AT_CAPACITY", "Agent has " + cap + " open tickets", 400);
      }
    }
    Instant now = clock.instant();
    Ticket updated = ticket.withAssignment(agentId, now);
    tickets.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("assigned_to", agentId);
    data.put("assigned_to_name", agent.displayName());
    data.put("assigned_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> resolve(MedmatePrincipal principal, UUID id, String summary) {
    requireAdmin(principal);
    if (summary == null) {
      throw new AppException("VALIDATION_ERROR", "resolution_summary is required", 400);
    }
    if (summary.isBlank()) {
      throw new AppException("VALIDATION_ERROR", "resolution_summary is required", 400);
    }
    Ticket ticket = requireTicket(id);
    Instant now = clock.instant();
    Instant csatAt = now.plusSeconds(24 * 3600);
    Ticket updated = ticket.withResolved(now, summary.trim(), csatAt, now);
    tickets.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("status", TicketStatus.RESOLVED.name());
    data.put("resolved_at", now);
    data.put("csat_survey_scheduled_at", csatAt);
    return data;
  }

  @Transactional
  public Map<String, Object> submitCsat(
      MedmatePrincipal principal, UUID id, Integer score, String feedback) {
    requirePrincipal(principal);
    Ticket ticket = requireTicket(id);
    if (!isAdmin(principal) && !ticket.customerId().equals(principal.subject())) {
      throw new AppException("FORBIDDEN", "Cannot submit CSAT for this ticket", 403);
    }
    if (ticket.status() != TicketStatus.RESOLVED && ticket.status() != TicketStatus.CLOSED) {
      throw new AppException(
          "VALIDATION_ERROR", "CSAT can only be submitted after resolution", 400);
    }
    if (ticket.csatScore() != null) {
      throw new AppException(
          "CSAT_ALREADY_SUBMITTED", "CSAT already submitted for this ticket", 409);
    }
    if (score == null || score < 1 || score > 5) {
      throw new AppException("VALIDATION_ERROR", "score must be between 1 and 5", 400);
    }
    Instant now = clock.instant();
    Ticket updated = ticket.withCsat(score, feedback == null ? null : feedback.trim(), now);
    tickets.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("csat_score", score);
    data.put("csat_feedback", updated.csatFeedback());
    return data;
  }

  @Transactional
  public Map<String, Object> reopen(MedmatePrincipal principal, UUID id, String reason) {
    requirePrincipal(principal);
    Ticket ticket = requireTicket(id);
    if (!isAdmin(principal) && !ticket.customerId().equals(principal.subject())) {
      throw new AppException("FORBIDDEN", "Cannot reopen this ticket", 403);
    }
    if (ticket.status() != TicketStatus.RESOLVED) {
      if (ticket.status() != TicketStatus.CLOSED) {
        throw new AppException(
            "VALIDATION_ERROR", "Only resolved/closed tickets can be reopened", 400);
      }
    }
    Instant now = clock.instant();
    SlaPolicy policy = resolvePolicy(ticket.category(), ticket.priority());
    Instant due = now.plusSeconds(policy.firstResponseSlaMinutes() * 60L);
    Ticket updated = ticket.withReopened(due, now);
    tickets.update(updated);
    if (reason != null) {
      if (!reason.isBlank()) {
        tickets.insertMessage(
            new TicketMessage(
                Ids.newId(),
                ticket.id(),
                resolveSenderType(principal),
                principal.subject(),
                resolveSenderName(principal),
                reason.trim(),
                false,
                null,
                List.of(),
                now));
      }
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("status", TicketStatus.IN_PROGRESS.name());
    data.put("reopened_at", now);
    return data;
  }

  @Transactional
  public Map<String, Object> escalate(
      MedmatePrincipal principal, UUID id, String escalationLevel, String reason) {
    requireAdmin(principal);
    Ticket ticket = requireTicket(id);
    Instant now = clock.instant();
    SlaLevel target =
        escalationLevel == null || escalationLevel.isBlank()
            ? ticket.slaLevel().next()
            : parseSlaLevel(escalationLevel);
    if (target.ordinal() < ticket.slaLevel().ordinal()) {
      target = ticket.slaLevel();
    }
    Instant due =
        ticket.firstResponseAt() == null
            ? now.plus(target.firstResponseWindow())
            : ticket.slaDueAt();
    Ticket updated = ticket.withSla(target, due, now);
    tickets.update(updated);
    notifications.notifySupervisorEscalation(ticket.id(), reason == null ? "Escalated" : reason);
    notifications.notifyEscalation(ticket.id(), ticket.customerId(), target.name());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("sla_level", target.name());
    data.put("escalated_at", now);
    data.put("supervisor_notified", true);
    return data;
  }

  @Transactional
  public Map<String, Object> changePriority(
      MedmatePrincipal principal, UUID id, String priorityRaw) {
    requireAdmin(principal);
    TicketPriority priority = requirePriority(priorityRaw);
    Ticket ticket = requireTicket(id);
    Instant now = clock.instant();
    SlaPolicy policy = resolvePolicy(ticket.category(), priority);
    SlaLevel sla = policy.slaLevel();
    Instant due;
    if (ticket.firstResponseAt() == null) {
      Instant createdBased = ticket.createdAt().plusSeconds(policy.firstResponseSlaMinutes() * 60L);
      if (createdBased.isBefore(now)) {
        due = now.plusSeconds(policy.firstResponseSlaMinutes() * 60L);
      } else {
        due = createdBased;
      }
    } else {
      due = ticket.slaDueAt();
    }
    Ticket updated = ticket.withPriority(priority, sla, due, now);
    tickets.update(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ticket_id", ticket.ticketId());
    data.put("priority", priority.name());
    data.put("sla_level", sla.name());
    data.put("updated_at", now);
    return data;
  }

  @Transactional
  public int dispatchDueCsatSurveys(int limit) {
    Instant now = clock.instant();
    int n = 0;
    for (Ticket t : tickets.findDueCsatSurveys(now, limit)) {
      notifications.notifyCsatSurvey(t.id(), t.customerId(), t.channel().name());
      tickets.update(t.withCsatSent(now));
      n++;
    }
    return n;
  }

  private Ticket createTicket(
      UUID customerId,
      UUID pharmacyId,
      UUID orderId,
      TicketCategory cat,
      String subject,
      TicketPriority priority,
      SlaPolicy policy,
      TicketChannel channel,
      UUID createdByAdminId,
      Instant now) {
    LocalDate day = TicketIds.dayKey(now);
    int seq = tickets.nextTicketSeq(day);
    String humanId = TicketIds.format(day, seq);
    UUID assigned = autoAssign(cat);
    TicketStatus status = assigned == null ? TicketStatus.OPEN : TicketStatus.IN_PROGRESS;
    Instant firstDue = now.plusSeconds(policy.firstResponseSlaMinutes() * 60L);
    Instant resolutionDue = now.plusSeconds(policy.resolutionSlaMinutes() * 60L);
    Ticket ticket =
        new Ticket(
            Ids.newId(),
            humanId,
            customerId,
            pharmacyId,
            orderId,
            cat,
            subject,
            status,
            priority,
            policy.slaLevel(),
            firstDue,
            firstDue,
            resolutionDue,
            assigned,
            channel,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            createdByAdminId,
            null,
            null,
            null,
            now,
            now);
    Ticket saved = tickets.insert(ticket);
    if (assigned == null) {
      notifications.notifySupervisorEscalation(saved.id(), "OVERFLOW_QUEUE");
    }
    return saved;
  }

  private SlaPolicy resolvePolicy(TicketCategory cat, TicketPriority priority) {
    return slaPolicies
        .resolve(cat, priority)
        .orElseGet(
            () ->
                new SlaPolicy(
                    null,
                    "ALL",
                    priority.name(),
                    (int) priority.firstResponseSla().toMinutes(),
                    (int) priority.firstResponseSla().toMinutes() * 48,
                    priority.defaultSlaLevel(),
                    null,
                    null,
                    null));
  }

  private UUID autoAssign(TicketCategory category) {
    return agentService.pickAutoAssign(category);
  }

  private void insertInitialMessage(
      Ticket ticket, MedmatePrincipal principal, String description, List<String> attachments) {
    tickets.insertMessage(
        new TicketMessage(
            Ids.newId(),
            ticket.id(),
            resolveSenderType(principal),
            principal.subject(),
            resolveSenderName(principal),
            description,
            false,
            null,
            attachments,
            ticket.createdAt()));
  }

  private Map<String, Object> createdPayload(Ticket ticket) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", ticket.id());
    data.put("ticket_id", ticket.ticketId());
    data.put("status", ticket.status().name());
    data.put("priority", ticket.priority().name());
    data.put("sla_level", ticket.slaLevel().name());
    data.put("sla_due_at", ticket.slaDueAt());
    data.put("created_at", ticket.createdAt());
    return data;
  }

  private Map<String, Object> toListItem(Ticket t, Instant now) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", t.id());
    m.put("ticket_id", t.ticketId());
    m.put("customer_name", customers.displayName(t.customerId()).orElse("Customer"));
    m.put("category", t.category().name());
    m.put("subject", t.subject());
    m.put("status", t.status().name());
    m.put("priority", t.priority().name());
    m.put("sla_level", t.slaLevel().name());
    m.put("sla_due_at", t.slaDueAt());
    m.put("sla_breached", t.slaBreached(now));
    m.put("channel", t.channel().name());
    m.put("assigned_agent_id", t.assignedAgentId());
    m.put("created_at", t.createdAt());
    return m;
  }

  private Map<String, Object> toMessageMap(TicketMessage m) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("message_id", m.id());
    row.put("sender", m.senderType().name().toLowerCase(Locale.ROOT));
    row.put("sender_name", m.senderName());
    row.put("message", m.message());
    row.put("is_internal_note", m.internalNote());
    row.put("attachments", m.attachments());
    if (m.cannedResponseId() != null) {
      row.put("canned_response_id", m.cannedResponseId());
    }
    row.put("created_at", m.createdAt());
    return row;
  }

  private static Map<String, Object> toChipsMap(Chips c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("open", c.open());
    m.put("in_progress", c.inProgress());
    m.put("sla_breached", c.slaBreached());
    m.put("open_disputes", c.openDisputes());
    m.put("refund_exposure_rs", c.refundExposureRs());
    m.put("csat_pct", c.csatPct());
    return m;
  }

  private String buildCsv(List<Ticket> rows, Instant now) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "ticket_id,customer_id,category,subject,status,priority,sla_level,sla_due_at,sla_breached,channel,assigned_agent_id,created_at\n");
    for (Ticket t : rows) {
      sb.append(csv(t.ticketId())).append(',');
      sb.append(csv(t.customerId().toString())).append(',');
      sb.append(csv(t.category().name())).append(',');
      sb.append(csv(t.subject())).append(',');
      sb.append(csv(t.status().name())).append(',');
      sb.append(csv(t.priority().name())).append(',');
      sb.append(csv(t.slaLevel().name())).append(',');
      sb.append(csv(t.slaDueAt().toString())).append(',');
      sb.append(t.slaBreached(now)).append(',');
      sb.append(csv(t.channel().name())).append(',');
      String agentCol = t.assignedAgentId() == null ? null : t.assignedAgentId().toString();
      sb.append(csv(agentCol)).append(',');
      sb.append(csv(t.createdAt().toString())).append('\n');
    }
    return sb.toString();
  }

  private static String csv(String v) {
    if (v == null) {
      return "\"\"";
    }
    return "\"" + v.replace("\"", "\"\"") + "\"";
  }

  public byte[] exportCsvBytes(ListResult result) {
    Object csv = result.data().get("csv");
    return csv == null ? new byte[0] : csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  private String resolveReplyBody(Ticket ticket, ReplyCommand cmd, Instant now) {
    String freeform = cmd.message() == null ? "" : cmd.message().trim();
    if (cmd.cannedResponseId() == null) {
      if (freeform.isBlank()) {
        throw new AppException("VALIDATION_ERROR", "message is required", 400);
      }
      return freeform;
    }
    CannedResponse canned =
        cannedResponses
            .findById(cmd.cannedResponseId())
            .orElseThrow(
                () ->
                    new AppException(
                        "CANNED_RESPONSE_NOT_FOUND", "Canned response not found", 404));
    String customerName =
        customers.find(ticket.customerId()).map(CustomerContext::customerName).orElse(null);
    String orderId = ticket.orderId() == null ? null : ticket.orderId().toString();
    String pharmacyName = null;
    String refundAmount = null;
    if (ticket.orderId() != null) {
      var ctx = orders.find(ticket.orderId());
      if (ctx.isPresent()) {
        pharmacyName = ctx.get().pharmacyName();
        refundAmount = CannedTemplate.formatRefundPaise(ctx.get().totalPayablePaise());
      }
    }
    String interpolated =
        CannedTemplate.interpolate(
            canned.body(),
            CannedTemplate.context(
                customerName, orderId, refundAmount, pharmacyName, ticket.ticketId()));
    cannedResponses.recordUsage(canned.id(), now);
    if (freeform.isBlank()) {
      return interpolated;
    }
    return interpolated + "\n" + freeform;
  }

  private UUID resolveCustomerIdForCreate(MedmatePrincipal principal) {
    if (principal.role() == AuthRole.CUSTOMER) {
      return principal.subject();
    }
    if (principal.role() == AuthRole.PHARMACY_OWNER
        || principal.role() == AuthRole.PHARMACY_STAFF) {
      return principal.subject();
    }
    throw new AppException(
        "VALIDATION_ERROR", "Use admin create-on-behalf for admin-created tickets", 400);
  }

  private SenderType resolveSenderType(MedmatePrincipal principal) {
    return switch (principal.role()) {
      case CUSTOMER -> SenderType.CUSTOMER;
      case PHARMACY_OWNER, PHARMACY_STAFF -> SenderType.PHARMACY;
      default -> SenderType.AGENT;
    };
  }

  private String resolveSenderName(MedmatePrincipal principal) {
    if (isAdmin(principal)) {
      return agents
          .findById(principal.subject())
          .map(AgentProfile::displayName)
          .orElse("Support Agent");
    }
    return customers.displayName(principal.subject()).orElse("Customer");
  }

  private Ticket requireTicket(UUID id) {
    return tickets
        .findById(id)
        .orElseThrow(() -> new AppException("TICKET_NOT_FOUND", "Ticket not found", 404));
  }

  private void assertCanView(MedmatePrincipal principal, Ticket ticket) {
    if (isAdmin(principal)) {
      return;
    }
    if (principal.role() == AuthRole.CUSTOMER && ticket.customerId().equals(principal.subject())) {
      return;
    }
    if ((principal.role() == AuthRole.PHARMACY_OWNER || principal.role() == AuthRole.PHARMACY_STAFF)
        && (ticket.customerId().equals(principal.subject())
            || (ticket.pharmacyId() != null
                && ticket.pharmacyId().equals(principal.pharmacyId())))) {
      return;
    }
    throw new AppException("FORBIDDEN", "Cannot view this ticket", 403);
  }

  private void assertCanReply(MedmatePrincipal principal, Ticket ticket) {
    assertCanView(principal, ticket);
  }

  private static void requireAdmin(MedmatePrincipal principal) {
    requirePrincipal(principal);
    if (!ADMIN_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Admin role required", 403);
    }
  }

  private static void requireCreateRole(MedmatePrincipal principal) {
    requirePrincipal(principal);
    if (!CREATE_ROLES.contains(principal.role())) {
      throw new AppException("FORBIDDEN", "Insufficient role", 403);
    }
  }

  private static void requirePrincipal(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }

  private static boolean isAdmin(MedmatePrincipal principal) {
    return ADMIN_ROLES.contains(principal.role());
  }

  private static TicketPriority resolvePriority(String raw, TicketCategory category) {
    if (!blank(raw)) {
      return requirePriority(raw);
    }
    return category == TicketCategory.ORDER ? TicketPriority.HIGH : TicketPriority.MEDIUM;
  }

  private static TicketCategory requireCategory(String raw) {
    TicketCategory c = parseCategory(raw);
    if (c == null) {
      throw new AppException("VALIDATION_ERROR", "category is required", 400);
    }
    return c;
  }

  private static boolean blank(String s) {
    if (s == null) {
      return true;
    }
    return s.isBlank();
  }

  private static String requireSubject(String subject) {
    if (blank(subject)) {
      throw new AppException("VALIDATION_ERROR", "subject is required", 400);
    }
    String s = subject.trim();
    if (s.length() > 200) {
      throw new AppException("VALIDATION_ERROR", "subject max 200 chars", 400);
    }
    return s;
  }

  private static String requireDescription(String description) {
    if (blank(description)) {
      throw new AppException("VALIDATION_ERROR", "description is required", 400);
    }
    return description.trim();
  }

  private static TicketPriority requirePriority(String raw) {
    TicketPriority p = parsePriority(raw);
    if (p == null) {
      throw new AppException("VALIDATION_ERROR", "priority is required", 400);
    }
    return p;
  }

  private static TicketStatus parseStatus(String raw) {
    if (blank(raw)) {
      return null;
    }
    try {
      return TicketStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid status", 400);
    }
  }

  private static TicketPriority parsePriority(String raw) {
    if (blank(raw)) {
      return null;
    }
    try {
      return TicketPriority.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid priority", 400);
    }
  }

  private static TicketCategory parseCategory(String raw) {
    if (blank(raw)) {
      return null;
    }
    try {
      return TicketCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid category", 400);
    }
  }

  private static TicketChannel parseChannel(String raw) {
    if (blank(raw)) {
      return null;
    }
    try {
      return TicketChannel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid channel", 400);
    }
  }

  private static TicketChannel parseChannelOrDefault(String raw) {
    TicketChannel c = parseChannel(raw);
    return c == null ? TicketChannel.APP : c;
  }

  private static SlaLevel parseSlaLevel(String raw) {
    try {
      return SlaLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new AppException("VALIDATION_ERROR", "Invalid escalation_level", 400);
    }
  }

  private static String blankToNull(String s) {
    return blank(s) ? null : s.trim();
  }
}
