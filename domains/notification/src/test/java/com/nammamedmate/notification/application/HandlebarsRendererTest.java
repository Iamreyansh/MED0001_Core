package com.nammamedmate.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HandlebarsRendererTest {

  @Test
  void rendersVarsNestedIfAndUndefinedEmpty() {
    String tpl =
        "Hi {{customer_name}} order {{order.id}} {{missing}} {{#if show}}YES{{/if}}{{#if hide}}NO{{/if}}";
    String out =
        HandlebarsRenderer.render(
            tpl,
            Map.of(
                "customer_name",
                "Ravi",
                "order",
                Map.of("id", "ORD-1"),
                "show",
                true,
                "hide",
                false));
    assertThat(out).isEqualTo("Hi Ravi order ORD-1  YES");
    assertThat(HandlebarsRenderer.render(null, null)).isEmpty();
    assertThat(HandlebarsRenderer.render("{{x}}", null)).isEmpty();
  }

  @Test
  void truthyAndResolveEdgeCases() {
    assertThat(HandlebarsRenderer.isTruthy(null)).isFalse();
    assertThat(HandlebarsRenderer.isTruthy(true)).isTrue();
    assertThat(HandlebarsRenderer.isTruthy(0)).isFalse();
    assertThat(HandlebarsRenderer.isTruthy(1)).isTrue();
    assertThat(HandlebarsRenderer.isTruthy(" ")).isFalse();
    assertThat(HandlebarsRenderer.isTruthy("x")).isTrue();
    assertThat(HandlebarsRenderer.isTruthy(List.of())).isFalse();
    assertThat(HandlebarsRenderer.isTruthy(List.of(1))).isTrue();
    assertThat(HandlebarsRenderer.isTruthy(Map.of())).isFalse();
    assertThat(HandlebarsRenderer.isTruthy(Map.of("a", 1))).isTrue();
    assertThat(HandlebarsRenderer.isTruthy(new Object())).isTrue();

    assertThat(HandlebarsRenderer.resolve(null, "a")).isNull();
    assertThat(HandlebarsRenderer.resolve(Map.of("a", "1"), null)).isNull();
    assertThat(HandlebarsRenderer.resolve(Map.of("a", "1"), "a.b")).isNull();
  }

  @Test
  void htmlToPlainTextAndExtractHrefs() {
    assertThat(HandlebarsRenderer.htmlToPlainText(null)).isEmpty();
    assertThat(HandlebarsRenderer.htmlToPlainText("<p>Hi<br/>there</p>")).contains("Hi");
    assertThat(HandlebarsRenderer.extractHrefs(null)).isEmpty();
    assertThat(HandlebarsRenderer.extractHrefs("<a href=\"https://x\">y</a>"))
        .containsExactly("https://x");
  }
}
