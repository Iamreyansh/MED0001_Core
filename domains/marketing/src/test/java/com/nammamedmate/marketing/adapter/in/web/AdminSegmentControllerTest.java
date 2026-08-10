package com.nammamedmate.marketing.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.marketing.application.SegmentService;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AdminSegmentControllerTest {

  @Mock SegmentService segments;
  AdminSegmentController controller;
  MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    controller = new AdminSegmentController(segments);
  }

  @Test
  void delegatesAllEndpoints() {
    UUID id = UUID.randomUUID();
    when(segments.list(any(), isNull(), isNull(), isNull()))
        .thenReturn(
            new SegmentService.PagedResult(
                Map.of("segments", List.of()), PaginationMeta.of(1, 20, 0)));
    when(segments.create(any(), any(), any(), any()))
        .thenReturn(Map.of("id", id, "status", "PENDING_COMPUTE"));
    when(segments.get(any(), eq(id)))
        .thenReturn(Map.of("id", id, "recommended_actions", List.of("x")));
    when(segments.enqueueCompute(any(), eq(id)))
        .thenReturn(Map.of("job_id", UUID.randomUUID(), "status", "ENQUEUED"));
    when(segments.delete(any(), eq(id))).thenReturn(Map.of("id", id, "deleted", true));
    when(segments.listCustomers(any(), eq(id), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new SegmentService.PagedResult(
                Map.of("customers", List.of()), PaginationMeta.of(1, 20, 0)));

    assertThat(controller.list(principal, null, null, null).data()).containsKey("segments");
    assertThat(
            controller
                .create(
                    principal,
                    new AdminSegmentController.CreateSegmentRequest(
                        "High AOV",
                        "d",
                        List.of(
                            new com.nammamedmate.marketing.domain.SegmentCriterion(
                                "city", "in", List.of("Bangalore")))))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(controller.create(principal, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.get(principal, id).data()).containsEntry("id", id);
    assertThat(controller.compute(principal, id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(controller.delete(principal, id).data()).containsEntry("deleted", true);
    assertThat(controller.customers(principal, id, null, null, null, null).data())
        .containsKey("customers");
  }
}
