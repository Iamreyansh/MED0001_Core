package com.nammamedmate.support.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import com.nammamedmate.support.application.DisputeService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SupportDisputeControllersTest {

  @Mock DisputeService disputes;
  @InjectMocks SupportDisputeController support;
  @InjectMocks AdminSupportDisputeController admin;
  @InjectMocks CustomerDisputeController customer;

  private final MedmatePrincipal principal =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");
  private final MedmatePrincipal cust =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");

  @Test
  void createDelegates() {
    when(disputes.create(any(), any())).thenReturn(Map.of("dispute_id", "DSP-1"));
    assertThat(support.create(cust, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            support
                .create(
                    cust,
                    new SupportDisputeController.CreateDisputeRequest(
                        UUID.randomUUID(), "WRONG_ITEMS", "d", List.of("https://e")))
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void adminEndpointsDelegate() {
    UUID id = UUID.randomUUID();
    when(disputes.listAdmin(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
        .thenReturn(
            new DisputeService.ListResult(
                Map.of("disputes", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(admin.list(principal, null, null, null, null, null, null).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    when(disputes.listAdmin(any(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(true)))
        .thenReturn(new DisputeService.ListResult(Map.of("csv", "a\n"), null));
    when(disputes.exportCsvBytes(any())).thenReturn("a\n".getBytes());
    assertThat(
            admin
                .list(principal, null, null, null, null, null, true)
                .getHeaders()
                .getContentType()
                .toString())
        .contains("text/csv");

    when(disputes.listAdmin(any(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false)))
        .thenReturn(new DisputeService.ListResult(Map.of("disputes", List.of()), null));
    assertThat(admin.list(principal, null, null, null, null, null, false).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    when(disputes.getAdmin(any(), eq(id))).thenReturn(Map.of("id", id));
    assertThat(admin.get(principal, id).success()).isTrue();

    when(disputes.investigate(any(), eq(id), any())).thenReturn(Map.of("status", "INVESTIGATING"));
    assertThat(admin.investigate(principal, id, null).success()).isTrue();
    assertThat(
            admin
                .investigate(
                    principal, id, new AdminSupportDisputeController.InvestigateRequest(id, "n"))
                .success())
        .isTrue();

    when(disputes.resolveApprove(any(), eq(id), any())).thenReturn(Map.of("status", "RESOLVED"));
    assertThat(admin.resolveApprove(principal, id, null).success()).isTrue();
    assertThat(
            admin
                .resolveApprove(
                    principal,
                    id,
                    new AdminSupportDisputeController.ApproveRequest(
                        "PHARMACY", 96, "SOURCE", "ok"))
                .success())
        .isTrue();

    when(disputes.resolveReject(any(), eq(id), any())).thenReturn(Map.of("status", "RESOLVED"));
    assertThat(admin.resolveReject(principal, id, null).success()).isTrue();
    assertThat(
            admin
                .resolveReject(
                    principal, id, new AdminSupportDisputeController.RejectRequest("no", "n"))
                .success())
        .isTrue();
  }

  @Test
  void customerListDelegates() {
    when(disputes.listMine(any(), isNull(), isNull()))
        .thenReturn(
            new DisputeService.ListResult(
                Map.of("disputes", List.of()), PaginationMeta.of(1, 20, 0)));
    assertThat(customer.listMine(cust, null, null).success()).isTrue();
  }
}
