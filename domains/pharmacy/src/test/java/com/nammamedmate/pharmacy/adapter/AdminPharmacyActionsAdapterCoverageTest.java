package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.in.web.AdminBulkJobController;
import com.nammamedmate.pharmacy.adapter.in.web.AdminBulkJobController.BulkActionRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyActionsController;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyActionsController.AddNoteRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyActionsController.CallLogRequest;
import com.nammamedmate.pharmacy.adapter.in.web.AdminPharmacyActionsController.NoticeRequest;
import com.nammamedmate.pharmacy.application.AdminBulkActionService;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsService;
import com.nammamedmate.pharmacy.application.AdminPharmacyActionsService.NotesListResult;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore;
import com.nammamedmate.pharmacy.application.port.out.BulkActionJobStore.JobRow;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminPharmacyActionsAdapterCoverageTest {

  private static final UUID PID = Ids.newId();
  private static final UUID JOB = Ids.newId();

  @Test
  void actionsControllerDelegates() {
    AdminPharmacyActionsService service = mock(AdminPharmacyActionsService.class);
    when(service.sendNotice(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("notice_id", PID.toString()));
    when(service.addNote(any(), any(), any(), any())).thenReturn(Map.of("note_id", PID.toString()));
    when(service.listNotes(any(), any(), any(), any(), any()))
        .thenReturn(
            new NotesListResult(
                Map.of("pharmacy_id", PID.toString(), "notes", List.of()),
                PaginationMeta.of(1, 20, 0)));
    when(service.logCall(any(), any(), any(), any(), any()))
        .thenReturn(Map.of("call_log_id", PID.toString()));

    AdminPharmacyActionsController controller = new AdminPharmacyActionsController(service);
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

    assertThat(
            controller
                .sendNotice(
                    principal,
                    PID,
                    new NoticeRequest("WHATSAPP", null, "msg", "NORMAL", "PHARMACY_GENERAL_NOTICE"))
                .success())
        .isTrue();
    assertThat(controller.addNote(principal, PID, new AddNoteRequest("note", true)).success())
        .isTrue();
    assertThat(controller.listNotes(principal, PID, true, 1, 20).success()).isTrue();
    assertThat(
            controller.logCall(principal, PID, new CallLogRequest(60, "RESOLVED", "ok")).success())
        .isTrue();
    assertThat(controller.sendNotice(principal, PID, null).success()).isTrue();
    assertThat(controller.addNote(principal, PID, null).success()).isTrue();
    assertThat(controller.logCall(principal, PID, null).success()).isTrue();
  }

  @Test
  void bulkJobControllerDelegates() {
    AdminBulkActionService bulkService = mock(AdminBulkActionService.class);
    BulkActionJobStore jobs = mock(BulkActionJobStore.class);
    when(bulkService.submitBulkAction(any(), any(), any(), any()))
        .thenReturn(Map.of("job_id", JOB.toString(), "status", "QUEUED"));
    when(bulkService.getJobStatus(any(), any()))
        .thenReturn(Map.of("job_id", JOB.toString(), "status", "COMPLETED"));
    when(jobs.findById(JOB))
        .thenReturn(
            Optional.of(
                new JobRow(
                    JOB,
                    "EXPORT",
                    Map.of(),
                    List.of(PID),
                    "COMPLETED",
                    1,
                    1,
                    1,
                    0,
                    0,
                    List.of(),
                    Map.of("export_content", "code,business_name\n"),
                    Ids.newId(),
                    Instant.parse("2026-07-24T00:00:00Z"),
                    Instant.parse("2026-07-24T00:01:00Z"),
                    Instant.parse("2026-07-24T00:00:00Z"))));

    AdminBulkJobController controller = new AdminBulkJobController(bulkService, jobs);
    MedmatePrincipal principal =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

    assertThat(
            controller
                .bulkAction(principal, new BulkActionRequest(List.of(PID), "EXPORT", Map.of()))
                .success())
        .isTrue();
    assertThat(controller.getJob(principal, JOB).success()).isTrue();
    assertThat(controller.downloadExport(principal, JOB).getBody()).contains("code,business_name");
    assertThat(controller.bulkAction(principal, null).success()).isTrue();
  }
}
