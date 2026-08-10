package com.nammamedmate.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.adapter.out.cache.RedisDeliveryOtpCache;
import com.nammamedmate.order.adapter.out.cache.RedisLiveFeedCache;
import com.nammamedmate.order.adapter.out.client.StubRazorpayPaymentPort;
import com.nammamedmate.order.adapter.out.persistence.JdbcAdminOrderExportStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcAdminOrderQueryAdapter;
import com.nammamedmate.order.adapter.out.persistence.JdbcCartStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcCustomerAddressAdapter;
import com.nammamedmate.order.adapter.out.persistence.JdbcInventoryAvailabilityAdapter;
import com.nammamedmate.order.adapter.out.persistence.JdbcOrderCancellationStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcOrderDisputeStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcOrderNoteStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcOrderStatusEventStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcOrderStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcPharmacyCandidateStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcRefundStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcReorderAttemptLogStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcRxBroadcastStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcWalletBalanceAdapter;
import com.nammamedmate.order.adapter.out.persistence.LocalExportObjectStore;
import com.nammamedmate.order.adapter.out.persistence.StubCodCollectionAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubDeliveryFeeAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubPrescriptionAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubPriceCeilingAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubRiderLookupAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubWalletPort;
import com.nammamedmate.order.adapter.out.persistence.StubZoneMembershipAdapter;
import com.nammamedmate.order.application.port.out.AdminOrderExportStore;
import com.nammamedmate.order.application.port.out.AdminOrderQueryPort;
import com.nammamedmate.order.application.port.out.CartStore;
import com.nammamedmate.order.application.port.out.CodCollectionPort;
import com.nammamedmate.order.application.port.out.CustomerAddressPort;
import com.nammamedmate.order.application.port.out.DeliveryFeePort;
import com.nammamedmate.order.application.port.out.DeliveryOtpCachePort;
import com.nammamedmate.order.application.port.out.ExportObjectStore;
import com.nammamedmate.order.application.port.out.InventoryAvailabilityPort;
import com.nammamedmate.order.application.port.out.LiveFeedCachePort;
import com.nammamedmate.order.application.port.out.OrderCancellationStore;
import com.nammamedmate.order.application.port.out.OrderDisputeStore;
import com.nammamedmate.order.application.port.out.OrderNoteStore;
import com.nammamedmate.order.application.port.out.OrderStatusEventStore;
import com.nammamedmate.order.application.port.out.OrderStore;
import com.nammamedmate.order.application.port.out.PharmacyCandidatePort;
import com.nammamedmate.order.application.port.out.PlatformCouponPort;
import com.nammamedmate.order.application.port.out.PrescriptionPort;
import com.nammamedmate.order.application.port.out.PriceCeilingPort;
import com.nammamedmate.order.application.port.out.RazorpayPaymentPort;
import com.nammamedmate.order.application.port.out.RefundStore;
import com.nammamedmate.order.application.port.out.ReorderAttemptLogStore;
import com.nammamedmate.order.application.port.out.RiderLookupPort;
import com.nammamedmate.order.application.port.out.RxBroadcastStore;
import com.nammamedmate.order.application.port.out.WalletBalancePort;
import com.nammamedmate.order.application.port.out.WalletPort;
import com.nammamedmate.order.application.port.out.ZoneMembershipPort;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class OrderConfig {

  @Bean
  @ConditionalOnMissingBean(PharmacyCandidatePort.class)
  PharmacyCandidatePort pharmacyCandidatePort(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return new JdbcPharmacyCandidateStore(jdbc, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(InventoryAvailabilityPort.class)
  InventoryAvailabilityPort inventoryAvailabilityPort(JdbcTemplate jdbc) {
    return new JdbcInventoryAvailabilityAdapter(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(CartStore.class)
  CartStore cartStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return new JdbcCartStore(jdbc, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(CustomerAddressPort.class)
  CustomerAddressPort customerAddressPort(JdbcTemplate jdbc) {
    return new JdbcCustomerAddressAdapter(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(WalletBalancePort.class)
  WalletBalancePort walletBalancePort(JdbcTemplate jdbc) {
    return new JdbcWalletBalanceAdapter(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(PrescriptionPort.class)
  PrescriptionPort prescriptionPort() {
    return new StubPrescriptionAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(ZoneMembershipPort.class)
  ZoneMembershipPort zoneMembershipPort() {
    return new StubZoneMembershipAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(DeliveryFeePort.class)
  DeliveryFeePort deliveryFeePort() {
    return new StubDeliveryFeeAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(PlatformCouponPort.class)
  PlatformCouponPort platformCouponPort() {
    return (couponCode, itemTotalPaise) -> {
      var applied =
          com.nammamedmate.order.domain.CartPricing.applyCoupon(couponCode, itemTotalPaise);
      return new PlatformCouponPort.Quote(
          applied.code(),
          applied.type(),
          applied.discountPaise(),
          applied.type() == com.nammamedmate.order.domain.CartPricing.CouponType.FREE_DELIVERY,
          applied.message());
    };
  }

  @Bean
  @ConditionalOnMissingBean(CodCollectionPort.class)
  CodCollectionPort codCollectionPort() {
    return new StubCodCollectionAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(RxBroadcastStore.class)
  RxBroadcastStore rxBroadcastStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return new JdbcRxBroadcastStore(jdbc, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(OrderStore.class)
  OrderStore orderStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return new JdbcOrderStore(jdbc, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(OrderStatusEventStore.class)
  OrderStatusEventStore orderStatusEventStore(JdbcTemplate jdbc) {
    return new JdbcOrderStatusEventStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(ReorderAttemptLogStore.class)
  ReorderAttemptLogStore reorderAttemptLogStore(JdbcTemplate jdbc) {
    return new JdbcReorderAttemptLogStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(RefundStore.class)
  RefundStore refundStore(JdbcTemplate jdbc) {
    return new JdbcRefundStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(OrderCancellationStore.class)
  OrderCancellationStore orderCancellationStore(JdbcTemplate jdbc) {
    return new JdbcOrderCancellationStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(RiderLookupPort.class)
  RiderLookupPort riderLookupPort() {
    return new StubRiderLookupAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(WalletPort.class)
  WalletPort walletPort() {
    return new StubWalletPort();
  }

  @Bean
  @ConditionalOnMissingBean(PriceCeilingPort.class)
  PriceCeilingPort priceCeilingPort() {
    return new StubPriceCeilingAdapter();
  }

  @Bean
  @ConditionalOnMissingBean(RazorpayPaymentPort.class)
  RazorpayPaymentPort razorpayPaymentPort(
      @Value("${medmate.razorpay.key-secret:test_razorpay_secret}") String keySecret,
      @Value("${medmate.razorpay.webhook-secret:test_razorpay_webhook_secret}")
          String webhookSecret) {
    return new StubRazorpayPaymentPort(keySecret, webhookSecret);
  }

  @Bean
  @ConditionalOnMissingBean(AdminOrderQueryPort.class)
  AdminOrderQueryPort adminOrderQueryPort(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    return new JdbcAdminOrderQueryAdapter(jdbc, objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean(OrderDisputeStore.class)
  OrderDisputeStore orderDisputeStore(JdbcTemplate jdbc) {
    return new JdbcOrderDisputeStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(OrderNoteStore.class)
  OrderNoteStore orderNoteStore(JdbcTemplate jdbc) {
    return new JdbcOrderNoteStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(AdminOrderExportStore.class)
  AdminOrderExportStore adminOrderExportStore(JdbcTemplate jdbc) {
    return new JdbcAdminOrderExportStore(jdbc);
  }

  @Bean
  @ConditionalOnMissingBean(ExportObjectStore.class)
  ExportObjectStore exportObjectStore() {
    return new LocalExportObjectStore();
  }

  @Bean
  @ConditionalOnMissingBean(LiveFeedCachePort.class)
  LiveFeedCachePort liveFeedCachePort(ObjectProvider<StringRedisTemplate> redis) {
    return new RedisLiveFeedCache(redis);
  }

  @Bean
  @ConditionalOnMissingBean(DeliveryOtpCachePort.class)
  DeliveryOtpCachePort deliveryOtpCachePort(ObjectProvider<StringRedisTemplate> redis) {
    StringRedisTemplate template = redis.getIfAvailable();
    if (template != null) {
      return new RedisDeliveryOtpCache(template);
    }
    return (orderId, otp) -> {};
  }

  @Bean(name = "adminOrderExportExecutor")
  @ConditionalOnMissingBean(name = "adminOrderExportExecutor")
  Executor adminOrderExportExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(1);
    exec.setMaxPoolSize(2);
    exec.setQueueCapacity(50);
    exec.setThreadNamePrefix("admin-order-export-");
    exec.initialize();
    return exec;
  }
}
