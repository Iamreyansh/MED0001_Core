package com.nammamedmate.support.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.support.application.port.out.DisputeStore;
import com.nammamedmate.support.application.port.out.EscalationMatrixStore;
import com.nammamedmate.support.application.port.out.TicketStore.ListFilter;
import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.Dispute;
import com.nammamedmate.support.domain.DisputeEvent;
import com.nammamedmate.support.domain.DisputeStatus;
import com.nammamedmate.support.domain.DisputeType;
import com.nammamedmate.support.domain.HelpArticle;
import com.nammamedmate.support.domain.LiableParty;
import com.nammamedmate.support.domain.RefundDestination;
import com.nammamedmate.support.domain.SenderType;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketMessage;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcSupportStoresTest {

  @Test
  @SuppressWarnings("unchecked")
  void ticketStoreDelegates() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcTicketStore store = new JdbcTicketStore(jdbc);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = UUID.randomUUID();
    Ticket ticket =
        new Ticket(
            id,
            "TKT-20260724-000001",
            UUID.randomUUID(),
            null,
            null,
            TicketCategory.ORDER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L3,
            now.plusSeconds(28800),
            now.plusSeconds(28800),
            now.plusSeconds(28800),
            null,
            TicketChannel.APP,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now,
            now);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(87.4);

    assertThat(store.nextTicketSeq(LocalDate.of(2026, 7, 24))).isEqualTo(1);
    assertThat(store.insert(ticket)).isEqualTo(ticket);
    store.update(ticket);

    ResultSet rs = mockTicketRs(ticket);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByTicketId(ticket.ticketId())).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store.list(
                new ListFilter(
                    TicketStatus.OPEN,
                    TicketPriority.HIGH,
                    TicketCategory.ORDER,
                    TicketChannel.APP,
                    "TKT",
                    null,
                    0,
                    20)))
        .hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(store.count(new ListFilter(null, null, null, null, null, null, 0, 20))).isEqualTo(1);

    assertThat(store.chips(now).open()).isEqualTo(1);
    assertThat(store.countOpenAssigned(UUID.randomUUID())).isEqualTo(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
    assertThat(store.countUnassignedOpen()).isEqualTo(2);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
    assertThat(store.countUnassignedOpen()).isEqualTo(0);

    TicketMessage msg =
        new TicketMessage(
            UUID.randomUUID(),
            id,
            SenderType.CUSTOMER,
            ticket.customerId(),
            "Priya",
            "hi",
            false,
            null,
            List.of("https://x"),
            now);
    assertThat(store.insertMessage(msg)).isEqualTo(msg);

    ResultSet mrs = mockMessageRs(msg);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mrs, 0));
            });
    assertThat(store.listMessages(id)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findDueCsatSurveys(now, 10)).hasSize(1);
    assertThat(store.findSlaBreachedWithoutFirstResponse(now, 10)).hasSize(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findOpenForSlaScan(10)).hasSize(1);
    UUID agentId = UUID.randomUUID();
    assertThat(store.listAssignedOpen(agentId)).hasSize(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listResolvedByAgent(agentId, now.minusSeconds(10), now.plusSeconds(10)))
        .hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(2L);
    assertThat(store.resolvedSlaStats().totalResolved()).isEqualTo(2);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    assertThat(store.resolvedSlaStats().withinSla()).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("unchecked")
  void slaPolicyAndEscalationStores() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcSlaPolicyStore policies = new JdbcSlaPolicyStore(jdbc);
    JdbcEscalationMatrixStore matrix = new JdbcEscalationMatrixStore(jdbc);
    UUID id = UUID.fromString("a1500003-0001-4000-8000-000000000005");
    Instant now = Instant.parse("2026-07-24T10:00:00Z");

    ResultSet prs = mock(ResultSet.class);
    when(prs.getObject("id")).thenReturn(id);
    when(prs.getString("category")).thenReturn("ORDER");
    when(prs.getString("priority")).thenReturn("ANY");
    when(prs.getInt("first_response_sla_minutes")).thenReturn(30);
    when(prs.getInt("resolution_sla_minutes")).thenReturn(480);
    when(prs.getString("sla_level")).thenReturn("L1");
    when(prs.getObject("updated_by")).thenReturn(null);
    when(prs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(prs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(prs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(prs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(prs, 0));
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(prs.getInt("first_response_sla_minutes")).thenReturn(30, 45);
    assertThat(policies.listAll()).hasSize(1);
    assertThat(policies.findById(id)).isPresent();
    assertThat(policies.resolve(TicketCategory.ORDER, TicketPriority.HIGH)).isPresent();
    assertThat(policies.update(id, 45, 960, null, UUID.randomUUID(), now).firstResponseSlaMinutes())
        .isEqualTo(45);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(prs.getString("category")).thenReturn("ORDER");
              when(prs.getString("priority")).thenReturn("ANY");
              return List.of(mapper.mapRow(prs, 0));
            });
    assertThat(policies.resolve(TicketCategory.ORDER, TicketPriority.URGENT)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenReturn(List.of())
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(prs.getString("category")).thenReturn("ALL");
              when(prs.getString("priority")).thenReturn("MEDIUM");
              return List.of(mapper.mapRow(prs, 0));
            });
    assertThat(policies.resolve(TicketCategory.ACCOUNT, TicketPriority.MEDIUM)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(prs, 0));
            });
    when(prs.getInt("first_response_sla_minutes")).thenReturn(30);
    when(prs.getInt("resolution_sla_minutes")).thenReturn(480);
    assertThat(policies.update(id, null, null, SlaLevel.L2, UUID.randomUUID(), now).slaLevel())
        .isEqualTo(SlaLevel.L1); // mock still maps L1 from rs unless we change
    when(prs.getString("sla_level")).thenReturn("L2");
    assertThat(policies.update(id, null, null, SlaLevel.L2, UUID.randomUUID(), now).slaLevel())
        .isEqualTo(SlaLevel.L2);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(policies.resolve(TicketCategory.ACCOUNT, TicketPriority.LOW)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThatThrownBy(() -> policies.update(UUID.randomUUID(), null, null, SlaLevel.L1, null, now))
        .isInstanceOf(AppException.class);
    when(prs.getTimestamp("updated_at")).thenReturn(null);
    when(prs.getTimestamp("created_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(prs, 0));
            });
    assertThat(policies.findById(id)).isPresent();

    ResultSet ers = mock(ResultSet.class);
    when(ers.getObject("id")).thenReturn(UUID.randomUUID());
    when(ers.getString("level")).thenReturn("L2");
    when(ers.getString("criteria")).thenReturn("L1 breach");
    when(ers.getString("assigned_team")).thenReturn("Senior Agents");
    java.sql.Array channels = mock(java.sql.Array.class);
    when(channels.getArray()).thenReturn(new String[] {"IN_APP", "WHATSAPP"});
    when(ers.getArray("notification_channels")).thenReturn(channels);
    when(ers.getInt("auto_escalate_after_minutes")).thenReturn(120);
    when(ers.getObject("updated_by")).thenReturn(null);
    when(ers.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(ers, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(ers, 0));
            });
    assertThat(matrix.listAll()).hasSize(1);
    assertThat(matrix.findByLevel(SlaLevel.L2)).isPresent();
    assertThat(
            matrix.updateRules(
                List.of(
                    new EscalationMatrixStore.RulePatch(null, 1, List.of()),
                    new EscalationMatrixStore.RulePatch(
                        SlaLevel.L2, 90, List.of("IN_APP", "EMAIL"))),
                UUID.randomUUID(),
                now))
        .containsExactly(SlaLevel.L2);
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(
            matrix.updateRules(
                List.of(new EscalationMatrixStore.RulePatch(SlaLevel.L3, 1, List.of())), null, now))
        .isEmpty();
    java.sql.Array objArr = mock(java.sql.Array.class);
    when(objArr.getArray()).thenReturn(new Object[] {"IN_APP", null});
    when(ers.getArray("notification_channels")).thenReturn(null, objArr);
    when(ers.getTimestamp("updated_at")).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(ers.getArray("notification_channels")).thenReturn(null);
              Object a = mapper.mapRow(ers, 0);
              when(ers.getArray("notification_channels")).thenReturn(objArr);
              Object b = mapper.mapRow(ers, 1);
              java.sql.Array other = mock(java.sql.Array.class);
              when(other.getArray()).thenReturn("nope");
              when(ers.getArray("notification_channels")).thenReturn(other);
              Object c = mapper.mapRow(ers, 2);
              return List.of(a, b, c);
            });
    assertThat(matrix.findByLevel(SlaLevel.L2)).isPresent();
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(ers.getArray("notification_channels")).thenReturn(channels);
              when(ers.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(ers, 0));
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(
            matrix.updateRules(
                List.of(new EscalationMatrixStore.RulePatch(SlaLevel.L2, null, null)),
                UUID.randomUUID(),
                now))
        .containsExactly(SlaLevel.L2);
    assertThat(
            matrix.updateRules(
                List.of(
                    new EscalationMatrixStore.RulePatch(
                        SlaLevel.L2, 11, List.of("IN_APP", "CALL"))),
                UUID.randomUUID(),
                now))
        .containsExactly(SlaLevel.L2);
    assertThat(
            matrix.updateRules(
                List.of(new EscalationMatrixStore.RulePatch(SlaLevel.L2, 1, List.of())),
                UUID.randomUUID(),
                now))
        .containsExactly(SlaLevel.L2);
  }

  @Test
  @SuppressWarnings("unchecked")
  void agentStoreDelegates() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAgentStore store = new JdbcAgentStore(jdbc);
    UUID agentId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("admin_user_id")).thenReturn(agentId);
    when(rs.getArray("specialties")).thenReturn(null);
    when(rs.getBoolean("is_online")).thenReturn(true);
    when(rs.getInt("max_load")).thenReturn(20);
    when(rs.getString("display_name")).thenReturn("Ravi");
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(agentId)).isPresent();
    assertThat(store.listAll()).hasSize(1);
    assertThat(store.listOnline()).hasSize(1);
    assertThat(store.listOnlineForCategory(TicketCategory.ORDER)).hasSize(1);

    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
    assertThat(store.updateOnline(agentId, false, now).adminUserId()).isEqualTo(agentId);
    when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
    assertThatThrownBy(() -> store.updateOnline(agentId, true, now))
        .isInstanceOf(com.nammamedmate.kernel.error.AppException.class);

    when(jdbc.query(contains("admin_staff"), any(RowMapper.class), eq(agentId)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              ResultSet ers = mock(ResultSet.class);
              when(ers.getString("email")).thenReturn("ravi@x.com");
              return List.of(mapper.mapRow(ers, 0));
            });
    assertThat(store.findEmail(agentId)).contains("ravi@x.com");

    UUID snapId = UUID.randomUUID();
    ResultSet srs = mock(ResultSet.class);
    when(srs.getObject("id")).thenReturn(snapId);
    when(srs.getObject("agent_id")).thenReturn(agentId);
    when(srs.getDate("week_start")).thenReturn(java.sql.Date.valueOf("2026-07-13"));
    when(srs.getInt("tickets_handled")).thenReturn(10);
    when(srs.getBigDecimal("avg_handle_minutes")).thenReturn(java.math.BigDecimal.valueOf(18.4));
    when(srs.getBigDecimal("csat_score_avg")).thenReturn(java.math.BigDecimal.valueOf(4.6));
    when(srs.getInt("sla_breach_count")).thenReturn(1);
    when(srs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(jdbc.update(
            contains("UPDATE support_agent_performance"), any(), any(), any(), any(), any(), any()))
        .thenReturn(0);
    when(jdbc.update(
            contains("INSERT INTO support_agent_performance"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    store.upsertSnapshot(
        new com.nammamedmate.support.domain.AgentPerformanceSnapshot(
            snapId,
            agentId,
            java.time.LocalDate.parse("2026-07-13"),
            10,
            java.math.BigDecimal.valueOf(18.4),
            java.math.BigDecimal.valueOf(4.6),
            1,
            now));
    when(jdbc.update(
            contains("UPDATE support_agent_performance"), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    store.upsertSnapshot(
        new com.nammamedmate.support.domain.AgentPerformanceSnapshot(
            snapId, agentId, java.time.LocalDate.parse("2026-07-13"), 11, null, null, 2, now));
    when(jdbc.query(
            contains("support_agent_performance_snapshots"), any(RowMapper.class), eq(agentId)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(srs, 0));
            });
    assertThat(store.listSnapshots(agentId)).hasSize(1);
    when(jdbc.query(
            contains("support_agent_performance_snapshots"),
            any(RowMapper.class),
            eq(agentId),
            any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(srs, 0));
            });
    assertThat(store.findSnapshot(agentId, java.time.LocalDate.parse("2026-07-13"))).isPresent();
  }

  @Test
  @SuppressWarnings("unchecked")
  void ticketStoreArrayAndNullBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcTicketStore store = new JdbcTicketStore(jdbc);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = UUID.randomUUID();
    UUID agentId = UUID.randomUUID();
    Ticket ticket =
        new Ticket(
            id,
            "TKT-20260724-000002",
            UUID.randomUUID(),
            null,
            null,
            TicketCategory.ORDER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L3,
            now.plusSeconds(28800),
            now.plusSeconds(28800),
            now.plusSeconds(28800),
            agentId,
            TicketChannel.APP,
            now,
            now,
            "sum",
            5,
            "ok",
            now,
            null,
            null,
            null,
            null,
            null,
            now,
            now);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.nextTicketSeq(LocalDate.of(2026, 7, 24))).isEqualTo(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.chips(now).open()).isEqualTo(0);
    assertThat(store.countOpenAssigned(agentId)).isEqualTo(0);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.count(new ListFilter(null, null, null, null, null, agentId, 0, 20)))
        .isEqualTo(0);

    assertThat(store.insert(ticket)).isEqualTo(ticket);
    store.update(ticket);

    TicketMessage msg =
        new TicketMessage(
            UUID.randomUUID(),
            id,
            SenderType.AGENT,
            agentId,
            "Ravi",
            "hi",
            true,
            UUID.randomUUID(),
            java.util.Arrays.asList("a", null, "b\"c\\d"),
            now);
    assertThat(store.insertMessage(msg)).isEqualTo(msg);
    assertThat(
            store.insertMessage(
                new TicketMessage(
                    UUID.randomUUID(),
                    id,
                    SenderType.CUSTOMER,
                    ticket.customerId(),
                    "P",
                    "x",
                    false,
                    null,
                    List.of(),
                    now)))
        .isNotNull();

    java.sql.Array stringArr = mock(java.sql.Array.class);
    when(stringArr.getArray()).thenReturn(new String[] {"x", "y"});
    java.sql.Array objArr = mock(java.sql.Array.class);
    when(objArr.getArray()).thenReturn(new Object[] {"z", null, 1});
    java.sql.Array otherArr = mock(java.sql.Array.class);
    when(otherArr.getArray()).thenReturn(42);

    ResultSet mrs = mock(ResultSet.class);
    when(mrs.getObject("id")).thenReturn(msg.id());
    when(mrs.getObject("ticket_id")).thenReturn(id);
    when(mrs.getString("sender_type")).thenReturn("AGENT");
    when(mrs.getObject("sender_id")).thenReturn(agentId);
    when(mrs.getString("sender_name")).thenReturn("Ravi");
    when(mrs.getString("message")).thenReturn("hi");
    when(mrs.getBoolean("is_internal_note")).thenReturn(true);
    when(mrs.getObject("canned_response_id")).thenReturn(UUID.randomUUID());
    when(mrs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(mrs.getArray("attachments")).thenReturn(stringArr);
              Object a = mapper.mapRow(mrs, 0);
              when(mrs.getArray("attachments")).thenReturn(objArr);
              Object b = mapper.mapRow(mrs, 1);
              when(mrs.getArray("attachments")).thenReturn(otherArr);
              Object c = mapper.mapRow(mrs, 2);
              return List.of(a, b, c);
            });
    assertThat(store.listMessages(id)).hasSize(3);

    ResultSet rs = mockTicketRs(ticket);
    when(rs.getTimestamp("first_response_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("resolved_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("csat_survey_scheduled_at")).thenReturn(Timestamp.from(now));
    when(rs.getObject("assigned_agent_id")).thenReturn(agentId);
    when(rs.getObject("csat_score")).thenReturn(5);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.list(new ListFilter(null, null, null, null, null, agentId, 0, 5))).hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void agentStoreArrayBranches() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAgentStore store = new JdbcAgentStore(jdbc);
    UUID agentId = UUID.randomUUID();
    java.sql.Array stringArr = mock(java.sql.Array.class);
    when(stringArr.getArray()).thenReturn(new String[] {"ORDER"});
    java.sql.Array objArr = mock(java.sql.Array.class);
    when(objArr.getArray()).thenReturn(new Object[] {"PAYMENT", null});
    java.sql.Array otherArr = mock(java.sql.Array.class);
    when(otherArr.getArray()).thenReturn("nope");

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("admin_user_id")).thenReturn(agentId);
    when(rs.getBoolean("is_online")).thenReturn(true);
    when(rs.getInt("max_load")).thenReturn(20);
    when(rs.getString("display_name")).thenReturn("Ravi");
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs.getArray("specialties")).thenReturn(stringArr);
              Object a = mapper.mapRow(rs, 0);
              when(rs.getArray("specialties")).thenReturn(objArr);
              Object b = mapper.mapRow(rs, 1);
              when(rs.getArray("specialties")).thenReturn(otherArr);
              Object c = mapper.mapRow(rs, 2);
              return List.of(a, b, c);
            });
    assertThat(store.listOnlineForCategory(TicketCategory.ORDER)).hasSize(3);
  }

  private static ResultSet mockTicketRs(Ticket t) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(t.id());
    when(rs.getString("ticket_id")).thenReturn(t.ticketId());
    when(rs.getObject("customer_id")).thenReturn(t.customerId());
    when(rs.getObject("pharmacy_id")).thenReturn(null);
    when(rs.getObject("order_id")).thenReturn(null);
    when(rs.getString("category")).thenReturn(t.category().name());
    when(rs.getString("subject")).thenReturn(t.subject());
    when(rs.getString("status")).thenReturn(t.status().name());
    when(rs.getString("priority")).thenReturn(t.priority().name());
    when(rs.getString("sla_level")).thenReturn(t.slaLevel().name());
    when(rs.getTimestamp("sla_due_at")).thenReturn(Timestamp.from(t.slaDueAt()));
    when(rs.getTimestamp("first_response_due_at"))
        .thenReturn(null, Timestamp.from(t.firstResponseDueAt()));
    when(rs.getTimestamp("resolution_due_at"))
        .thenReturn(null, Timestamp.from(t.resolutionDueAt()));
    when(rs.getObject("assigned_agent_id")).thenReturn(null);
    when(rs.getString("channel")).thenReturn(t.channel().name());
    when(rs.getTimestamp("first_response_at")).thenReturn(null);
    when(rs.getTimestamp("resolved_at")).thenReturn(null);
    when(rs.getString("resolution_summary")).thenReturn(null);
    when(rs.getObject("csat_score")).thenReturn(null);
    when(rs.getString("csat_feedback")).thenReturn(null);
    when(rs.getTimestamp("csat_survey_scheduled_at")).thenReturn(null);
    when(rs.getTimestamp("csat_survey_sent_at")).thenReturn(null);
    when(rs.getObject("created_by_admin_id")).thenReturn(null);
    when(rs.getTimestamp("sla_paused_at")).thenReturn(null);
    when(rs.getTimestamp("sla_l4_notified_at")).thenReturn(null);
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(t.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(t.updatedAt()));
    return rs;
  }

  private static ResultSet mockMessageRs(TicketMessage m) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(m.id());
    when(rs.getObject("ticket_id")).thenReturn(m.ticketId());
    when(rs.getString("sender_type")).thenReturn(m.senderType().name());
    when(rs.getObject("sender_id")).thenReturn(m.senderId());
    when(rs.getString("sender_name")).thenReturn(m.senderName());
    when(rs.getString("message")).thenReturn(m.message());
    when(rs.getBoolean("is_internal_note")).thenReturn(m.internalNote());
    when(rs.getObject("canned_response_id")).thenReturn(null);
    when(rs.getArray("attachments")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(m.createdAt()));
    return rs;
  }

  @Test
  @SuppressWarnings("unchecked")
  void disputeStoreDelegates() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcDisputeStore store = new JdbcDisputeStore(jdbc);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID id = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    Dispute dispute =
        new Dispute(
            id,
            "DSP-20260724-000001",
            orderId,
            customerId,
            DisputeType.WRONG_ITEMS,
            "desc",
            List.of("https://e"),
            DisputeStatus.OPEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now.plusSeconds(172800),
            LiableParty.PHARMACY,
            false,
            null,
            now,
            now,
            null);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L, 9600L, 1L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(18.4);

    assertThat(store.nextDisputeSeq(LocalDate.of(2026, 7, 24))).isEqualTo(1);
    assertThat(store.insert(dispute)).isEqualTo(dispute);
    store.update(dispute.withInvestigating(UUID.randomUUID(), now));

    ResultSet rs = mockDisputeRs(dispute);
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(store.findById(id)).isPresent();
    assertThat(store.findByOrderId(orderId)).isPresent();
    assertThat(store.findBannerDispute(orderId)).isPresent();
    assertThat(
            store.list(
                new DisputeStore.ListFilter(
                    DisputeStatus.OPEN, null, DisputeType.WRONG_ITEMS, 0, 10)))
        .hasSize(1);
    assertThat(store.count(new DisputeStore.ListFilter(null, LiableParty.PHARMACY, null, 0, 10)))
        .isEqualTo(1);
    assertThat(store.listForCustomer(customerId, 0, 10)).hasSize(1);
    assertThat(store.countForCustomer(customerId)).isEqualTo(1);
    assertThat(store.chips(now).openDisputes()).isEqualTo(1);
    assertThat(store.findSlaBreachedOpen(now.plusSeconds(200000), 10)).hasSize(1);

    DisputeEvent event =
        new DisputeEvent(UUID.randomUUID(), id, "DISPUTE_RAISED", customerId, "Priya", "n", now);
    assertThat(store.insertEvent(event)).isEqualTo(event);
    ResultSet ers = mock(ResultSet.class);
    when(ers.getObject("id")).thenReturn(event.id());
    when(ers.getObject("dispute_id")).thenReturn(id);
    when(ers.getString("event_type")).thenReturn(event.eventType());
    when(ers.getObject("actor_id")).thenReturn(customerId);
    when(ers.getString("actor_name")).thenReturn("Priya");
    when(ers.getString("notes")).thenReturn("n");
    when(ers.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(ers, 0));
            });
    assertThat(store.listEvents(id)).hasSize(1);
    assertThat(JdbcDisputeStore.toTextArrayLiteral(List.of())).isEqualTo("{}");
    assertThat(JdbcDisputeStore.toTextArrayLiteral(null)).isEqualTo("{}");
    assertThat(JdbcDisputeStore.toTextArrayLiteral(List.of("a\"b", "c"))).contains("a\\\"b");
    assertThat(JdbcDisputeStore.toTextArrayLiteral(java.util.Arrays.asList("a", null)))
        .isEqualTo("{\"a\",\"\"}");

    // evidence array mapping branches
    java.sql.Array emptyArr = mock(java.sql.Array.class);
    when(emptyArr.getArray()).thenReturn(new Object[0]);
    java.sql.Array nullInner = mock(java.sql.Array.class);
    when(nullInner.getArray()).thenReturn(null);
    java.sql.Array mixed = mock(java.sql.Array.class);
    when(mixed.getArray()).thenReturn(new Object[] {"https://e", null, 3});
    ResultSet rs2 = mockDisputeRs(dispute);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(rs2.getArray("evidence_urls")).thenReturn(null);
              Object a = mapper.mapRow(rs2, 0);
              when(rs2.getArray("evidence_urls")).thenReturn(emptyArr);
              Object b = mapper.mapRow(rs2, 1);
              when(rs2.getArray("evidence_urls")).thenReturn(nullInner);
              Object c = mapper.mapRow(rs2, 2);
              when(rs2.getArray("evidence_urls")).thenReturn(mixed);
              when(rs2.getString("liable_party")).thenReturn("PHARMACY");
              when(rs2.getString("refund_to")).thenReturn("SOURCE");
              when(rs2.getObject("refund_amount_paise")).thenReturn(9600L);
              when(rs2.getTimestamp("resolved_at")).thenReturn(Timestamp.from(now));
              when(rs2.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
              Object d = mapper.mapRow(rs2, 3);
              return List.of(a, b, c, d);
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.nextDisputeSeq(LocalDate.of(2026, 7, 25))).isEqualTo(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(null, null);
    when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    assertThat(store.chips(now).openDisputes()).isEqualTo(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.count(new DisputeStore.ListFilter(null, null, null, 0, 10))).isEqualTo(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(customerId))).thenReturn(null);
    assertThat(store.countForCustomer(customerId)).isEqualTo(0);

    Dispute withRefund =
        new Dispute(
            id,
            "DSP-20260724-000001",
            orderId,
            customerId,
            DisputeType.WRONG_ITEMS,
            "desc",
            List.of(),
            DisputeStatus.RESOLVED,
            LiableParty.PHARMACY,
            9600L,
            RefundDestination.SOURCE,
            "notes",
            null,
            UUID.randomUUID(),
            now,
            now.plusSeconds(172800),
            LiableParty.PHARMACY,
            true,
            "txn_1",
            now,
            now,
            null);
    store.insert(withRefund);
    store.update(withRefund);
  }

  @Test
  @SuppressWarnings("unchecked")
  void cannedAndHelpStoresDelegate() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcCannedResponseStore canned = new JdbcCannedResponseStore(jdbc);
    JdbcHelpArticleStore help = new JdbcHelpArticleStore(jdbc);
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    UUID cid = UUID.randomUUID();
    UUID aid = UUID.randomUUID();
    CannedResponse cr =
        new CannedResponse(
            cid,
            "title",
            TicketCategory.ORDER,
            "Hi {customer_name}",
            "/wrong-items",
            0,
            null,
            UUID.randomUUID(),
            null,
            now,
            now);
    HelpArticle ha =
        new HelpArticle(
            aid,
            "How to track",
            TicketCategory.ORDER,
            "## track",
            List.of("order", "tracking"),
            true,
            0,
            0,
            UUID.randomUUID(),
            null,
            now,
            now);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(1);
    when(jdbc.update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(1);
    assertThat(canned.insert(cr)).isEqualTo(cr);
    assertThat(help.insert(ha)).isEqualTo(ha);

    ResultSet crs = mock(ResultSet.class);
    when(crs.getObject("id")).thenReturn(cid);
    when(crs.getString("title")).thenReturn(cr.title());
    when(crs.getString("category")).thenReturn("ORDER");
    when(crs.getString("body")).thenReturn(cr.body());
    when(crs.getString("shortcut_key")).thenReturn(cr.shortcutKey());
    when(crs.getInt("copy_count")).thenReturn(0);
    when(crs.getTimestamp("last_used_at")).thenReturn(null, Timestamp.from(now));
    when(crs.getObject("created_by")).thenReturn(cr.createdBy());
    when(crs.getTimestamp("deleted_at")).thenReturn(null, Timestamp.from(now));
    when(crs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(crs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

    org.mockito.Mockito.doAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(2);
              return List.of(mapper.mapRow(crs, 0));
            })
        .when(jdbc)
        .query(contains("support_canned_responses"), any(Object[].class), any(RowMapper.class));
    when(jdbc.query(contains("support_canned_responses"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(crs, 0));
            });

    when(jdbc.queryForObject(contains("COUNT(*) FROM support_canned_responses"), eq(Long.class)))
        .thenReturn(1L);
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM support_canned_responses"), eq(Long.class), any()))
        .thenReturn(1L);
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM support_canned_responses"),
            eq(Long.class),
            any(Object[].class)))
        .thenReturn(null, 1L);
    assertThat(canned.findById(cid)).isPresent();
    assertThat(canned.findByShortcut("/wrong-items")).isPresent();
    assertThat(
            canned.count(
                new com.nammamedmate.support.application.port.out.CannedResponseStore.ListFilter(
                    TicketCategory.ORDER, null, 0, 20)))
        .isEqualTo(0);
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM support_canned_responses"),
            eq(Long.class),
            any(Object[].class)))
        .thenReturn(1L);
    assertThat(
            canned.list(
                new com.nammamedmate.support.application.port.out.CannedResponseStore.ListFilter(
                    null, null, 0, 20)))
        .hasSize(1);
    assertThat(
            canned.list(
                new com.nammamedmate.support.application.port.out.CannedResponseStore.ListFilter(
                    TicketCategory.ORDER, "", 0, 20)))
        .hasSize(1);
    assertThat(
            canned.list(
                new com.nammamedmate.support.application.port.out.CannedResponseStore.ListFilter(
                    TicketCategory.ORDER, "wrong", 0, 20)))
        .hasSize(1);
    assertThat(
            canned.count(
                new com.nammamedmate.support.application.port.out.CannedResponseStore.ListFilter(
                    TicketCategory.ORDER, null, 0, 20)))
        .isEqualTo(1);
    canned.recordUsage(cid, now);
    canned.update(cr.withContent("t2", TicketCategory.PAYMENT, "b2", "/pay", now));
    canned.update(cr.softDeleted(now, now));

    ResultSet hrs = mock(ResultSet.class);
    when(hrs.getObject("id")).thenReturn(aid);
    when(hrs.getString("title")).thenReturn(ha.title());
    when(hrs.getString("category")).thenReturn("ORDER");
    when(hrs.getString("content_markdown")).thenReturn(ha.contentMarkdown());
    Array tags = mock(Array.class);
    when(tags.getArray()).thenReturn(new String[] {"order", "tracking"});
    when(hrs.getArray("tags")).thenReturn(tags);
    when(hrs.getBoolean("is_published")).thenReturn(true);
    when(hrs.getInt("view_count")).thenReturn(1);
    when(hrs.getInt("deflection_count")).thenReturn(2);
    when(hrs.getObject("created_by")).thenReturn(ha.createdBy());
    when(hrs.getTimestamp("deleted_at")).thenReturn(null, Timestamp.from(now));
    when(hrs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(hrs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

    org.mockito.Mockito.doAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(2);
              return List.of(mapper.mapRow(hrs, 0));
            })
        .when(jdbc)
        .query(contains("support_help_articles"), any(Object[].class), any(RowMapper.class));
    when(jdbc.query(contains("support_help_articles"), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(hrs, 0));
            });

    org.mockito.Mockito.doAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(2);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("category")).thenReturn("ORDER");
              when(rs.getLong("article_count")).thenReturn(1L);
              return List.of(mapper.mapRow(rs, 0));
            })
        .when(jdbc)
        .query(contains("GROUP BY category"), any(Object[].class), any(RowMapper.class));
    org.mockito.Mockito.doAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("category")).thenReturn("ORDER");
              when(rs.getLong("article_count")).thenReturn(1L);
              return List.of(mapper.mapRow(rs, 0));
            })
        .when(jdbc)
        .query(contains("GROUP BY category"), any(RowMapper.class));

    when(jdbc.queryForObject(contains("COUNT(*) FROM support_help_articles"), eq(Long.class)))
        .thenReturn(1L);
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM support_help_articles"), eq(Long.class), any()))
        .thenReturn(1L);
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM support_help_articles"), eq(Long.class), any(Object[].class)))
        .thenReturn(null, 1L);
    when(jdbc.queryForObject(contains("RETURNING view_count"), eq(Integer.class), any()))
        .thenReturn(null, 3);
    when(jdbc.queryForObject(contains("RETURNING deflection_count"), eq(Integer.class), any()))
        .thenReturn(null, 4);

    assertThat(help.findById(aid)).isPresent();
    assertThat(
            help.count(
                new com.nammamedmate.support.application.port.out.HelpArticleStore.ListFilter(
                    null, false, null, false, 0, 20)))
        .isEqualTo(0);
    when(jdbc.queryForObject(
            contains("COUNT(*) FROM support_help_articles"), eq(Long.class), any(Object[].class)))
        .thenReturn(1L);
    assertThat(help.incrementViewCount(aid)).isEqualTo(0);
    assertThat(help.incrementDeflectionCount(aid)).isEqualTo(0);
    when(jdbc.queryForObject(contains("RETURNING view_count"), eq(Integer.class), any()))
        .thenReturn(3);
    when(jdbc.queryForObject(contains("RETURNING deflection_count"), eq(Integer.class), any()))
        .thenReturn(4);
    assertThat(
            help.list(
                new com.nammamedmate.support.application.port.out.HelpArticleStore.ListFilter(
                    TicketCategory.ORDER, true, "track", true, 0, 20)))
        .hasSize(1);
    assertThat(
            help.list(
                new com.nammamedmate.support.application.port.out.HelpArticleStore.ListFilter(
                    null, false, null, false, 0, 20)))
        .hasSize(1);
    assertThat(
            help.list(
                new com.nammamedmate.support.application.port.out.HelpArticleStore.ListFilter(
                    null, null, null, false, 0, 20)))
        .hasSize(1);
    HelpArticle withDeleted =
        new HelpArticle(
            aid,
            "How to track",
            TicketCategory.ORDER,
            "## track",
            List.of(),
            true,
            0,
            0,
            ha.createdBy(),
            now,
            now,
            now);
    assertThat(help.insert(withDeleted)).isEqualTo(withDeleted);
    assertThat(
            help.count(
                new com.nammamedmate.support.application.port.out.HelpArticleStore.ListFilter(
                    null, false, null, false, 0, 20)))
        .isEqualTo(1);
    assertThat(help.publishedCategoryCounts(null)).hasSize(1);
    assertThat(help.publishedCategoryCounts("")).hasSize(1);
    assertThat(help.publishedCategoryCounts("track")).hasSize(1);
    assertThat(
            help.list(
                new com.nammamedmate.support.application.port.out.HelpArticleStore.ListFilter(
                    null, true, "", false, 0, 20)))
        .hasSize(1);
    assertThat(help.incrementViewCount(aid)).isEqualTo(3);
    assertThat(help.incrementDeflectionCount(aid)).isEqualTo(4);
    help.update(ha.withContent("t2", null, "md2", List.of("x"), true, now));

    Array objTags = mock(Array.class);
    when(objTags.getArray()).thenReturn(new Object[] {"a", null, 1});
    Array bad = mock(Array.class);
    when(bad.getArray()).thenReturn(42);
    org.mockito.Mockito.doAnswer(
            inv -> {
              RowMapper<Object> mapper = inv.getArgument(1);
              when(hrs.getArray("tags")).thenReturn(null);
              mapper.mapRow(hrs, 0);
              when(hrs.getArray("tags")).thenReturn(objTags);
              mapper.mapRow(hrs, 0);
              when(hrs.getArray("tags")).thenReturn(bad);
              return List.of(mapper.mapRow(hrs, 0));
            })
        .when(jdbc)
        .query(contains("WHERE id = ? AND deleted_at IS NULL"), any(RowMapper.class), any());
    assertThat(help.findById(aid)).isPresent();

    when(jdbc.update(contains("INSERT INTO support_canned_responses"), any(Object[].class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
    assertThatThrownBy(() -> canned.insert(cr))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SHORTCUT_KEY_EXISTS");
    when(jdbc.update(
            contains("UPDATE support_canned_responses"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
    assertThatThrownBy(() -> canned.update(cr))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SHORTCUT_KEY_EXISTS");
    when(jdbc.update(
            contains("UPDATE support_canned_responses"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(0);
    assertThatThrownBy(() -> canned.update(cr))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CANNED_RESPONSE_NOT_FOUND");
    when(jdbc.update(
            contains("UPDATE support_help_articles"),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(0);
    assertThatThrownBy(() -> help.update(ha))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");
  }

  private static ResultSet mockDisputeRs(Dispute d) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(d.id());
    when(rs.getString("dispute_id")).thenReturn(d.disputeId());
    when(rs.getObject("order_id")).thenReturn(d.orderId());
    when(rs.getObject("customer_id")).thenReturn(d.customerId());
    when(rs.getString("dispute_type")).thenReturn(d.disputeType().name());
    when(rs.getString("description")).thenReturn(d.description());
    when(rs.getArray("evidence_urls")).thenReturn(null);
    when(rs.getString("status")).thenReturn(d.status().name());
    when(rs.getString("liable_party")).thenReturn(null);
    when(rs.getObject("refund_amount_paise")).thenReturn(null);
    when(rs.getString("refund_to")).thenReturn(null);
    when(rs.getString("resolution_notes")).thenReturn(null);
    when(rs.getString("rejection_reason")).thenReturn(null);
    when(rs.getObject("investigated_by")).thenReturn(null);
    when(rs.getTimestamp("resolved_at")).thenReturn(null);
    when(rs.getTimestamp("resolution_sla_at")).thenReturn(Timestamp.from(d.resolutionSlaAt()));
    when(rs.getString("recommended_liable_party")).thenReturn(d.recommendedLiableParty().name());
    when(rs.getBoolean("auto_processed")).thenReturn(false);
    when(rs.getString("refund_txn_id")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(d.createdAt()));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(d.updatedAt()));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }
}
