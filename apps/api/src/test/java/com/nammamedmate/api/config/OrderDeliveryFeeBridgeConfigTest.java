package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.order.application.port.out.DeliveryFeePort;
import com.nammamedmate.order.application.port.out.DeliveryFeePort.FeeQuote;
import com.nammamedmate.rider.application.DeliveryPricingService;
import com.nammamedmate.rider.application.DeliveryPricingService.LockedQuote;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderDeliveryFeeBridgeConfigTest {

  @Test
  void bridgesQuoteAndLockSnapshot() {
    DeliveryPricingService pricing = mock(DeliveryPricingService.class);
    UUID zoneId = UUID.randomUUID();
    LockedQuote locked =
        new LockedQuote(
            zoneId,
            new BigDecimal("3.00"),
            new BigDecimal("25"),
            new BigDecimal("15"),
            BigDecimal.ONE,
            new BigDecimal("40"),
            new BigDecimal("5"),
            false,
            new BigDecimal("39.30"),
            4000L,
            500L);
    when(pricing.quoteForDelivery(any(), anyDouble(), anyDouble(), anyLong(), anyBoolean()))
        .thenReturn(Optional.of(locked));

    DeliveryFeePort port = new OrderDeliveryFeeBridgeConfig().riderDeliveryFeePort(pricing);
    assertThat(port.quote(null, 1.0, 1.0, 1000L, false)).isEmpty();
    assertThat(port.quote(UUID.randomUUID(), null, 1.0, 1000L, false)).isEmpty();

    Optional<FeeQuote> quote = port.quote(UUID.randomUUID(), 12.9, 77.6, 10_000L, false);
    assertThat(quote).isPresent();
    assertThat(quote.get().deliveryFeePaise()).isEqualTo(4000L);
    assertThat(quote.get().zoneId()).isEqualTo(zoneId);

    UUID orderId = UUID.randomUUID();
    port.lockSnapshot(orderId, quote.get());
    verify(pricing).lockSnapshot(orderId, locked);

    port.lockSnapshot(orderId, new FeeQuote(0, 500, null, 0, true, "not-a-locked-quote"));
  }
}
