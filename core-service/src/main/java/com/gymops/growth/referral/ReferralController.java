package com.gymops.growth.referral;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.growth.GrowthJobs;
import com.gymops.growth.referral.ReferralDtos.AttachRequest;
import com.gymops.growth.referral.ReferralDtos.ClaimRequest;
import com.gymops.growth.referral.ReferralDtos.MemberReferralSummary;
import com.gymops.growth.referral.ReferralDtos.MyReferralDashboard;
import com.gymops.growth.referral.ReferralDtos.PublicReferral;
import com.gymops.growth.referral.ReferralDtos.ReferralResponse;
import com.gymops.growth.referral.ReferralDtos.ReferralSettings;
import com.gymops.growth.referral.ReferralDtos.ReferralStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Referrals")
@RestController
@RequiredArgsConstructor
public class ReferralController {

    private static final String MANAGERS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')";
    private static final String FRONT_DESK = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST')";

    private final ReferralService service;
    private final ReferralQueries queries;
    private final GrowthJobs jobs;

    // ------------------------------------------------------------------ public

    @Operation(summary = "Public referral-code lookup (referrer's first name and the offer only)")
    @GetMapping("/api/v1/public/referrals/{code}")
    public PublicReferral lookup(@PathVariable String code) {
        return service.lookup(code);
    }

    // ------------------------------------------------------------------ member

    @Operation(summary = "My referral code, share link, referrals and rewards")
    @GetMapping("/api/v1/referrals/me")
    @PreAuthorize("hasRole('MEMBER')")
    public MyReferralDashboard me() {
        return service.myDashboard();
    }

    @Operation(summary = "Claim the referral code I was given (new members, e.g. right after self sign-up)")
    @PostMapping("/api/v1/referrals/me/claim")
    @PreAuthorize("hasRole('MEMBER')")
    public MyReferralDashboard claim(@Valid @RequestBody ClaimRequest request) {
        return service.claim(request.code());
    }

    // ------------------------------------------------------------------ staff

    @Operation(summary = "Attach a referral code to a new member or an open lead (front desk)")
    @PostMapping("/api/v1/referrals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FRONT_DESK)
    public ReferralResponse attach(@Valid @RequestBody AttachRequest request) {
        return service.attach(request);
    }

    @Operation(summary = "Referrals in the caller's branches (newest first)")
    @GetMapping("/api/v1/referrals")
    @PreAuthorize(FRONT_DESK)
    public PageResponse<ReferralResponse> list(@RequestParam(required = false) Long branchId,
                                               @RequestParam(required = false) ReferralStatus status,
                                               @RequestParam(required = false) Long referrerMemberId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        PageRequests.of(page, size, null, Set.of(), Sort.unsorted());   // validates bounds
        return queries.list(branchId, status, referrerMemberId, page, size);
    }

    @GetMapping("/api/v1/referrals/{id}")
    @PreAuthorize(FRONT_DESK)
    public ReferralResponse get(@PathVariable Long id) {
        return queries.get(id);
    }

    @Operation(summary = "Programme statistics: referrals, conversions, rewards issued / pending / reversed, top referrers")
    @GetMapping("/api/v1/referrals/stats")
    @PreAuthorize(MANAGERS)
    public ReferralStats stats(@RequestParam(required = false) Long branchId) {
        return queries.stats(branchId);
    }

    @Operation(summary = "Programme settings (configured in app.growth.referral)")
    @GetMapping("/api/v1/referrals/settings")
    @PreAuthorize(FRONT_DESK)
    public ReferralSettings settings() {
        return service.settings();
    }

    @Operation(summary = "A member's referral code and activity (code generated on first use)")
    @GetMapping("/api/v1/referrals/members/{memberId}")
    @PreAuthorize(FRONT_DESK)
    public MemberReferralSummary member(@PathVariable Long memberId) {
        return service.memberSummary(memberId);
    }

    @Operation(summary = "Run the growth job now: release due rewards, run due campaigns, conversion sweep")
    @PostMapping("/api/v1/growth/jobs/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public GrowthJobs.RunSummary runJobs() {
        return jobs.runAll();
    }
}
