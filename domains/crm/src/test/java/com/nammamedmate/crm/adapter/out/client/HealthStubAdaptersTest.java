package com.nammamedmate.crm.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.SaasModuleUsageStore;
import com.nammamedmate.crm.domain.HealthMath;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HealthStubAdaptersTest {

  @Mock SaasModuleUsageStore usage;

  @Test
  void supportAndBusiness() {
    assertThat(new StubSupportSatisfactionAdapter().scoreForAccount(Ids.newId()))
        .isEqualTo(HealthMath.DEFAULT_SUPPORT);

    ErpBusinessPerformanceAdapter biz =
        new ErpBusinessPerformanceAdapter(
            usage, Clock.fixed(Instant.parse("2026-07-24T10:00:00Z"), ZoneOffset.UTC));
    assertThat(biz.scoreForAccount(Ids.newId(), null)).isEqualTo(HealthMath.DEFAULT_BUSINESS);

    UUID pharmacyId = Ids.newId();
    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(0L, 0L);
    assertThat(biz.scoreForAccount(Ids.newId(), pharmacyId)).isEqualTo(70.0);

    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(10L, 0L);
    assertThat(biz.scoreForAccount(Ids.newId(), pharmacyId)).isEqualTo(100.0);

    when(usage.countInvoicesThisMonth(eq(pharmacyId), any(), any())).thenReturn(80L, 100L);
    assertThat(biz.scoreForAccount(Ids.newId(), pharmacyId)).isEqualTo(30.0);
  }
}
