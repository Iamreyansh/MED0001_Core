package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.adapter.in.web.PublicHelpController;
import com.nammamedmate.support.application.SlaServiceTest.FakeSlaPolicyStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeAgentStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeCustomers;
import com.nammamedmate.support.application.TicketServiceTest.FakeNotifications;
import com.nammamedmate.support.application.TicketServiceTest.FakeTicketStore;
import com.nammamedmate.support.application.port.out.OrderContextPort;
import com.nammamedmate.support.domain.AgentProfile;
import com.nammamedmate.support.domain.CannedResponse;
import com.nammamedmate.support.domain.HelpArticle;
import com.nammamedmate.support.domain.SlaLevel;
import com.nammamedmate.support.domain.Ticket;
import com.nammamedmate.support.domain.TicketCategory;
import com.nammamedmate.support.domain.TicketChannel;
import com.nammamedmate.support.domain.TicketPriority;
import com.nammamedmate.support.domain.TicketStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class KnowledgeBaseCoverageFillTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID SUPER = UUID.fromString("a1500005-0002-4000-8000-000000000001");
  private static final UUID CUST = UUID.fromString("c1500005-0002-4000-8000-000000000001");

  private KnowledgeBaseServiceTest.FakeCanned canned;
  private KnowledgeBaseServiceTest.FakeArticles articles;
  private KnowledgeBaseService kb;
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    canned = new KnowledgeBaseServiceTest.FakeCanned();
    articles = new KnowledgeBaseServiceTest.FakeArticles();
    kb =
        new KnowledgeBaseService(
            canned,
            articles,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void validationBranchesAndHelpers() {
    assertThatThrownBy(() -> kb.createCanned(superAdmin, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> kb.createArticle(superAdmin, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand("t", "NOPE", "b", "/x")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand("t", "ORDER", "b", null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand(
                        "t", "ORDER", "b", "/" + "x".repeat(60))))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand("t", null, "b", "/ok")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    UUID id =
        (UUID)
            kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand(
                        "t", "ORDER", "Hi {customer_name}", "NoSlash"))
                .get("id");
    assertThat(canned.findById(id).orElseThrow().shortcutKey()).isEqualTo("/noslash");

    kb.updateCanned(superAdmin, id, null);
    kb.updateCanned(
        superAdmin, id, new KnowledgeBaseService.CannedUpdateCommand(" ", "  ", null, null));
    assertThatThrownBy(() -> kb.updateCanned(superAdmin, UUID.randomUUID(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CANNED_RESPONSE_NOT_FOUND");
    assertThatThrownBy(() -> kb.deleteCanned(superAdmin, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CANNED_RESPONSE_NOT_FOUND");

    UUID art =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "Long", "ORDER", "# ".repeat(80) + "\n" + "word ".repeat(40), null, true))
                .get("id");
    kb.updateArticle(superAdmin, art, null);
    kb.updateArticle(
        superAdmin,
        art,
        new KnowledgeBaseService.ArticleUpdateCommand(" ", "  ", null, null, null));
    assertThatThrownBy(() -> kb.updateArticle(superAdmin, UUID.randomUUID(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");
    assertThatThrownBy(
            () ->
                kb.updateArticle(
                    superAdmin,
                    art,
                    new KnowledgeBaseService.ArticleUpdateCommand(null, "BAD", null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // unpublished / missing article public paths
    UUID draft =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "Draft", "ACCOUNT", "x", List.of(), false))
                .get("id");
    assertThatThrownBy(() -> kb.readPublicArticle(draft, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");
    assertThatThrownBy(() -> kb.logDeflection(null, draft, true, " "))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");
    assertThatThrownBy(() -> kb.logDeflection(null, null, true, "1.2.3.4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> kb.logDeflection(null, UUID.randomUUID(), true, "1.2.3.4"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");
    assertThatThrownBy(() -> kb.updateArticle(superAdmin, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");

    // force view_count fallback when increment returns 0
    UUID pub =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "Pub", "ORDER", "content", List.of("t"), true))
                .get("id");
    articles.bumpDeflection(pub, 0); // no-op keep
    // wrap articles temporarily? use FakeArticles increment returning 0 by unpublished trick:
    // manually set published false then read fails; instead stub via subclass:
    KnowledgeBaseServiceTest.FakeArticles zeroViews =
        new KnowledgeBaseServiceTest.FakeArticles() {
          @Override
          public int incrementViewCount(UUID id) {
            return 0;
          }
        };
    zeroViews.insert(articles.findById(pub).orElseThrow());
    KnowledgeBaseService kb2 =
        new KnowledgeBaseService(
            canned,
            zeroViews,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(kb2.readPublicArticle(pub, "1.1.1.1").get("view_count")).isEqualTo(1);

    kb.publicHelp("ORDER", null, null);
    // create PAYMENT published so ORDER filter skips it in categories
    kb.createArticle(
        superAdmin,
        new KnowledgeBaseService.ArticleCreateCommand("Pay", "PAYMENT", "pay", List.of(), true));
    kb.publicHelp("ORDER", null, "9.9.9.9");
    kb.publicHelp("PAYMENT", "track", " ");

    // summary blank + short
    UUID shortArt =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "S", "OTHER", "ok", List.of(), true))
                .get("id");
    articles.update(
        articles.findById(shortArt).orElseThrow().withContent(null, null, "", null, true, NOW));
    kb.publicHelp(null, null, "8.8.8.8");

    assertThat(CannedTemplate.context(null, null, null, null, null)).isEmpty();
    assertThat(CannedTemplate.context(" ", " ", " ", " ", " ")).isEmpty();
    assertThat(CannedTemplate.context("a", "b", "c", "d", "e"))
        .containsKeys("customer_name", "order_id", "refund_amount", "pharmacy_name", "ticket_id");
    assertThat(CannedTemplate.interpolate("x", null)).isEqualTo("x");
    assertThat(CannedTemplate.interpolate("Hi {customer_name}", Map.of("customer_name", " ")))
        .isEqualTo("Hi {customer_name}");
    assertThat(CannedTemplate.formatRefundPaise(105L)).isEqualTo("₹1.05");
    assertThat(CannedTemplate.formatRefundPaise(115L)).isEqualTo("₹1.15");

    CannedResponse cr =
        new CannedResponse(
            UUID.randomUUID(),
            "t",
            TicketCategory.ORDER,
            "b",
            "/z",
            0,
            null,
            SUPER,
            null,
            NOW,
            NOW);
    assertThat(cr.withContent(null, null, null, null, NOW).title()).isEqualTo("t");

    HelpArticle ha =
        new HelpArticle(
            UUID.randomUUID(),
            "t",
            TicketCategory.ORDER,
            "c",
            null,
            false,
            0,
            0,
            SUPER,
            null,
            NOW,
            NOW);
    assertThat(ha.tags()).isEmpty();
    assertThat(ha.withContent(null, null, null, null, null, NOW).title()).isEqualTo("t");
    articles.update(
        articles.findById(shortArt).orElseThrow().withContent(null, null, null, null, true, NOW));
    // null markdown summary
    articles.update(
        new HelpArticle(
            shortArt,
            "S",
            TicketCategory.OTHER,
            null,
            List.of(),
            true,
            0,
            0,
            SUPER,
            null,
            NOW,
            NOW));
    kb.publicHelp(null, null, "7.7.7.7");

    assertThatThrownBy(() -> kb.listCanned(null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(
            () ->
                kb.listCanned(
                    new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    null,
                    null,
                    1,
                    20))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);

    kb.publicHelp("  ", null, "6.6.6.6");
    kb.listCanned(superAdmin, "  ", "", 1, 20);
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand(
                        "  ", "ORDER", "b", "/blanktitle")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    PublicHelpController ctrl = new PublicHelpController(kb);
    assertThat(ctrl.list(null, null, null).success()).isTrue();
    MockHttpServletRequest blank = new MockHttpServletRequest();
    blank.setRemoteAddr("");
    assertThat(ctrl.list(null, null, blank).success()).isTrue();
    MockHttpServletRequest xffBlank = new MockHttpServletRequest();
    xffBlank.addHeader("X-Forwarded-For", " ");
    xffBlank.setRemoteAddr("127.0.0.1");
    assertThat(ctrl.list(null, null, xffBlank).success()).isTrue();
    jakarta.servlet.http.HttpServletRequest nullRemote =
        org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
    org.mockito.Mockito.when(nullRemote.getHeader("X-Forwarded-For")).thenReturn(null);
    org.mockito.Mockito.when(nullRemote.getRemoteAddr()).thenReturn(null);
    assertThat(ctrl.list(null, null, nullRemote).success()).isTrue();

    var updateReq =
        new com.nammamedmate.support.adapter.in.web.AdminSupportHelpArticleController.UpdateRequest(
            null, null, null, null, null);
    assertThat(updateReq.tags()).isNull();
    var updateReq2 =
        new com.nammamedmate.support.adapter.in.web.AdminSupportHelpArticleController.UpdateRequest(
            null, null, null, List.of("a"), null);
    assertThat(updateReq2.tags()).containsExactly("a");
  }

  @Test
  void ticketReplyCannedBranches() {
    KnowledgeBaseServiceTest.FakeCanned store = new KnowledgeBaseServiceTest.FakeCanned();
    UUID cannedId = UUID.randomUUID();
    store.insert(
        new CannedResponse(
            cannedId,
            "t",
            TicketCategory.ORDER,
            "Hi {customer_name} {order_id} {refund_amount} {pharmacy_name} {ticket_id}",
            "/x",
            0,
            null,
            SUPER,
            null,
            NOW,
            NOW));
    FakeTicketStore tickets = new FakeTicketStore();
    FakeAgentStore agents = new FakeAgentStore();
    agents.put(new AgentProfile(SUPER, List.of(), true, 20, "A", NOW));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    TicketService svc =
        new TicketService(
            tickets,
            agents,
            new FakeCustomers(),
            new FakeNotifications(),
            new FakeSlaPolicyStore(),
            store,
            orderId -> Optional.empty(),
            clock,
            new AgentService(agents, tickets, new FakeNotifications(), clock));
    Ticket ticket =
        new Ticket(
            UUID.randomUUID(),
            "TKT-20260724-000099",
            CUST,
            null,
            null,
            TicketCategory.ORDER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L1,
            NOW.plusSeconds(60),
            NOW.plusSeconds(60),
            NOW.plusSeconds(60),
            SUPER,
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
            NOW,
            NOW);
    tickets.insert(ticket);
    MedmatePrincipal admin =
        new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    // canned only (no freeform) + missing order context leaves placeholders
    svc.reply(admin, ticket.id(), new TicketService.ReplyCommand("  ", false, List.of(), cannedId));
    assertThat(tickets.listMessages(ticket.id()).getFirst().message()).contains("{order_id}");
    // append freeform
    svc.reply(
        admin, ticket.id(), new TicketService.ReplyCommand("extra", false, List.of(), cannedId));
    assertThat(tickets.listMessages(ticket.id()).getLast().message()).endsWith("extra");
    assertThatThrownBy(
            () ->
                svc.reply(
                    admin,
                    ticket.id(),
                    new TicketService.ReplyCommand("x", false, List.of(), UUID.randomUUID())))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CANNED_RESPONSE_NOT_FOUND");

    // with order context
    TicketService svc2 =
        new TicketService(
            tickets,
            agents,
            new FakeCustomers(),
            new FakeNotifications(),
            new FakeSlaPolicyStore(),
            store,
            orderId ->
                Optional.of(
                    new OrderContextPort.OrderContext(
                        orderId, CUST, "DELIVERED", 5L, List.of(), "P", "R", "u")),
            clock,
            new AgentService(agents, tickets, new FakeNotifications(), clock));
    Ticket withOrder =
        new Ticket(
            UUID.randomUUID(),
            "TKT-20260724-000100",
            CUST,
            null,
            UUID.randomUUID(),
            TicketCategory.ORDER,
            "s",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L1,
            NOW.plusSeconds(60),
            NOW.plusSeconds(60),
            NOW.plusSeconds(60),
            SUPER,
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
            NOW,
            NOW);
    tickets.insert(withOrder);
    svc2.reply(
        admin, withOrder.id(), new TicketService.ReplyCommand(null, false, List.of(), cannedId));
    assertThat(tickets.listMessages(withOrder.id()).getFirst().message()).contains("₹0.05");
  }
}
