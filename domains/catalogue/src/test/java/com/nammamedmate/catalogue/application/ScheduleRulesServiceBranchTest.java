package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleRulesServiceBranchTest {

  @Mock private RateLimiter rateLimiter;

  @Test
  void allAllowedRolesAndRateLimit() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    ScheduleRulesService service = new ScheduleRulesService(rateLimiter);

    for (AuthRole role : ListRoles()) {
      MedmatePrincipal p =
          new MedmatePrincipal(
              UUID.randomUUID(),
              role,
              role.name().startsWith("PHARMACY") ? UUID.randomUUID() : null,
              TokenScope.FULL,
              "j");
      assertThat(service.get(p)).containsKey("schedules");
    }

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.get(ops))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  private static AuthRole[] ListRoles() {
    return new AuthRole[] {
      AuthRole.ADMIN_SUPER,
      AuthRole.ADMIN_OPERATIONS,
      AuthRole.ADMIN_COMPLIANCE,
      AuthRole.PHARMACY_OWNER,
      AuthRole.PHARMACY_STAFF
    };
  }
}
