package com.nammamedmate.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.order.adapter.out.persistence.JdbcCartStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcCustomerAddressAdapter;
import com.nammamedmate.order.adapter.out.persistence.JdbcInventoryAvailabilityAdapter;
import com.nammamedmate.order.adapter.out.persistence.JdbcPharmacyCandidateStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcRxBroadcastStore;
import com.nammamedmate.order.adapter.out.persistence.JdbcWalletBalanceAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubDeliveryFeeAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubPrescriptionAdapter;
import com.nammamedmate.order.adapter.out.persistence.StubZoneMembershipAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderConfigTest {

  @Test
  void wiresJdbcPorts() {
    OrderConfig config = new OrderConfig();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper om = new ObjectMapper();
    assertThat(config.pharmacyCandidatePort(jdbc, om))
        .isInstanceOf(JdbcPharmacyCandidateStore.class);
    assertThat(config.inventoryAvailabilityPort(jdbc))
        .isInstanceOf(JdbcInventoryAvailabilityAdapter.class);
    assertThat(config.cartStore(jdbc, om)).isInstanceOf(JdbcCartStore.class);
    assertThat(config.customerAddressPort(jdbc)).isInstanceOf(JdbcCustomerAddressAdapter.class);
    assertThat(config.walletBalancePort(jdbc)).isInstanceOf(JdbcWalletBalanceAdapter.class);
    assertThat(config.prescriptionPort()).isInstanceOf(StubPrescriptionAdapter.class);
    assertThat(config.zoneMembershipPort()).isInstanceOf(StubZoneMembershipAdapter.class);
    assertThat(config.deliveryFeePort()).isInstanceOf(StubDeliveryFeeAdapter.class);
    assertThat(config.platformCouponPort()).isNotNull();
    assertThat(config.rxBroadcastStore(jdbc, om)).isInstanceOf(JdbcRxBroadcastStore.class);
    assertThat(config.orderStore(jdbc, om))
        .isInstanceOf(com.nammamedmate.order.adapter.out.persistence.JdbcOrderStore.class);
    assertThat(config.orderStatusEventStore(jdbc))
        .isInstanceOf(
            com.nammamedmate.order.adapter.out.persistence.JdbcOrderStatusEventStore.class);
    assertThat(config.riderLookupPort())
        .isInstanceOf(com.nammamedmate.order.adapter.out.persistence.StubRiderLookupAdapter.class);
    assertThat(config.refundStore(jdbc))
        .isInstanceOf(com.nammamedmate.order.adapter.out.persistence.JdbcRefundStore.class);
    assertThat(config.orderCancellationStore(jdbc))
        .isInstanceOf(
            com.nammamedmate.order.adapter.out.persistence.JdbcOrderCancellationStore.class);
    assertThat(config.walletPort())
        .isInstanceOf(com.nammamedmate.order.adapter.out.persistence.StubWalletPort.class);
    assertThat(config.priceCeilingPort())
        .isInstanceOf(com.nammamedmate.order.adapter.out.persistence.StubPriceCeilingAdapter.class);
    assertThat(config.razorpayPaymentPort("sec", "wh"))
        .isInstanceOf(com.nammamedmate.order.adapter.out.client.StubRazorpayPaymentPort.class);
  }
}
