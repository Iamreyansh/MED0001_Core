package com.nammamedmate.notification.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Handlebars: {@code {{var}}}, nested dots, {@code {{#if x}}...{{/if}}}, undefined → empty.
 */
public final class HandlebarsRenderer {

  private static final Pattern IF_BLOCK =
      Pattern.compile("\\{\\{#if\\s+([\\w.]+)\\}\\}([\\s\\S]*?)\\{\\{/if\\}\\}");
  private static final Pattern VAR = Pattern.compile("\\{\\{([\\w.]+)\\}\\}");

  private HandlebarsRenderer() {}

  public static String render(String template, Map<String, Object> variables) {
    if (template == null) {
      return "";
    }
    Map<String, Object> vars = variables == null ? Map.of() : variables;
    String withIfs = expandIfs(template, vars);
    return expandVars(withIfs, vars);
  }

  public static String htmlToPlainText(String html) {
    if (html == null || html.isBlank()) {
      return "";
    }
    String text =
        html.replaceAll("(?is)<script[^>]*>.*?</script>", "")
            .replaceAll("(?is)<style[^>]*>.*?</style>", "")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</p>", "\n")
            .replaceAll("(?i)</div>", "\n")
            .replaceAll("(?i)<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    return text;
  }

  private static String expandIfs(String template, Map<String, Object> vars) {
    String out = template;
    Matcher m = IF_BLOCK.matcher(out);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      Object value = resolve(vars, m.group(1));
      String replacement = isTruthy(value) ? m.group(2) : "";
      m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String expandVars(String template, Map<String, Object> vars) {
    Matcher m = VAR.matcher(template);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      Object value = resolve(vars, m.group(1));
      String replacement = value == null ? "" : String.valueOf(value);
      m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  static Object resolve(Map<String, Object> root, String path) {
    if (root == null || path == null || path.isBlank()) {
      return null;
    }
    String[] parts = path.split("\\.");
    Object current = root;
    for (String part : parts) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = ((Map<String, Object>) map).get(part);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  static boolean isTruthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0d;
    }
    if (value instanceof String s) {
      return !s.isBlank();
    }
    if (value instanceof List<?> list) {
      return !list.isEmpty();
    }
    if (value instanceof Map<?, ?> map) {
      return !map.isEmpty();
    }
    return true;
  }

  /** Collects href URLs from HTML anchors for click-tracking rewrite. */
  public static List<String> extractHrefs(String html) {
    List<String> hrefs = new ArrayList<>();
    if (html == null) {
      return hrefs;
    }
    Matcher m = Pattern.compile("(?i)href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
    while (m.find()) {
      hrefs.add(m.group(1));
    }
    return hrefs;
  }
}
