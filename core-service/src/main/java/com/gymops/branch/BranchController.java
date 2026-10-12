package com.gymops.branch;

import com.gymops.branch.dto.BranchRequest;
import com.gymops.branch.dto.BranchResponse;
import com.gymops.branch.dto.BranchStatusRequest;
import com.gymops.branch.dto.BranchSummary;
import com.gymops.branch.dto.FacilityRequest;
import com.gymops.branch.dto.OperatingHoursRequest;
import com.gymops.branch.dto.StaffAssignmentRequest;
import com.gymops.branch.dto.StaffResponse;
import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "Branches")
@RestController
@RequestMapping("/api/v1/branches")
@RequiredArgsConstructor
public class BranchController {

    private static final Set<String> SORTABLE = Set.of("name", "code", "city", "createdAt");

    private final BranchService service;

    @Operation(summary = "Active branches (public, used by the member sign-up flow)")
    @GetMapping("/public")
    public List<BranchSummary> listPublic() {
        return service.listPublic();
    }

    @Operation(summary = "Branches visible to the caller (org-wide roles see all)")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','TRAINER')")
    public PageResponse<BranchResponse> list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String sort) {
        return service.list(PageRequests.of(page, size, sort, SORTABLE, Sort.by("name")));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','TRAINER')")
    public BranchResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public ResponseEntity<BranchResponse> create(@Valid @RequestBody BranchRequest request) {
        BranchResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/branches/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public BranchResponse update(@PathVariable Long id, @Valid @RequestBody BranchRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public BranchResponse changeStatus(@PathVariable Long id, @Valid @RequestBody BranchStatusRequest request) {
        return service.changeStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PutMapping("/{id}/operating-hours")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public BranchResponse replaceOperatingHours(@PathVariable Long id, @Valid @RequestBody OperatingHoursRequest request) {
        return service.replaceOperatingHours(id, request);
    }

    @PostMapping("/{id}/facilities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public BranchResponse addFacility(@PathVariable Long id, @Valid @RequestBody FacilityRequest request) {
        return service.addFacility(id, request);
    }

    @PutMapping("/{id}/facilities/{facilityId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public BranchResponse updateFacility(@PathVariable Long id, @PathVariable Long facilityId,
                                         @Valid @RequestBody FacilityRequest request) {
        return service.updateFacility(id, facilityId, request);
    }

    @DeleteMapping("/{id}/facilities/{facilityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public void removeFacility(@PathVariable Long id, @PathVariable Long facilityId) {
        service.removeFacility(id, facilityId);
    }

    @GetMapping("/{id}/staff")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public List<StaffResponse> listStaff(@PathVariable Long id) {
        return service.listStaff(id);
    }

    @PostMapping("/{id}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public StaffResponse assignStaff(@PathVariable Long id, @Valid @RequestBody StaffAssignmentRequest request) {
        return service.assignStaff(id, request.userId());
    }

    @DeleteMapping("/{id}/staff/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public void unassignStaff(@PathVariable Long id, @PathVariable Long userId) {
        service.unassignStaff(id, userId);
    }
}
