package com.gymops.member;

import com.gymops.audit.AuditLogResponse;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.member.dto.MemberListItem;
import com.gymops.member.dto.MemberRequest;
import com.gymops.member.dto.MemberResponse;
import com.gymops.member.dto.MemberStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Members")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private static final String STAFF = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','TRAINER')";
    private static final String FRONT_DESK = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";
    private static final Set<String> SORTABLE = Set.of("lastName", "firstName", "registeredAt", "memberCode");

    private final MemberService service;

    @Operation(summary = "Search members by name/email/phone/code with filters (branch-scoped)")
    @GetMapping
    @PreAuthorize(STAFF)
    public PageResponse<MemberListItem> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) MembershipState membership,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredTo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return service.search(q, branchId, status, membership, registeredFrom, registeredTo,
                PageRequests.of(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "registeredAt")));
    }

    @Operation(summary = "Register a walk-in member")
    @PostMapping
    @PreAuthorize(FRONT_DESK)
    public ResponseEntity<MemberResponse> register(@Valid @RequestBody MemberRequest request) {
        MemberResponse created = service.register(request);
        return ResponseEntity.created(URI.create("/api/v1/members/" + created.id())).body(created);
    }

    @Operation(summary = "The caller's own member profile")
    @GetMapping("/me")
    @PreAuthorize("hasRole('MEMBER')")
    public MemberResponse me() {
        return service.me();
    }

    @Operation(summary = "Member profile incl. current membership (Redis-cached)")
    @GetMapping("/{id}")
    public MemberResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Look up a member by member code (front-desk scan)")
    @GetMapping("/code/{memberCode}")
    @PreAuthorize(STAFF)
    public MemberResponse getByCode(@PathVariable String memberCode) {
        return service.getByCode(memberCode);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public MemberResponse update(@PathVariable Long id, @Valid @RequestBody MemberRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public MemberResponse changeStatus(@PathVariable Long id, @Valid @RequestBody MemberStatusRequest request) {
        return service.changeStatus(id, request.status(), request.reason());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @Operation(summary = "Audit timeline for the member (registrations, subscriptions, payments, check-ins...)")
    @GetMapping("/{id}/history")
    public PageResponse<AuditLogResponse> history(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return service.history(id, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }
}
