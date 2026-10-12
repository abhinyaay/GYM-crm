package com.gymops.growth.campaign;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.growth.campaign.CampaignDtos.CampaignRequest;
import com.gymops.growth.campaign.CampaignDtos.CampaignResponse;
import com.gymops.growth.campaign.CampaignDtos.PreviewResponse;
import com.gymops.growth.campaign.CampaignDtos.RecipientResponse;
import com.gymops.growth.campaign.CampaignDtos.RunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Retention campaigns")
@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
public class CampaignController {

    private final CampaignService service;
    private final CampaignRunner runner;

    @GetMapping
    public PageResponse<CampaignResponse> list(@RequestParam(required = false) Long branchId,
                                               @RequestParam(required = false) CampaignStatus status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        PageRequests.of(page, size, null, Set.of(), Sort.unsorted());   // validates bounds
        return service.list(branchId, status, page, size);
    }

    @PostMapping
    public ResponseEntity<CampaignResponse> create(@Valid @RequestBody CampaignRequest request) {
        CampaignResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/campaigns/" + created.id())).body(created);
    }

    @Operation(summary = "Preview an unsaved campaign: audience size and rendered sample messages")
    @PostMapping("/preview")
    public PreviewResponse previewDraft(@Valid @RequestBody CampaignRequest request) {
        return service.preview(request);
    }

    @GetMapping("/{id}")
    public CampaignResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public CampaignResponse update(@PathVariable Long id, @Valid @RequestBody CampaignRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Preview a saved campaign (audience, members still in cooldown, sample messages)")
    @PostMapping("/{id}/preview")
    public PreviewResponse preview(@PathVariable Long id) {
        return service.preview(id);
    }

    @Operation(summary = "Send now (members inside the cooldown are skipped)")
    @PostMapping("/{id}/run")
    public RunResponse run(@PathVariable Long id) {
        service.load(id);   // access check
        return runner.run(id, RunTrigger.MANUAL);
    }

    @Operation(summary = "Put a daily / weekly campaign on its schedule")
    @PostMapping("/{id}/activate")
    public CampaignResponse activate(@PathVariable Long id) {
        return service.activate(id);
    }

    @PostMapping("/{id}/pause")
    public CampaignResponse pause(@PathVariable Long id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/archive")
    public CampaignResponse archive(@PathVariable Long id) {
        return service.archive(id);
    }

    @GetMapping("/{id}/runs")
    public List<RunResponse> runs(@PathVariable Long id) {
        return service.runs(id);
    }

    @Operation(summary = "Recipients with attribution (converted, revenue, offer used)")
    @GetMapping("/{id}/recipients")
    public PageResponse<RecipientResponse> recipients(@PathVariable Long id,
                                                      @RequestParam(required = false) Boolean converted,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        PageRequests.of(page, size, null, Set.of(), Sort.unsorted());
        return service.recipients(id, converted, page, size);
    }
}
