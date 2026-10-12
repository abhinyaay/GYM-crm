package com.gymops.plan;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.plan.dto.PlanRequest;
import com.gymops.plan.dto.PlanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Membership plans")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private static final Set<String> SORTABLE = Set.of("name", "price", "code", "createdAt");

    private final PlanService service;

    public record PlanStatusRequest(@NotNull PlanStatus status) {
    }

    @Operation(summary = "Active plan catalogue (public, Redis-cached)")
    @GetMapping
    public List<PlanResponse> listActive() {
        return service.listActive();
    }

    @Operation(summary = "Plan details (public for ACTIVE plans)")
    @GetMapping("/{id}")
    public PlanResponse get(@PathVariable Long id) {
        return service.getVisible(service.get(id));
    }

    @Operation(summary = "All plans including drafts and archived (admin)")
    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public PageResponse<PlanResponse> listAll(@RequestParam(required = false) PlanStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String sort) {
        return service.listAll(status, PageRequests.of(page, size, sort, SORTABLE, Sort.by("price")));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody PlanRequest request) {
        PlanResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/plans/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public PlanResponse update(@PathVariable Long id, @Valid @RequestBody PlanRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Activate (start selling) or archive (stop selling) a plan")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public PlanResponse changeStatus(@PathVariable Long id, @Valid @RequestBody PlanStatusRequest request) {
        return service.changeStatus(id, request.status());
    }
}
