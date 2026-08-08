package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.error.AppException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ConfigValueTypesTest {

  @Test
  void parseAndValidateBranches() {
    assertThat(ConfigValueTypes.parse("integer", "25")).isEqualTo(25);
    assertThat(ConfigValueTypes.parse("decimal", "8.5")).isEqualTo(new BigDecimal("8.5"));
    assertThat(ConfigValueTypes.parse("boolean", "true")).isEqualTo(true);
    assertThat(ConfigValueTypes.parse("boolean", "false")).isEqualTo(false);
    assertThat(ConfigValueTypes.parse("string", "NMM")).isEqualTo("NMM");

    assertThat(ConfigValueTypes.validateAndSerialize("orders.delivery_fee", "integer", 30))
        .isEqualTo("30");
    assertThat(ConfigValueTypes.validateAndSerialize("orders.delivery_fee", "integer", 30L))
        .isEqualTo("30");
    assertThat(
            ConfigValueTypes.validateAndSerialize(
                "orders.delivery_fee", "integer", BigDecimal.valueOf(30)))
        .isEqualTo("30");
    assertThat(ConfigValueTypes.validateAndSerialize("orders.delivery_fee", "integer", 30.0))
        .isEqualTo("30");
    assertThat(
            ConfigValueTypes.validateAndSerialize(
                "commissions.default_pharmacy_commission_pct", "decimal", 9.0))
        .isEqualTo("9");
    assertThat(
            ConfigValueTypes.validateAndSerialize(
                "commissions.min_commission_pct", "decimal", new BigDecimal("3.0")))
        .isEqualTo("3");
    assertThat(
            ConfigValueTypes.validateAndSerialize("commissions.min_commission_pct", "decimal", 3))
        .isEqualTo("3");
    assertThat(
            ConfigValueTypes.validateAndSerialize("commissions.min_commission_pct", "decimal", 3L))
        .isEqualTo("3");
    assertThat(
            ConfigValueTypes.validateAndSerialize(
                "commissions.min_commission_pct", "decimal", 3.5f))
        .isEqualTo("3.5");
    assertThat(ConfigValueTypes.validateAndSerialize("payments.cod_available", "boolean", true))
        .isEqualTo("true");
    assertThat(ConfigValueTypes.validateAndSerialize("orders.order_id_prefix", "string", "NMM"))
        .isEqualTo("NMM");

    assertThatThrownBy(
            () -> ConfigValueTypes.validateAndSerialize("orders.delivery_fee", "integer", "thirty"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> ConfigValueTypes.validateAndSerialize("orders.delivery_fee", "integer", 30.5))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                ConfigValueTypes.validateAndSerialize(
                    "orders.delivery_fee", "integer", BigDecimal.valueOf(30.5)))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                ConfigValueTypes.validateAndSerialize(
                    "orders.delivery_fee", "integer", Long.MAX_VALUE))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () -> ConfigValueTypes.validateAndSerialize("payments.cod_available", "boolean", 1))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () -> ConfigValueTypes.validateAndSerialize("orders.order_id_prefix", "string", 1))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> ConfigValueTypes.validateAndSerialize("x", "decimal", "nope"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> ConfigValueTypes.validateAndSerialize("x", "integer", null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> ConfigValueTypes.validateAndSerialize("x", "unknown", 1))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> ConfigValueTypes.parse(null, "1")).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> ConfigValueTypes.parse("boolean", "maybe"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ConfigValueTypes.parse("integer", null))
        .isInstanceOf(AppException.class);
    assertThat(ConfigValueTypes.ALL).contains("integer", "decimal", "boolean", "string");
    assertThatThrownBy(() -> ConfigValueTypes.validateAndSerialize(null, "integer", true))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> ConfigValueTypes.validateAndSerialize("x", null, 1))
        .isInstanceOf(AppException.class);
    assertThat(ConfigValueTypes.validateAndSerialize("delivery_fee", "integer", 1)).isEqualTo("1");
    assertThat(ConfigValueTypes.validateAndSerialize("trailing.", "integer", 2)).isEqualTo("2");
    assertThatThrownBy(
            () ->
                ConfigValueTypes.validateAndSerialize(
                    "orders.delivery_fee", "integer", (long) Integer.MIN_VALUE - 1L))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                ConfigValueTypes.validateAndSerialize(
                    "orders.delivery_fee", "integer", Double.MAX_VALUE))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(
            () ->
                ConfigValueTypes.validateAndSerialize(
                    "orders.delivery_fee", "integer", (double) Integer.MIN_VALUE - 1000.0))
        .isInstanceOf(AppException.class);
  }
}
