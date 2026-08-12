package com.nammamedmate.observability_ops.adapter.out.persistence;

import com.nammamedmate.observability_ops.application.port.out.IncidentNumberPort;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisIncidentNumberAdapter implements IncidentNumberPort {

  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
  private static final Duration TTL = Duration.ofDays(2);

  private final ObjectProvider<StringRedisTemplate> redis;
  private final ConcurrentHashMap<String, AtomicLong> local = new ConcurrentHashMap<>();

  public RedisIncidentNumberAdapter(ObjectProvider<StringRedisTemplate> redis) {
    this.redis = redis;
  }

  @Override
  public String next(LocalDate day) {
    String ymd = DAY.format(day);
    String key = "incident:seq:" + ymd;
    long seq = nextSeq(key);
    return "INC-" + ymd + "-" + String.format("%03d", seq);
  }

  private long nextSeq(String key) {
    StringRedisTemplate template = redis == null ? null : redis.getIfAvailable();
    if (template != null) {
      Long v = template.opsForValue().increment(key);
      if (v != null && v == 1L) {
        template.expire(key, TTL);
      }
      return v == null ? 1L : v;
    }
    return local.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
  }
}
