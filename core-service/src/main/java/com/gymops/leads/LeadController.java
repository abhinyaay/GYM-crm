package com.gymops.leads;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.DatabaseConstraints;
import com.gymops.leads.dto.LeadDtos.ActivityRequest;
import com.gymops.leads.dto.LeadDtos.ActivityResponse;
import com.gymops.leads.dto.LeadDtos.AssignRequest;
import com.gymops.leads.dto.LeadDtos.CompleteFollowUpRequest;
import com.gymops.leads.dto.LeadDtos.ConvertRequest;
import com.gymops.leads.dto.LeadDtos.CreateRequest;
import com.gymops.leads.dto.LeadDtos.EnquiryAccepted;
import com.gymops.leads.dto.LeadDtos.EnquiryRequest;
import com.gymops.leads.dto.LeadDtos.FollowUpItem;
import com.gymops.leads.dto.LeadDtos.FollowUpRequest;
import com.gymops.leads.dto.LeadDtos.LeadDetailResponse;
import com.gymops.leads.dto.LeadDtos.LeadResponse;
import com.gymops.leads.dto.LeadDtos.OwnerOption;
import com.gymops.leads.dto.LeadDtos.PipelineSummary;
import com.gymops.leads.dto.LeadDtos.StartTrialRequest;
import com.gymops.leads.dto.LeadDtos.StatusRequest;
import com.gymops.leads.dto.LeadDtos.TrialResponse;
import com.gymops.leads.dto.LeadDtos.UpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Leads & trials")
@RestController
@RequestMapping("/api/v1/leads")
@RequiredArgsConstructor
public class LeadController {

    private static final String FRONT_DESK = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";
    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "nextFollowUpAt", "firstName", "status");
    private static final EnquiryAccepted ACCEPTED =
            new EnquiryAccepted("Thanks! Our team will contact you shortly to book your free trial.");

    private final LeadService service;
    private final LeadTrialService trialService;
    private final LeadJobs jobs;

    /**
     * Public website form (no login, rate limited per IP). Always answers 202 with the same message:
     * it never reveals whether the phone number was already known, and honeypot hits are dropped silently.
     */
    @Operation(summary = "Public 'book a free trial' enquiry")
    @PostMapping("/enquiry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EnquiryAccepted enquire(@Valid @RequestBody EnquiryRequest request) {
        if (request.website() != null && !request.website().isBlank()) {
            log.info("lead_enquiry_honeypot_dropped branchId={}", request.branchId());
            return ACCEPTED;
        }
        try {
            service.enquire(request);
        } catch (DataIntegrityViolationException e) {
            if (!DatabaseConstraints.isViolationOf(e, "ux_leads_open_phone")) {
                throw e;
            }
            service.enquire(request);   // a concurrent first enquiry created the lead: attach to it
        }
        return ACCEPTED;
    }

    @PostMapping
    @PreAuthorize(FRONT_DESK)
    public ResponseEntity<LeadResponse> create(@Valid @RequestBody CreateRequest request) {
        LeadResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/leads/" + created.id())).body(created);
    }

    @GetMapping
    @PreAuthorize(FRONT_DESK)
    public PageResponse<LeadResponse> search(@RequestParam(required = false) Long branchId,
                                             @RequestParam(required = false) Set<LeadStatus> status,
                                             @RequestParam(required = false) LeadSource source,
                                             @RequestParam(required = false) Long ownerUserId,
                                             @RequestParam(defaultValue = "false") boolean unassigned,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "false") boolean followUpDue,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String sort) {
        return service.search(branchId, status, source, ownerUserId, unassigned, q, followUpDue, createdFrom, createdTo,
                PageRequests.of(page, size, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Operation(summary = "Pipeline board counters")
    @GetMapping("/summary")
    @PreAuthorize(FRONT_DESK)
    public PipelineSummary summary(@RequestParam(required = false) Long branchId) {
        return service.summary(branchId);
    }

    @Operation(summary = "Open follow-ups due today or overdue (mine by default)")
    @GetMapping("/follow-ups")
    @PreAuthorize(FRONT_DESK)
    public List<FollowUpItem> followUps(@RequestParam(defaultValue = "true") boolean mine,
                                        @RequestParam(required = false) Long branchId) {
        return service.dueFollowUps(mine, branchId);
    }

    @Operation(summary = "Staff who can own leads at a branch")
    @GetMapping("/owners")
    @PreAuthorize(FRONT_DESK)
    public List<OwnerOption> owners(@RequestParam Long branchId) {
        return service.owners(branchId);
    }

    @GetMapping("/{id}")
    @PreAuthorize(FRONT_DESK)
    public LeadDetailResponse get(@PathVariable Long id) {
        return service.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(FRONT_DESK)
    public LeadResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/owner")
    @PreAuthorize(FRONT_DESK)
    public LeadResponse assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return service.assign(id, request.ownerUserId());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(FRONT_DESK)
    public LeadResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return service.changeStatus(id, request);
    }

    @PostMapping("/{id}/activities")
    @PreAuthorize(FRONT_DESK)
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse logActivity(@PathVariable Long id, @Valid @RequestBody ActivityRequest request) {
        return service.logActivity(id, request);
    }

    @PostMapping("/{id}/follow-ups")
    @PreAuthorize(FRONT_DESK)
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse scheduleFollowUp(@PathVariable Long id, @Valid @RequestBody FollowUpRequest request) {
        return service.scheduleFollowUp(id, request);
    }

    @PostMapping("/{id}/follow-ups/{activityId}/complete")
    @PreAuthorize(FRONT_DESK)
    public ActivityResponse completeFollowUp(@PathVariable Long id, @PathVariable Long activityId,
                                             @Valid @RequestBody(required = false) CompleteFollowUpRequest request) {
        return service.completeFollowUp(id, activityId, request);
    }

    @Operation(summary = "Start a trial: registers (or links) the member and subscribes them to a trial plan")
    @PostMapping("/{id}/trial")
    @PreAuthorize(FRONT_DESK)
    @ResponseStatus(HttpStatus.CREATED)
    public TrialResponse startTrial(@PathVariable Long id, @Valid @RequestBody StartTrialRequest request) {
        return trialService.startTrial(id, request);
    }

    @Operation(summary = "Mark a lead converted by linking it to an existing member")
    @PostMapping("/{id}/convert")
    @PreAuthorize(FRONT_DESK)
    public LeadDetailResponse convert(@PathVariable Long id, @Valid @RequestBody ConvertRequest request) {
        return trialService.convert(id, request);
    }

    @Operation(summary = "Run the lead jobs now (digest, trial-ending reminders, stale trials)")
    @PostMapping("/jobs/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public LeadJobs.RunSummary runJobs() {
        return jobs.runAll();
    }
}
