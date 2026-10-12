package com.gymops.complaint;

import com.gymops.audit.AuditLogResponse;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.complaint.dto.ComplaintDtos.AssignRequest;
import com.gymops.complaint.dto.ComplaintDtos.CommentRequest;
import com.gymops.complaint.dto.ComplaintDtos.CommentResponse;
import com.gymops.complaint.dto.ComplaintDtos.ComplaintResponse;
import com.gymops.complaint.dto.ComplaintDtos.CreateRequest;
import com.gymops.complaint.dto.ComplaintDtos.EscalateRequest;
import com.gymops.complaint.dto.ComplaintDtos.StatusRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Complaints & support")
@RestController
@RequestMapping("/api/v1/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private static final String SUPPORT_STAFF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";
    private static final Set<String> SORTABLE = Set.of("createdAt", "slaDueAt");

    private final ComplaintService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public ResponseEntity<ComplaintResponse> create(@Valid @RequestBody CreateRequest request) {
        ComplaintResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/complaints/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<ComplaintResponse> search(@RequestParam(required = false) Long branchId,
                                                  @RequestParam(required = false) ComplaintStatus status,
                                                  @RequestParam(required = false) ComplaintPriority priority,
                                                  @RequestParam(required = false) ComplaintCategory category,
                                                  @RequestParam(required = false) Long assignedTo,
                                                  @RequestParam(defaultValue = "false") boolean slaBreached,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(required = false) String sort) {
        return service.search(branchId, status, priority, category, assignedTo, slaBreached,
                PageRequests.of(page, size, sort, SORTABLE, Sort.by("slaDueAt")));
    }

    @GetMapping("/{id}")
    public ComplaintResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}/assignee")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public ComplaintResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request.assigneeUserId());
    }

    @PatchMapping("/{id}/status")
    public ComplaintResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize(SUPPORT_STAFF)
    public ComplaintResponse escalate(@PathVariable Long id, @Valid @RequestBody EscalateRequest request) {
        return service.escalate(id, request.reason());
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse comment(@PathVariable Long id, @Valid @RequestBody CommentRequest request) {
        return service.addComment(id, request);
    }

    @GetMapping("/{id}/comments")
    public List<CommentResponse> comments(@PathVariable Long id) {
        return service.comments(id);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(SUPPORT_STAFF)
    public PageResponse<AuditLogResponse> history(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return service.history(id, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }
}
