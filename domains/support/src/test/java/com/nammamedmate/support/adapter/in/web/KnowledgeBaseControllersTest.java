package com.nammamedmate.support.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.KnowledgeBaseService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseControllersTest {

  @Mock KnowledgeBaseService kb;
  @InjectMocks AdminSupportCannedController canned;
  @InjectMocks AdminSupportHelpArticleController articles;
  @InjectMocks PublicHelpController help;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @Test
  void cannedEndpointsDelegate() {
    when(kb.listCanned(any(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new KnowledgeBaseService.ListResult(
                Map.of("canned_responses", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(canned.list(principal, null, null, null, null).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    UUID id = UUID.randomUUID();
    when(kb.createCanned(any(), any())).thenReturn(Map.of("id", id));
    assertThat(canned.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            canned
                .create(
                    principal,
                    new AdminSupportCannedController.CreateRequest("t", "ORDER", "b", "/t"))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(kb.updateCanned(any(), eq(id), any())).thenReturn(Map.of("id", id));
    assertThat(canned.update(principal, id, null).data().get("id")).isEqualTo(id);
    assertThat(
            canned
                .update(
                    principal,
                    id,
                    new AdminSupportCannedController.UpdateRequest("t2", null, "b2", null))
                .data()
                .get("id"))
        .isEqualTo(id);

    when(kb.deleteCanned(any(), eq(id))).thenReturn(Map.of("id", id, "deleted", true));
    assertThat(canned.delete(principal, id).data().get("deleted")).isEqualTo(true);
  }

  @Test
  void articleEndpointsDelegate() {
    when(kb.listArticlesAdmin(any(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new KnowledgeBaseService.ListResult(
                Map.of("articles", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(articles.list(principal, null, null, null, null).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    UUID id = UUID.randomUUID();
    when(kb.createArticle(any(), any())).thenReturn(Map.of("id", id));
    assertThat(articles.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            articles
                .create(
                    principal,
                    new AdminSupportHelpArticleController.CreateRequest(
                        "t", "ORDER", "md", List.of("a"), true))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    when(kb.updateArticle(any(), eq(id), any())).thenReturn(Map.of("id", id));
    assertThat(articles.update(principal, id, null).data().get("id")).isEqualTo(id);
    assertThat(
            articles
                .update(
                    principal,
                    id,
                    new AdminSupportHelpArticleController.UpdateRequest(
                        null, null, "md2", null, true))
                .data()
                .get("id"))
        .isEqualTo(id);
  }

  @Test
  void publicHelpEndpointsDelegate() {
    when(kb.publicHelp(isNull(), isNull(), any()))
        .thenReturn(
            new KnowledgeBaseService.ListResult(
                Map.of("articles", List.of(), "categories", List.of()),
                PaginationMeta.of(1, 100, 0)));
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.0.0.1");
    assertThat(help.list(null, null, req).success()).isTrue();

    req.addHeader("X-Forwarded-For", "9.9.9.9, 8.8.8.8");
    UUID id = UUID.randomUUID();
    when(kb.readPublicArticle(eq(id), eq("9.9.9.9"))).thenReturn(Map.of("id", id));
    assertThat(help.get(id, req).data().get("id")).isEqualTo(id);

    when(kb.logDeflection(any(), eq(id), eq(true), any()))
        .thenReturn(Map.of("deflection_logged", true));
    assertThat(
            help.deflection(principal, new PublicHelpController.DeflectionRequest(id, true), req)
                .data()
                .get("deflection_logged"))
        .isEqualTo(true);
    when(kb.logDeflection(isNull(), isNull(), isNull(), any()))
        .thenReturn(Map.of("deflection_logged", false));
    assertThat(help.deflection(null, null, req).success()).isTrue();
  }
}
