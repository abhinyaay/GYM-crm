package com.gymops.branch;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.dto.BranchRequest;
import com.gymops.branch.dto.BranchResponse;
import com.gymops.branch.dto.BranchSummary;
import com.gymops.branch.dto.FacilityRequest;
import com.gymops.branch.dto.OperatingHoursRequest;
import com.gymops.branch.dto.StaffResponse;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.pricing.gst.GstRegistration;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import com.gymops.user.RoleName;
import com.gymops.user.User;
import com.gymops.user.UserRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BranchService {

    private final GymBranchRepository branchRepository;
    private final StaffAssignmentRepository staffRepository;
    private final UserRepository userRepository;
    private final AccessPolicy access;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public BranchResponse create(BranchRequest request) {
        if (branchRepository.existsByCode(request.code())) {
            throw new ConflictException("A branch with code " + request.code() + " already exists");
        }
        GymBranch branch = new GymBranch(request.code(), request.name());
        apply(branch, request);
        branchRepository.save(branch);
        audit.record(AuditEvent.of("BRANCH", branch.getId(), "CREATED").branch(branch.getId())
                .with("code", branch.getCode()));
        return BranchResponse.from(branch);
    }

    @Transactional
    public BranchResponse update(Long id, BranchRequest request) {
        GymBranch branch = load(id);
        if (!branch.getCode().equalsIgnoreCase(request.code()) && branchRepository.existsByCode(request.code())) {
            throw new ConflictException("A branch with code " + request.code() + " already exists");
        }
        branch.setCode(request.code().trim().toUpperCase());
        branch.setName(request.name());
        apply(branch, request);
        audit.record(AuditEvent.of("BRANCH", id, "UPDATED").branch(id));
        return BranchResponse.from(branch);
    }

    @Transactional
    public BranchResponse changeStatus(Long id, BranchStatus status) {
        GymBranch branch = load(id);
        BranchStatus previous = branch.getStatus();
        branch.changeStatus(status);
        audit.record(AuditEvent.of("BRANCH", id, "STATUS_CHANGED").branch(id)
                .with("from", previous).with("to", status));
        return BranchResponse.from(branch);
    }

    @Transactional
    public void delete(Long id) {
        GymBranch branch = load(id);
        if (branchRepository.countActiveMembers(id) > 0) {
            throw new BusinessRuleException("Branch still has active members; transfer them before deleting the branch");
        }
        branch.softDelete(Instant.now(clock));
        audit.record(AuditEvent.of("BRANCH", id, "DELETED").branch(id));
    }

    @Transactional(readOnly = true)
    public BranchResponse get(Long id) {
        access.assertBranchAccess(id);
        return BranchResponse.from(load(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<BranchResponse> list(Pageable pageable) {
        return access.branchScope(null)
                .map(ids -> PageResponse.of(branchRepository.findByIdIn(AccessPolicy.orNoMatch(ids), pageable),
                        BranchResponse::from))
                .orElseGet(() -> PageResponse.of(branchRepository.findAll(pageable), BranchResponse::from));
    }

    @Transactional(readOnly = true)
    public List<BranchSummary> listPublic() {
        return branchRepository.findByStatusOrderByNameAsc(BranchStatus.ACTIVE).stream().map(BranchSummary::from).toList();
    }

    @Transactional
    public BranchResponse replaceOperatingHours(Long id, OperatingHoursRequest request) {
        access.assertBranchAccess(id);
        GymBranch branch = load(id);
        branch.replaceOperatingHours(request.days().stream()
                .map(d -> new OperatingHours(d.dayOfWeek(), d.openTime(), d.closeTime(), d.closed()))
                .toList());
        audit.record(AuditEvent.of("BRANCH", id, "OPERATING_HOURS_CHANGED").branch(id));
        return BranchResponse.from(branch);
    }

    @Transactional
    public BranchResponse addFacility(Long id, FacilityRequest request) {
        access.assertBranchAccess(id);
        GymBranch branch = load(id);
        boolean exists = branch.getFacilities().stream().anyMatch(f -> f.getName().equalsIgnoreCase(request.name()));
        if (exists) {
            throw new ConflictException("Facility '" + request.name() + "' already exists at this branch");
        }
        Facility facility = branch.addFacility(request.name(), request.description());
        if (request.available() != null) {
            facility.setAvailable(request.available());
        }
        branchRepository.flush();   // assign the new facility's id before building the response
        audit.record(AuditEvent.of("BRANCH", id, "FACILITY_ADDED").branch(id).with("facility", request.name()));
        return BranchResponse.from(branch);
    }

    @Transactional
    public BranchResponse updateFacility(Long id, Long facilityId, FacilityRequest request) {
        access.assertBranchAccess(id);
        GymBranch branch = load(id);
        Facility facility = branch.facility(facilityId).orElseThrow(() -> new ResourceNotFoundException("Facility", facilityId));
        facility.setName(request.name());
        facility.setDescription(request.description());
        if (request.available() != null) {
            facility.setAvailable(request.available());
        }
        audit.record(AuditEvent.of("BRANCH", id, "FACILITY_UPDATED").branch(id).with("facilityId", facilityId)
                .with("available", facility.isAvailable()));
        return BranchResponse.from(branch);
    }

    @Transactional
    public void removeFacility(Long id, Long facilityId) {
        access.assertBranchAccess(id);
        GymBranch branch = load(id);
        Facility facility = branch.facility(facilityId).orElseThrow(() -> new ResourceNotFoundException("Facility", facilityId));
        branch.getFacilities().remove(facility);
        audit.record(AuditEvent.of("BRANCH", id, "FACILITY_REMOVED").branch(id).with("facilityId", facilityId));
    }

    @Transactional
    public StaffResponse assignStaff(Long branchId, Long userId) {
        GymBranch branch = load(branchId);
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (!user.roleNames().stream().anyMatch(RoleName.BRANCH_SCOPED_STAFF::contains)) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "Only MANAGER, TRAINER or RECEPTIONIST users can be assigned to a branch");
        }
        if (staffRepository.findByUserIdAndBranchId(userId, branchId).isPresent()) {
            throw new ConflictException("User is already assigned to this branch");
        }
        StaffAssignment assignment = staffRepository.save(
                new StaffAssignment(user, branch, Instant.now(clock), CurrentUser.idOrNull()));
        audit.record(AuditEvent.of("BRANCH", branchId, "STAFF_ASSIGNED").branch(branchId).with("userId", userId));
        return StaffResponse.from(assignment);
    }

    @Transactional
    public void unassignStaff(Long branchId, Long userId) {
        StaffAssignment assignment = staffRepository.findByUserIdAndBranchId(userId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff assignment for user", userId));
        staffRepository.delete(assignment);
        audit.record(AuditEvent.of("BRANCH", branchId, "STAFF_UNASSIGNED").branch(branchId).with("userId", userId));
    }

    @Transactional(readOnly = true)
    public List<StaffResponse> listStaff(Long branchId) {
        access.assertBranchAccess(branchId);
        load(branchId);
        return staffRepository.findByBranchIdWithUser(branchId).stream().map(StaffResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public Set<Long> branchIdsForUser(Long userId) {
        return staffRepository.findBranchIdsByUserId(userId);
    }

    public GymBranch load(Long id) {
        return branchRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Branch", id));
    }

    private static void apply(GymBranch branch, BranchRequest r) {
        try {
            ZoneId.of(r.timezone());
        } catch (DateTimeException e) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Unknown timezone '" + r.timezone() + "'");
        }
        branch.setPhone(r.phone());
        branch.setEmail(r.email());
        branch.setAddressLine1(r.addressLine1());
        branch.setAddressLine2(r.addressLine2());
        branch.setCity(r.city());
        branch.setState(r.state());
        branch.setPostalCode(r.postalCode());
        branch.setCountry(r.country() == null ? "IN" : r.country());
        branch.setTimezone(r.timezone());
        branch.setCapacity(r.capacity());
        GstRegistration gst = GstRegistration.resolve(r.gstin(), r.gstStateCode(), r.state());
        branch.setGstin(gst.gstin());
        branch.setGstStateCode(gst.stateCode());
    }
}
