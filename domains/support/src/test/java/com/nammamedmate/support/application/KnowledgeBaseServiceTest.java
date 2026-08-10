package com.nammamedmate.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.SlaServiceTest.FakeSlaPolicyStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeAgentStore;
import com.nammamedmate.support.application.TicketServiceTest.FakeCustomers;
import com.nammamedmate.support.application.TicketServiceTest.FakeNotifications;
import com.nammamedmate.support.application.TicketServiceTest.FakeTicketStore;
import com.nammamedmate.support.application.port.out.CannedResponseStore;
import com.nammamedmate.support.application.port.out.HelpArticleStore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeBaseServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID SUPER = UUID.fromString("a1500005-0001-4000-8000-000000000001");
  private static final UUID SUPPORT = UUID.fromString("a1500005-0001-4000-8000-000000000002");
  private static final UUID CUST = UUID.fromString("c1500005-0001-4000-8000-000000000001");
  private static final UUID ORDER = UUID.fromString("01500005-0001-4000-8000-000000000001");

  private FakeCanned canned;
  private FakeArticles articles;
  private KnowledgeBaseService kb;
  private InMemoryRateLimiter limiter;

  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(SUPER, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal supportAdmin =
      new MedmatePrincipal(SUPPORT, AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(CUST, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    canned = new FakeCanned();
    articles = new FakeArticles();
    limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    kb = new KnowledgeBaseService(canned, articles, limiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_createCannedSearchableByShortcut() {
    Map<String, Object> created =
        kb.createCanned(
            superAdmin,
            new KnowledgeBaseService.CannedCreateCommand(
                "Payment refund - processing time",
                "PAYMENT",
                "Hi {customer_name}, refund for {order_id}",
                "/refund-processing"));
    assertThat(created.get("shortcut_key")).isEqualTo("/refund-processing");
    KnowledgeBaseService.ListResult listed =
        kb.listCanned(supportAdmin, null, "/refund-processing", 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) listed.data().get("canned_responses");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("title")).isEqualTo("Payment refund - processing time");
  }

  @Test
  void ac002_duplicateShortcutReturns409() {
    kb.createCanned(
        superAdmin,
        new KnowledgeBaseService.CannedCreateCommand("A", "ORDER", "body", "/wrong-items"));
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand(
                        "B", "ORDER", "body2", "/wrong-items")))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SHORTCUT_KEY_EXISTS");
  }

  @Test
  void ac003_interpolateOnTicketReply() {
    Map<String, Object> created =
        kb.createCanned(
            superAdmin,
            new KnowledgeBaseService.CannedCreateCommand(
                "Wrong items",
                "ORDER",
                "Hi {customer_name}, order {order_id} ticket {ticket_id} refund {refund_amount} at {pharmacy_name}. Missing {customer_name}",
                "/wrong-items"));
    UUID cannedId = (UUID) created.get("id");

    FakeTicketStore tickets = new FakeTicketStore();
    FakeAgentStore agents = new FakeAgentStore();
    agents.put(new AgentProfile(SUPPORT, List.of("ORDER"), true, 20, "Ravi", NOW));
    FakeNotifications notifications = new FakeNotifications();
    FakeSlaPolicyStore policies = new FakeSlaPolicyStore();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    AgentService agentService = new AgentService(agents, tickets, notifications, clock);
    TicketService ticketsSvc =
        new TicketService(
            tickets,
            agents,
            new FakeCustomers(),
            notifications,
            policies,
            canned,
            orderId ->
                Optional.of(
                    new OrderContextPort.OrderContext(
                        orderId,
                        CUST,
                        "DELIVERED",
                        9600L,
                        List.of(),
                        "Stub Pharmacy",
                        "Rider",
                        "https://t")),
            clock,
            agentService);

    Ticket ticket =
        new Ticket(
            UUID.randomUUID(),
            "TKT-20260724-000042",
            CUST,
            null,
            ORDER,
            TicketCategory.ORDER,
            "Wrong items",
            TicketStatus.OPEN,
            TicketPriority.HIGH,
            SlaLevel.L1,
            NOW.plusSeconds(1800),
            NOW.plusSeconds(1800),
            NOW.plusSeconds(1800),
            SUPPORT,
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

    ticketsSvc.reply(
        supportAdmin,
        ticket.id(),
        new TicketService.ReplyCommand(null, false, List.of(), cannedId));
    assertThat(tickets.listMessages(ticket.id()).getFirst().message())
        .isEqualTo(
            "Hi Priya Sharma, order "
                + ORDER
                + " ticket TKT-20260724-000042 refund ₹96.00 at Stub Pharmacy. Missing Priya Sharma");
    assertThat(canned.findById(cannedId).orElseThrow().copyCount()).isEqualTo(1);
    assertThat(canned.findById(cannedId).orElseThrow().lastUsedAt()).isEqualTo(NOW);
  }

  @Test
  void ac004_publicHelpExcludesUnpublished() {
    kb.createArticle(
        superAdmin,
        new KnowledgeBaseService.ArticleCreateCommand(
            "Published", "ORDER", "## track", List.of("order"), true));
    kb.createArticle(
        superAdmin,
        new KnowledgeBaseService.ArticleCreateCommand(
            "Draft", "ORDER", "## draft", List.of(), false));
    KnowledgeBaseService.ListResult pub = kb.publicHelp(null, null, "1.1.1.1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) pub.data().get("articles");
    assertThat(rows).extracting(m -> m.get("title")).containsExactly("Published");
  }

  @Test
  void ac005_viewCountIncrements() {
    Map<String, Object> created =
        kb.createArticle(
            superAdmin,
            new KnowledgeBaseService.ArticleCreateCommand(
                "How to track my order",
                "ORDER",
                "## How to track your order\n\nOnce confirmed...",
                List.of("order", "tracking"),
                true));
    UUID id = (UUID) created.get("id");
    Map<String, Object> first = kb.readPublicArticle(id, "2.2.2.2");
    assertThat(first.get("view_count")).isEqualTo(1);
    Map<String, Object> second = kb.readPublicArticle(id, "2.2.2.2");
    assertThat(second.get("view_count")).isEqualTo(2);
  }

  @Test
  void ac006_deflectionIncrementsWhenResolved() {
    Map<String, Object> created =
        kb.createArticle(
            superAdmin,
            new KnowledgeBaseService.ArticleCreateCommand(
                "Cancel", "ORDER", "steps", List.of("cancel"), true));
    UUID id = (UUID) created.get("id");
    Map<String, Object> logged = kb.logDeflection(customer, id, true, "3.3.3.3");
    assertThat(logged.get("deflection_logged")).isEqualTo(true);
    assertThat(articles.findById(id).orElseThrow().deflectionCount()).isEqualTo(1);
    kb.logDeflection(null, id, false, "3.3.3.3");
    assertThat(articles.findById(id).orElseThrow().deflectionCount()).isEqualTo(1);
  }

  @Test
  void ac007_adminListSortsByDeflectionDesc() {
    UUID low =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "Low", "ORDER", "a", List.of(), true))
                .get("id");
    UUID high =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "High", "ORDER", "b", List.of(), true))
                .get("id");
    articles.bumpDeflection(high, 10);
    articles.bumpDeflection(low, 1);
    KnowledgeBaseService.ListResult listed = kb.listArticlesAdmin(superAdmin, null, null, 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.data().get("articles");
    assertThat(rows.getFirst().get("id")).isEqualTo(high);
    assertThat(rows.get(1).get("id")).isEqualTo(low);
  }

  @Test
  void ac008_keywordSearchTrack() {
    kb.createArticle(
        superAdmin,
        new KnowledgeBaseService.ArticleCreateCommand(
            "How to track my order",
            "ORDER",
            "Learn how to track your delivery",
            List.of("tracking"),
            true));
    kb.createArticle(
        superAdmin,
        new KnowledgeBaseService.ArticleCreateCommand(
            "Refund policy", "PAYMENT", "refunds", List.of(), true));
    KnowledgeBaseService.ListResult listed = kb.publicHelp(null, "track", "4.4.4.4");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.data().get("articles");
    assertThat(rows).extracting(m -> m.get("title")).containsExactly("How to track my order");
  }

  @Test
  void ac009_adminSupportCannotCreateOrDelete() {
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    supportAdmin,
                    new KnowledgeBaseService.CannedCreateCommand("x", "ORDER", "b", "/x")))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    Map<String, Object> created =
        kb.createCanned(
            superAdmin, new KnowledgeBaseService.CannedCreateCommand("ok", "ORDER", "b", "/ok"));
    UUID id = (UUID) created.get("id");
    assertThatThrownBy(() -> kb.deleteCanned(supportAdmin, id))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThat(kb.listCanned(supportAdmin, null, null, 1, 20).meta().total()).isEqualTo(1);
  }

  @Test
  void ac010_orderCategoryPreferredFirstOnSearch() {
    kb.createCanned(
        superAdmin,
        new KnowledgeBaseService.CannedCreateCommand(
            "Payment apology", "PAYMENT", "pay body", "/pay-apology"));
    kb.createCanned(
        superAdmin,
        new KnowledgeBaseService.CannedCreateCommand(
            "Order apology", "ORDER", "order body", "/order-apology"));
    KnowledgeBaseService.ListResult listed = kb.listCanned(supportAdmin, "ORDER", "apology", 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) listed.data().get("canned_responses");
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().get("category")).isEqualTo("ORDER");
  }

  @Test
  void invalidTemplateVariable422AndShortcutExistsOnUpdate() {
    assertThatThrownBy(
            () ->
                kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand(
                        "x", "ORDER", "Hi {unknown_var}", "/u")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_TEMPLATE_VARIABLE");
    UUID a =
        (UUID)
            kb.createCanned(
                    superAdmin,
                    new KnowledgeBaseService.CannedCreateCommand("A", "ORDER", "a", "/a"))
                .get("id");
    kb.createCanned(
        superAdmin, new KnowledgeBaseService.CannedCreateCommand("B", "ORDER", "b", "/b"));
    assertThatThrownBy(
            () ->
                kb.updateCanned(
                    superAdmin,
                    a,
                    new KnowledgeBaseService.CannedUpdateCommand(null, null, null, "/b")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SHORTCUT_KEY_EXISTS");
    kb.updateCanned(
        superAdmin,
        a,
        new KnowledgeBaseService.CannedUpdateCommand("A2", "PAYMENT", "Hi {order_id}", "/a2"));
    kb.deleteCanned(superAdmin, a);
    assertThat(canned.findById(a)).isEmpty();
  }

  @Test
  void articleUpdateAndRateLimitAndNotFound() {
    UUID id =
        (UUID)
            kb.createArticle(
                    superAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand(
                        "T", "ACCOUNT", "long content here", List.of("x"), false))
                .get("id");
    kb.updateArticle(
        superAdmin,
        id,
        new KnowledgeBaseService.ArticleUpdateCommand(
            "T2", "ACCOUNT", "updated", List.of("y"), true));
    assertThatThrownBy(() -> kb.readPublicArticle(UUID.randomUUID(), "5.5.5.5"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("HELP_ARTICLE_NOT_FOUND");
    assertThatThrownBy(
            () ->
                kb.createArticle(
                    supportAdmin,
                    new KnowledgeBaseService.ArticleCreateCommand("x", "ORDER", "c", null, true)))
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    for (int i = 0; i < 60; i++) {
      kb.publicHelp(null, null, "rate-ip");
    }
    assertThatThrownBy(() -> kb.publicHelp(null, null, "rate-ip"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void cannedTemplateHelpers() {
    assertThat(CannedTemplate.formatRefundPaise(null)).isNull();
    assertThat(CannedTemplate.formatRefundPaise(5L)).isEqualTo("₹0.05");
    assertThat(CannedTemplate.interpolate(null, Map.of())).isNull();
    assertThat(CannedTemplate.interpolate("", Map.of())).isEmpty();
    assertThat(CannedTemplate.interpolate("Hi {customer_name}", Map.of()))
        .isEqualTo("Hi {customer_name}");
    CannedTemplate.validate(null);
  }

  static final class FakeCanned implements CannedResponseStore {
    private final Map<UUID, CannedResponse> byId = new HashMap<>();

    @Override
    public CannedResponse insert(CannedResponse row) {
      if (findByShortcut(row.shortcutKey()).isPresent()) {
        throw new AppException("SHORTCUT_KEY_EXISTS", "Shortcut key already used", 409);
      }
      byId.put(row.id(), row);
      return row;
    }

    @Override
    public CannedResponse update(CannedResponse row) {
      CannedResponse existing = byId.get(row.id());
      if (existing == null || existing.deletedAt() != null) {
        throw new AppException("CANNED_RESPONSE_NOT_FOUND", "Canned response not found", 404);
      }
      Optional<CannedResponse> clash = findByShortcut(row.shortcutKey());
      if (clash.isPresent() && !clash.get().id().equals(row.id())) {
        throw new AppException("SHORTCUT_KEY_EXISTS", "Shortcut key already used", 409);
      }
      byId.put(row.id(), row);
      return row;
    }

    @Override
    public Optional<CannedResponse> findById(UUID id) {
      CannedResponse r = byId.get(id);
      return r == null || r.deletedAt() != null ? Optional.empty() : Optional.of(r);
    }

    @Override
    public Optional<CannedResponse> findByShortcut(String shortcutKey) {
      return byId.values().stream()
          .filter(r -> r.deletedAt() == null && r.shortcutKey().equals(shortcutKey))
          .findFirst();
    }

    @Override
    public List<CannedResponse> list(ListFilter filter) {
      return byId.values().stream()
          .filter(r -> r.deletedAt() == null)
          .filter(
              r ->
                  filter.category() == null
                      || (filter.q() != null && !filter.q().isBlank())
                      || r.category() == filter.category())
          .filter(
              r -> {
                if (filter.q() == null || filter.q().isBlank()) {
                  return true;
                }
                String q = filter.q().toLowerCase();
                return r.title().toLowerCase().contains(q)
                    || r.body().toLowerCase().contains(q)
                    || r.shortcutKey().toLowerCase().contains(q);
              })
          .sorted(
              (a, b) -> {
                if (filter.category() != null) {
                  int pa = a.category() == filter.category() ? 0 : 1;
                  int pb = b.category() == filter.category() ? 0 : 1;
                  if (pa != pb) {
                    return Integer.compare(pa, pb);
                  }
                }
                return a.title().compareTo(b.title());
              })
          .skip(filter.offset())
          .limit(filter.limit())
          .collect(Collectors.toList());
    }

    @Override
    public long count(ListFilter filter) {
      return list(new ListFilter(filter.category(), filter.q(), 0, Integer.MAX_VALUE)).size();
    }

    @Override
    public void recordUsage(UUID id, Instant usedAt) {
      CannedResponse r = byId.get(id);
      if (r != null) {
        byId.put(id, r.withUsage(r.copyCount() + 1, usedAt, usedAt));
      }
    }
  }

  static class FakeArticles implements HelpArticleStore {
    private final Map<UUID, HelpArticle> byId = new HashMap<>();

    @Override
    public HelpArticle insert(HelpArticle row) {
      byId.put(row.id(), row);
      return row;
    }

    @Override
    public HelpArticle update(HelpArticle row) {
      if (!byId.containsKey(row.id()) || byId.get(row.id()).deletedAt() != null) {
        throw new AppException("HELP_ARTICLE_NOT_FOUND", "Help article not found", 404);
      }
      byId.put(row.id(), row);
      return row;
    }

    @Override
    public Optional<HelpArticle> findById(UUID id) {
      HelpArticle a = byId.get(id);
      return a == null || a.deletedAt() != null ? Optional.empty() : Optional.of(a);
    }

    @Override
    public List<HelpArticle> list(ListFilter filter) {
      Comparator<HelpArticle> cmp =
          filter.publicOnly()
              ? Comparator.comparing(HelpArticle::title)
              : Comparator.comparingInt(HelpArticle::deflectionCount)
                  .reversed()
                  .thenComparing(HelpArticle::title);
      return byId.values().stream()
          .filter(a -> a.deletedAt() == null)
          .filter(a -> !filter.publicOnly() || a.published())
          .filter(a -> filter.category() == null || a.category() == filter.category())
          .filter(
              a ->
                  filter.published() == null
                      || filter.publicOnly()
                      || a.published() == filter.published())
          .filter(
              a -> {
                if (filter.q() == null || filter.q().isBlank()) {
                  return true;
                }
                String q = filter.q().toLowerCase();
                return a.title().toLowerCase().contains(q)
                    || a.contentMarkdown().toLowerCase().contains(q)
                    || a.tags().stream().anyMatch(t -> t.toLowerCase().contains(q));
              })
          .sorted(cmp)
          .skip(filter.offset())
          .limit(filter.limit())
          .collect(Collectors.toList());
    }

    @Override
    public long count(ListFilter filter) {
      return list(new ListFilter(
              filter.category(),
              filter.published(),
              filter.q(),
              filter.publicOnly(),
              0,
              Integer.MAX_VALUE))
          .size();
    }

    @Override
    public List<CategoryCount> publishedCategoryCounts(String q) {
      Map<String, AtomicInteger> counts = new HashMap<>();
      for (HelpArticle a : list(new ListFilter(null, true, q, true, 0, Integer.MAX_VALUE))) {
        counts.computeIfAbsent(a.category().name(), k -> new AtomicInteger()).incrementAndGet();
      }
      List<CategoryCount> out = new ArrayList<>();
      counts.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(e -> out.add(new CategoryCount(e.getKey(), e.getValue().get())));
      return out;
    }

    @Override
    public int incrementViewCount(UUID id) {
      HelpArticle a = byId.get(id);
      if (a == null || !a.published()) {
        return 0;
      }
      HelpArticle next = a.withViewCount(a.viewCount() + 1);
      byId.put(id, next);
      return next.viewCount();
    }

    @Override
    public int incrementDeflectionCount(UUID id) {
      HelpArticle a = byId.get(id);
      if (a == null || !a.published()) {
        return 0;
      }
      HelpArticle next = a.withDeflectionCount(a.deflectionCount() + 1);
      byId.put(id, next);
      return next.deflectionCount();
    }

    void bumpDeflection(UUID id, int n) {
      HelpArticle a = byId.get(id);
      byId.put(id, a.withDeflectionCount(n));
    }
  }
}
