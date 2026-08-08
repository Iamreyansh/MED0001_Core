package com.nammamedmate.settings.adapter.out.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.settings.application.port.out.PlatformConfigCachePort;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.ConfigRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPlatformConfigCache implements PlatformConfigCachePort {

  static final Duration TTL = Duration.ofSeconds(60);
  static final String KEY = "platform_config";

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ObjectMapper objectMapper;
  private final AtomicReference<String> local = new AtomicReference<>();

  public RedisPlatformConfigCache(
      ObjectProvider<StringRedisTemplate> redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<List<ConfigRow>> getAll() {
    String raw = read();
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      List<Map<String, Object>> maps =
          objectMapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {});
      List<ConfigRow> rows = new ArrayList<>(maps.size());
      for (Map<String, Object> m : maps) {
        rows.add(fromMap(m));
      }
      return Optional.of(rows);
    } catch (JsonProcessingException | RuntimeException ex) {
      return Optional.empty();
    }
  }

  @Override
  public void putAll(List<ConfigRow> rows) {
    if (rows == null) {
      return;
    }
    try {
      List<Map<String, Object>> maps = new ArrayList<>(rows.size());
      for (ConfigRow row : rows) {
        maps.add(toMap(row));
      }
      write(objectMapper.writeValueAsString(maps));
    } catch (JsonProcessingException | RuntimeException ignored) {
      // ponytail: cache miss is fine; next read reloads from DB
    }
  }

  @Override
  public void invalidate() {
    StringRedisTemplate template = template();
    if (template != null) {
      template.delete(KEY);
    }
    local.set(null);
  }

  private String read() {
    StringRedisTemplate template = template();
    if (template != null) {
      return template.opsForValue().get(KEY);
    }
    return local.get();
  }

  private void write(String json) {
    StringRedisTemplate template = template();
    if (template != null) {
      template.opsForValue().set(KEY, json, TTL);
      return;
    }
    local.set(json);
  }

  private StringRedisTemplate template() {
    return redis == null ? null : redis.getIfAvailable();
  }

  private static Map<String, Object> toMap(ConfigRow row) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("key", row.key());
    m.put("value", row.value());
    m.put("type", row.type());
    m.put("unit", row.unit());
    m.put("domain", row.domain());
    m.put("immutable", row.immutable());
    m.put("description", row.description());
    m.put("updated_by", row.updatedBy() == null ? null : row.updatedBy().toString());
    m.put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
    return m;
  }

  private static ConfigRow fromMap(Map<String, Object> m) {
    String updatedByRaw = stringOrNull(m.get("updated_by"));
    String updatedAtRaw = stringOrNull(m.get("updated_at"));
    return new ConfigRow(
        stringOrNull(m.get("key")),
        stringOrNull(m.get("value")),
        stringOrNull(m.get("type")),
        stringOrNull(m.get("unit")),
        stringOrNull(m.get("domain")),
        parseImmutable(m.get("immutable")),
        stringOrNull(m.get("description")),
        updatedByRaw == null || updatedByRaw.isBlank() ? null : UUID.fromString(updatedByRaw),
        updatedAtRaw == null || updatedAtRaw.isBlank() ? null : Instant.parse(updatedAtRaw));
  }

  private static boolean parseImmutable(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return "true".equalsIgnoreCase(stringOrNull(value));
  }

  private static String stringOrNull(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
