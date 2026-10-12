package com.gymops.dashboard;

import com.gymops.dashboard.dto.AdminDashboardResponse;
import com.gymops.dashboard.dto.MemberDashboardResponse;
import com.gymops.dashboard.dto.TrainerDashboardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboards")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @Operation(summary = "Admin/manager KPIs (Redis-cached 60s; churn from the analytics service, degrades gracefully)")
    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
    public AdminDashboardResponse admin(@RequestParam(required = false) Long branchId) {
        return service.admin(branchId);
    }

    @Operation(summary = "Trainer dashboard (own, or a trainerId for managers)")
    @GetMapping("/trainer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','TRAINER')")
    public TrainerDashboardResponse trainer(@RequestParam(required = false) Long trainerId) {
        return service.trainer(trainerId);
    }

    @Operation(summary = "Member dashboard (own, or a memberId for staff)")
    @GetMapping("/member")
    public MemberDashboardResponse member(@RequestParam(required = false) Long memberId) {
        return service.member(memberId);
    }
}
