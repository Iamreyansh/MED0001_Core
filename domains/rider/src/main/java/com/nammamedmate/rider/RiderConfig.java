package com.nammamedmate.rider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.rider.adapter.out.cache.RedisAssignmentOtpCache;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLiveStatusCache;
import com.nammamedmate.rider.adapter.out.cache.RedisRiderLocationCache;
import com.nammamedmate.rider.adapter.out.client.StubAadhaarKycAdapter;
import com.nammamedmate.rider.adapter.out.client.StubDistanceMatrixAdapter;
import com.nammamedmate.rider.adapter.out.client.StubRazorpayRouteAdapter;
import com.nammamedmate.rider.adapter.out.sse.InMemoryOrderLocationPush;
import com.nammamedmate.rider.application.port.out.AadhaarKycPort;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.AssignmentOtpCachePort;
import com.nammamedmate.rider.application.port.out.CodDepositConfirmedPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.OrderDetails;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort.QueuePage;
import com.nammamedmate.rider.application.port.out.DistanceMatrixPort;
import com.nammamedmate.rider.application.port.out.FinanceCodDailyReconciliationPort;
import com.nammamedmate.rider.application.port.out.OrderLocationPushPort;
import com.nammamedmate.rider.application.port.out.RazorpayRoutePort;
import com.nammamedmate.rider.application.port.out.RiderLiveStatusCachePort;
import com.nammamedmate.rider.application.port.out.RiderLocationCachePort;
import com.nammamedmate.rider.domain.AssignmentOtps;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RiderConfig {

  @Bean
  @ConditionalOnMissingBean(AadhaarKycPort.class)
  AadhaarKycPort aadhaarKycPort() {
    return new StubAadhaarKycAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(ActiveDeliveryPort.class)
  ActiveDeliveryPort stubActiveDeliveryPort() {
    return new ActiveDeliveryPort() {
      @Override
      public Optional<ActiveOrder> findActiveByRider(java.util.UUID riderId) {
        return Optional.empty();
      }

      @Override
      public int countLiveOrdersInZone(java.util.UUID zoneId) {
        return 0;
      }

      @Override
      public void flagForMonitoring(java.util.UUID orderId, String reason) {}
    };
  }

  @Bean
  @ConditionalOnMissingBean(DispatchOrderPort.class)
  DispatchOrderPort stubDispatchOrderPort() {
    return new DispatchOrderPort() {
      @Override
      public QueuePage listUnassignedReady(UUID zoneId, int page, int limit) {
        return new QueuePage(List.of(), 0);
      }

      @Override
      public Optional<OrderDetails> findOrder(UUID orderId) {
        return Optional.empty();
      }

      @Override
      public void assignRiderOnOrder(UUID orderId, UUID riderId, Instant now) {}

      @Override
      public void clearRiderOnOrder(UUID orderId, Instant now) {}

      @Override
      public void advanceStatus(
          UUID orderId,
          String fromStatus,
          String toStatus,
          String actorType,
          UUID actorId,
          String notes,
          Instant now) {}

      @Override
      public Optional<String> peekDeliveryOtp(UUID orderId) {
        return Optional.empty();
      }

      @Override
      public boolean verifyDeliveryOtp(UUID orderId, String otp) {
        return false;
      }

      @Override
      public String ensureDeliveryOtp(UUID orderId, Instant now) {
        return AssignmentOtps.generate();
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(DistanceMatrixPort.class)
  DistanceMatrixPort distanceMatrixPort() {
    return new StubDistanceMatrixAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(RazorpayRoutePort.class)
  RazorpayRoutePort razorpayRoutePort() {
    return new StubRazorpayRouteAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(AssignmentOtpCachePort.class)
  AssignmentOtpCachePort assignmentOtpCachePort(ObjectProvider<StringRedisTemplate> redis) {
    return new RedisAssignmentOtpCache(redis);
  }

  @Bean
  @ConditionalOnMissingBean(RiderLiveStatusCachePort.class)
  RiderLiveStatusCachePort riderLiveStatusCachePort(ObjectProvider<StringRedisTemplate> redis) {
    return new RedisRiderLiveStatusCache(redis);
  }

  @Bean
  @ConditionalOnMissingBean(RiderLocationCachePort.class)
  RiderLocationCachePort riderLocationCachePort(ObjectProvider<StringRedisTemplate> redis) {
    return new RedisRiderLocationCache(redis);
  }

  @Bean
  @ConditionalOnMissingBean(OrderLocationPushPort.class)
  OrderLocationPushPort orderLocationPushPort(ObjectMapper mapper) {
    return new InMemoryOrderLocationPush(mapper);
  }

  @Bean
  @ConditionalOnMissingBean(CustomerOrderLocationPort.class)
  CustomerOrderLocationPort stubCustomerOrderLocationPort() {
    return orderId -> Optional.empty();
  }

  @Bean
  @ConditionalOnMissingBean(CodDepositConfirmedPort.class)
  CodDepositConfirmedPort stubCodDepositConfirmedPort() {
    return (depositId, riderId, amountPaise) -> {
      // no-op until apps/api ledger bridge (EPIC-012/STORY-006)
    };
  }

  @Bean
  @ConditionalOnMissingBean(FinanceCodDailyReconciliationPort.class)
  FinanceCodDailyReconciliationPort stubFinanceCodDailyReconciliationPort() {
    return reportDate -> {
      // no-op until apps/api bridge — CodReconciliationService falls back to outbox stub
    };
  }
}
