package com.nammamedmate.customer.adapter.out.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.customer.application.port.out.GeocodePort;
import com.nammamedmate.customer.application.port.out.GeocodePort.SuggestedAddress;
import com.nammamedmate.kernel.error.AppException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CachingGeocodePortTest {

  @Test
  void localCache_hitsDelegateOnce() {
    AtomicInteger calls = new AtomicInteger();
    GeocodePort delegate =
        (lat, lng) -> {
          calls.incrementAndGet();
          return new SuggestedAddress("", "A", "Bengaluru", "Karnataka", "560001", "fmt", lat, lng);
        };
    CachingGeocodePort cache = new CachingGeocodePort(delegate);

    SuggestedAddress first = cache.reverseGeocode(12.9716, 77.5946);
    SuggestedAddress second = cache.reverseGeocode(12.9716, 77.5946);

    assertThat(calls.get()).isEqualTo(1);
    assertThat(first.city()).isEqualTo("Bengaluru");
    assertThat(second.city()).isEqualTo(first.city());
  }

  @Test
  void redisCache_getAndPut() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenReturn(null);

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);

    GeocodePort delegate =
        (lat, lng) -> new SuggestedAddress("", "A", "City", "State", "110001", "fmt", lat, lng);
    CachingGeocodePort cache = new CachingGeocodePort(delegate, provider);

    SuggestedAddress result = cache.reverseGeocode(28.6139, 77.2090);

    assertThat(result.city()).isEqualTo("City");
    verify(values).set(anyString(), anyString(), eq(Duration.ofHours(1)));
  }

  @Test
  void redisCache_hit_skipsDelegate() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    String encoded =
        CachingGeocodePort.encode(
            new SuggestedAddress("", "A", "Cached", "State", "560001", "fmt", 1.0, 2.0));
    when(values.get(anyString())).thenReturn(encoded);

    ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(redis);

    AtomicInteger calls = new AtomicInteger();
    CachingGeocodePort cache =
        new CachingGeocodePort(
            (lat, lng) -> {
              calls.incrementAndGet();
              return new SuggestedAddress("", "", "", "", "", "", lat, lng);
            },
            provider);

    assertThat(cache.reverseGeocode(1.0, 2.0).city()).isEqualTo("Cached");
    assertThat(calls.get()).isZero();
  }

  @Test
  void decode_corrupt_throws() {
    assertThatThrownBy(() -> CachingGeocodePort.decode("bad"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("GEOCODE_SERVICE_ERROR");
  }

  @Test
  void cacheKey_roundsToFourDecimals() {
    assertThat(CachingGeocodePort.cacheKey(12.97165, 77.59465)).isEqualTo("12.9717,77.5947");
  }

  @Test
  void encode_nullFieldsBecomeEmpty() {
    String encoded =
        CachingGeocodePort.encode(
            new SuggestedAddress(null, null, null, null, null, null, 1.0, 2.0));
    SuggestedAddress decoded = CachingGeocodePort.decode(encoded);
    assertThat(decoded.city()).isEmpty();
    assertThat(decoded.flatBuilding()).isEmpty();
  }
}
