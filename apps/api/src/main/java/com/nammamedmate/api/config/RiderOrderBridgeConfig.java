package com.nammamedmate.api.config;

import com.nammamedmate.rider.adapter.out.persistence.JdbcActiveDeliveryAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcCustomerOrderLocationAdapter;
import com.nammamedmate.rider.adapter.out.persistence.JdbcDispatchOrderAdapter;
import com.nammamedmate.rider.application.port.out.ActiveDeliveryPort;
import com.nammamedmate.rider.application.port.out.CustomerOrderLocationPort;
import com.nammamedmate.rider.application.port.out.DispatchOrderPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition-root bridge: rider ports → orders/pharmacies JDBC (+ Redis OTP peek). */
@Configuration
public class RiderOrderBridgeConfig {

  @Bean
  @Primary
  ActiveDeliveryPort jdbcActiveDeliveryPort(JdbcTemplate jdbc) {
    return new JdbcActiveDeliveryAdapter(jdbc);
  }

  @Bean
  @Primary
  DispatchOrderPort jdbcDispatchOrderPort(
      JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis) {
    return new JdbcDispatchOrderAdapter(jdbc, redis.getIfAvailable());
  }

  @Bean
  @Primary
  CustomerOrderLocationPort jdbcCustomerOrderLocationPort(JdbcTemplate jdbc) {
    return new JdbcCustomerOrderLocationAdapter(jdbc);
  }
}
