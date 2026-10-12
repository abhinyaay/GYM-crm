package com.gymops.access;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.attendance.AttendanceService;
import com.gymops.branch.GymBranch;
import com.gymops.branch.GymBranchRepository;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch-level gate settings. In {@link ExitMode#BUTTON} mode only entry is verified: the next entry closes a
 * member's previous visit, and visits nobody closed are ended {@code buttonExitVisitLength} after entry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchAccessSettingsService {

    private final AccessBranchSettingsRepository repository;
    private final GymBranchRepository branches;
    private final AttendanceService attendance;
    private final AccessPolicy access;
    private final AuditService audit;
    private final AccessProperties props;
    private final Clock clock;

    public record BranchAccessSettings(Long branchId, String branchCode, String branchName, ExitMode exitMode,
                                       boolean defaulted, long buttonExitVisitMinutes) {
    }

    public record UpdateRequest(@NotNull ExitMode exitMode) {
    }

    @Transactional(readOnly = true)
    public ExitMode exitMode(Long branchId) {
        return repository.findById(branchId).map(AccessBranchSettings::getExitMode).orElse(props.defaultExitMode());
    }

    @Transactional(readOnly = true)
    public List<BranchAccessSettings> list() {
        return branches.findAll().stream()
                .filter(b -> b.getDeletedAt() == null && access.canAccessBranch(b.getId()))
                .map(this::view).toList();
    }

    @Transactional
    public BranchAccessSettings update(Long branchId, UpdateRequest request) {
        GymBranch branch = branches.findById(branchId).orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
        access.assertBranchAccess(branchId);
        AccessBranchSettings settings = repository.findById(branchId).orElseGet(() -> new AccessBranchSettings(branchId));
        ExitMode before = settings.getExitMode() == null ? props.defaultExitMode() : settings.getExitMode();
        settings.update(request.exitMode(), CurrentUser.idOrNull(), Instant.now(clock));
        repository.save(settings);
        audit.record(AuditEvent.of("BRANCH", branchId, "EXIT_MODE_CHANGED").branch(branchId)
                .with("from", before).with("to", request.exitMode()));
        return view(branch);
    }

    /** Ends visits at BUTTON-mode branches that nobody closed (nobody scans out there). */
    public int closeUnexitedVisits() {
        Instant cutoff = Instant.now(clock).minus(props.buttonExitVisitLength());
        int closed = 0;
        for (GymBranch branch : branches.findAll()) {
            if (branch.getDeletedAt() != null || exitMode(branch.getId()) != ExitMode.BUTTON) {
                continue;
            }
            for (Long visitId : attendance.openVisitIdsBefore(branch.getId(), cutoff)) {
                try {
                    if (attendance.closeAssumedVisit(visitId, props.buttonExitVisitLength())) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not close visit {} at button-exit branch {}: {}", visitId, branch.getCode(), e.getMessage());
                }
            }
        }
        return closed;
    }

    private BranchAccessSettings view(GymBranch b) {
        var row = repository.findById(b.getId());
        return new BranchAccessSettings(b.getId(), b.getCode(), b.getName(),
                row.map(AccessBranchSettings::getExitMode).orElse(props.defaultExitMode()), row.isEmpty(),
                props.buttonExitVisitLength().toMinutes());
    }
}
