package com.nammamedmate.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionMatcherTest {

  @Test
  void wildcardsAndExact() {
    assertThat(PermissionMatcher.allows(List.of("*:*"), "pharmacies:suspend")).isTrue();
    assertThat(PermissionMatcher.allows(List.of("*"), "anything:here")).isTrue();
    assertThat(PermissionMatcher.allows(List.of("orders:*"), "orders:cancel")).isTrue();
    assertThat(PermissionMatcher.allows(List.of("orders:read"), "orders:cancel")).isFalse();
    assertThat(PermissionMatcher.allows(List.of("tickets:*"), "pharmacies:suspend")).isFalse();
    assertThat(PermissionMatcher.allows(List.of("orders:read"), "orders:read")).isTrue();
    assertThat(PermissionMatcher.allows(List.of(), "orders:read")).isFalse();
    assertThat(PermissionMatcher.allows(List.of("orders:read"), null)).isFalse();
    assertThat(PermissionMatcher.allows(List.of("orders:read"), "   ")).isFalse();
    assertThat(PermissionMatcher.allows(Arrays.asList(null, " ", "orders:read"), "orders:read"))
        .isTrue();
    assertThat(PermissionMatcher.matches("bad", "orders:read")).isFalse();
    assertThat(PermissionMatcher.matches("orders:read", "bad")).isFalse();
    assertThat(PermissionMatcher.matches("*:read", "orders:read")).isTrue();
    assertThat(PermissionMatcher.matches("orders:write", "orders:read")).isFalse();
    assertThat(PermissionMatcher.matches("  orders:read  ".trim(), "orders:read")).isTrue();
  }
}
