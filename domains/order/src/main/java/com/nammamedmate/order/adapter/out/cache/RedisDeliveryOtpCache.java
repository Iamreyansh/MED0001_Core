package com.nammamedmate.order.adapter.out.cache;

import com.nammamedmate.order.application.port.out.DeliveryOtpCachePort;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis TTL cache for delivery OTP plaintext (SMS worker reads; outbox stays OTP-free). */
public class RedisDeliveryOtpCache implements DeliveryOtpCachePort {

  static final String KEY_PREFIX = "order:delivery-otp:";
  static final Duration TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;

  public RedisDeliveryOtpCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void store(UUID orderId, String otp) {
    if (orderId == null || otp == null || otp.isBlank()) {
      return;
    }
    redis.opsForValue().set(KEY_PREFIX + orderId, otp, TTL);
  }
}
