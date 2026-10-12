package com.gymops.leads;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.BranchStatus;
import com.gymops.branch.GymBranch;
import com.gymops.branch.StaffAssignmentRepository;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.leads.dto.LeadDtos.ActivityRequest;
import com.gymops.leads.dto.LeadDtos.ActivityResponse;
import com.gymops.leads.dto.LeadDtos.CompleteFollowUpRequest;
import com.gymops.leads.dto.LeadDtos.CreateRequest;
import com.gymops.leads.dto.LeadDtos.EnquiryRequest;
import com.gymops.leads.dto.LeadDtos.FollowUpItem;
import com.gymops.leads.dto.LeadDtos.FollowUpRequest;
import com.gymops.leads.dto.LeadDtos.LeadDetailResponse;
import com.gymops.leads.dto.LeadDtos.LeadResponse;
import com.gymops.leads.dto.LeadDtos.MemberLink;
import com.gymops.leads.dto.LeadDtos.OwnerOption;
import com.gymops.leads.dto.LeadDtos.PipelineSummary;
import com.gymops.leads.dto.LeadDtos.StatusRequest;
import com.gymops.leads.dto.LeadDtos.UpdateRequest;
import com.gymops.member.MemberRepository;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.user.RoleName;
import com.gymops.user.User;
import com.gymops.user.UserRepository;
import com.gymops.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales pipeline: public enquiries, lead capture, ownership, status workflow, activity timeline and
 * follow-up tasks. Every staff operation is scoped to the caller's branches through {@link AccessPolicy}.
 * Trials and conversions live in {@link LeadTrialService}.
 */
@Service
@RequiredArgsConstructor
public class LeadService {

    /** Staff roles that can own (work) a lead at their branch; org-wide admins may always own one. */
    static final Set<RoleName> OWNER_ROLES = EnumSet.of(RoleName.MANAGER, RoleName.RECEPTIONIST);
    private static final Duration DUE_AT_TOLERANCE = Duration.ofMinutes(5);

    private final LeadRepository repository;
    private final LeadActivityRepository activityRepository;
    private final BranchService branchService;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final UserRepository userRepository;
    private final MemberRepository memberRepository;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ================================================================== public enquiry

    /**
     * Records a website enquiry. A repeat enquiry from a phone number that already has an open lead at
     * the branch is attached to that lead as a note, so the response never reveals whether it existed.
     * Callers retry once on a {@code ux_leads_open_phone} race (two simultaneous first enquiries).
     */
    @Transactional
    public void enquire(EnquiryRequest r) {
        GymBranch branch = branchService.load(r.branchId());
        if (branch.getStatus() != BranchStatus.ACTIVE) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "This branch is not accepting enquiries");
        }
        Instant now = Instant.now(clock);
        String phone = Lead.normalizePhone(r.phone());
        LeadInterest interest = r.interest() == null ? LeadInterest.MEMBERSHIP : r.interest();
        String message = r.message() == null || r.message().isBlank() ? "" : ": " + r.message().trim();
        var existing = repository.findOpenByBranchAndPhone(branch.getId(), phone);
        if (existing.isPresent()) {
            Lead lead = existing.get();
            lead.enrichFromEnquiry(r.email(), r.lastName());
            activityRepository.save(LeadActivity.log(lead, LeadActivityType.NOTE,
                    "Repeat website enquiry (" + interest + ")" + message, null, now));
            audit.record(AuditEvent.of("LEAD", lead.getId(), "ENQUIRY_MERGED").branch(branch.getId()));
            publishReferral(lead, r);
            return;
        }
        LeadSource source = r.hasReferralCode() ? LeadSource.REFERRAL : LeadSource.WEBSITE;
        Lead lead = repository.saveAndFlush(new Lead(branch, r.firstName(), r.lastName(), r.email(), phone,
                source, interest, null));
        activityRepository.save(LeadActivity.log(lead, LeadActivityType.NOTE,
                "Website enquiry (" + interest + ")" + message, null, now));
        audit.record(AuditEvent.of("LEAD", lead.getId(), "ENQUIRY_RECEIVED").branch(branch.getId())
                .with("source", source).with("interest", interest));
        publishReferral(lead, r);
    }

    private void publishReferral(Lead lead, EnquiryRequest r) {
        if (r.hasReferralCode()) {
            events.publishEvent(new LeadEvents.ReferralCodeSubmitted(lead.getId(), r.referralCode().trim()));
        }
    }

    // ================================================================== staff: capture & maintain

    @Transactional
    public LeadResponse create(CreateRequest r) {
        access.assertBranchAccess(r.branchId());
        GymBranch branch = branchService.load(r.branchId());
        if (branch.getStatus() != BranchStatus.ACTIVE) {
            throw new BusinessRuleException("Branch " + branch.getCode() + " is not active");
        }
        String phone = Lead.normalizePhone(r.phone());
        rejectDuplicateOpenLead(branch.getId(), phone, null);
        UserPrincipal caller = CurrentUser.get();
        Long owner = r.ownerUserId() != null ? requireEligibleOwner(branch.getId(), r.ownerUserId())
                : defaultOwner(caller, branch.getId());
        Lead lead = new Lead(branch, r.firstName(), r.lastName(), r.email(), phone, r.source(), r.interest(), r.notes());
        if (owner != null) {
            lead.assignOwner(owner);
        }
        repository.saveAndFlush(lead);
        Instant now = Instant.now(clock);
        activityRepository.save(LeadActivity.log(lead, LeadActivityType.STATUS_CHANGE,
                "Lead created (" + r.source() + ")", caller.userId(), now));
        audit.record(AuditEvent.of("LEAD", lead.getId(), "CREATED").branch(branch.getId())
                .with("source", r.source()).with("owner", owner));
        if (owner != null && !owner.equals(caller.userId())) {
            events.publishEvent(new LeadEvents.LeadAssigned(lead.getId(), owner));
        }
        return toResponse(lead, now);
    }

    @Transactional
    public LeadResponse update(Long id, UpdateRequest r) {
        Lead lead = loadForStaff(id);
        String phone = Lead.normalizePhone(r.phone());
        if (lead.isOpen() && !phone.equals(lead.getPhone())) {
            rejectDuplicateOpenLead(lead.getBranch().getId(), phone, lead.getId());
        }
        lead.updateDetails(r.firstName(), r.lastName(), r.email(), r.phone(), r.source(), r.interest(), r.notes());
        repository.saveAndFlush(lead);
        audit.record(AuditEvent.of("LEAD", id, "UPDATED").branch(lead.getBranch().getId()).member(lead.getMemberId()));
        return toResponse(lead, Instant.now(clock));
    }

    @Transactional
    public LeadResponse assign(Long id, Long ownerUserId) {
        Lead lead = loadForStaff(id);
        Long previous = lead.getOwnerUserId();
        Long owner = ownerUserId == null ? null : requireEligibleOwner(lead.getBranch().getId(), ownerUserId);
        lead.assignOwner(owner);
        Instant now = Instant.now(clock);
        UserPrincipal caller = CurrentUser.get();
        String ownerName = owner == null ? "nobody" : userNames(List.of(owner)).getOrDefault(owner, "user " + owner);
        activityRepository.save(LeadActivity.log(lead, LeadActivityType.NOTE, "Assigned to " + ownerName,
                caller.userId(), now));
        audit.record(AuditEvent.of("LEAD", id, "ASSIGNED").branch(lead.getBranch().getId())
                .with("from", previous).with("to", owner));
        if (owner != null && !owner.equals(previous) && !owner.equals(caller.userId())) {
            events.publishEvent(new LeadEvents.LeadAssigned(id, owner));
        }
        return toResponse(lead, now);
    }

    /** Plain workflow moves: contacted, tour scheduled, lost (with reason) and reopen. */
    @Transactional
    public LeadResponse changeStatus(Long id, StatusRequest r) {
        Lead lead = loadForStaff(id);
        LeadStatus from = lead.getStatus();
        lead.moveTo(r.status(), r.lostReason());
        if (r.status() == LeadStatus.NEW) {
            lead.scheduleNextFollowUp(activityRepository.earliestOpenFollowUp(id).orElse(null));
        }
        repository.saveAndFlush(lead);   // a reopened lead may collide with a newer open lead (ux_leads_open_phone)
        Instant now = Instant.now(clock);
        StringBuilder body = new StringBuilder(from + " → " + lead.getStatus());
        if (lead.getLostReason() != null) {
            body.append(" (").append(lead.getLostReason()).append(')');
        }
        if (r.note() != null && !r.note().isBlank()) {
            body.append(": ").append(r.note().trim());
        }
        activityRepository.save(LeadActivity.log(lead, LeadActivityType.STATUS_CHANGE, body.toString(),
                CurrentUser.get().userId(), now));
        audit.record(AuditEvent.of("LEAD", id, "STATUS_CHANGED").branch(lead.getBranch().getId())
                .member(lead.getMemberId()).with("from", from).with("to", lead.getStatus())
                .with("lostReason", lead.getLostReason()));
        return toResponse(lead, now);
    }

    // ================================================================== timeline & follow-ups

    @Transactional
    public ActivityResponse logActivity(Long id, ActivityRequest r) {
        if (!LeadActivityType.LOGGABLE.contains(r.type())) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED,
                    "Activity type " + r.type() + " cannot be logged directly; allowed: " + LeadActivityType.LOGGABLE);
        }
        Lead lead = loadForStaff(id);
        Instant now = Instant.now(clock);
        Long caller = CurrentUser.get().userId();
        LeadActivity activity = activityRepository.save(LeadActivity.log(lead, r.type(), r.body(), caller, now));
        if (r.type().isContact() && lead.recordContact()) {
            activityRepository.save(LeadActivity.log(lead, LeadActivityType.STATUS_CHANGE,
                    LeadStatus.NEW + " → " + LeadStatus.CONTACTED, caller, now.plusMillis(1)));
            audit.record(AuditEvent.of("LEAD", id, "STATUS_CHANGED").branch(lead.getBranch().getId())
                    .with("from", LeadStatus.NEW).with("to", LeadStatus.CONTACTED).with("via", r.type()));
        }
        return ActivityResponse.from(activity, userNames(List.of(caller)), now);
    }

    @Transactional
    public ActivityResponse scheduleFollowUp(Long id, FollowUpRequest r) {
        Lead lead = loadForStaff(id);
        lead.requireOpen("schedule a follow-up for");
        Instant now = Instant.now(clock);
        if (r.dueAt().isBefore(now.minus(DUE_AT_TOLERANCE))) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "dueAt must be in the future");
        }
        Long caller = CurrentUser.get().userId();
        LeadActivity followUp = activityRepository.save(LeadActivity.followUp(lead, r.body(), r.dueAt(), caller, now));
        refreshNextFollowUp(lead);
        return ActivityResponse.from(followUp, userNames(List.of(caller)), now);
    }

    @Transactional
    public ActivityResponse completeFollowUp(Long id, Long activityId, CompleteFollowUpRequest r) {
        Lead lead = loadForStaff(id);
        LeadActivity followUp = activityRepository.findByIdAndLeadId(activityId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Follow-up", activityId));
        Instant now = Instant.now(clock);
        followUp.complete(now);
        Long caller = CurrentUser.get().userId();
        if (r != null && r.outcome() != null && !r.outcome().isBlank()) {
            activityRepository.save(LeadActivity.log(lead, LeadActivityType.NOTE,
                    "Follow-up done: " + r.outcome().trim(), caller, now));
        }
        refreshNextFollowUp(lead);
        return ActivityResponse.from(followUp, userNames(Stream.of(followUp.getCreatedBy(), caller).toList()), now);
    }

    /** Open follow-ups due by the end of today (business timezone), overdue ones first. */
    @Transactional(readOnly = true)
    public List<FollowUpItem> dueFollowUps(boolean mineOnly, Long branchId) {
        UserPrincipal caller = CurrentUser.get();
        BranchScope scope = access.scope(branchId);
        Instant now = Instant.now(clock);
        List<LeadActivity> due = activityRepository.findDueFollowUps(startOfDay(today().plusDays(1)),
                mineOnly ? caller.userId() : null, scope.all(), scope.ids());
        Map<Long, String> names = userNames(due.stream().map(a -> a.getLead().getOwnerUserId()).toList());
        return due.stream().map(a -> {
            Lead l = a.getLead();
            return new FollowUpItem(a.getId(), l.getId(), l.fullName(), l.getPhone(), l.getStatus(), l.getBranch().getId(),
                    l.getOwnerUserId(), l.getOwnerUserId() == null ? null : names.get(l.getOwnerUserId()), a.getBody(),
                    a.getDueAt(), a.isOverdue(now));
        }).toList();
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> search(Long branchId, Set<LeadStatus> statuses, LeadSource source, Long ownerUserId,
                                             boolean unassigned, String query, boolean followUpDue, LocalDate createdFrom,
                                             LocalDate createdTo, Pageable pageable) {
        BranchScope scope = access.scope(branchId);
        var filter = new LeadSpecifications.Filter(scope.all() ? null : scope.ids(), statuses, source, ownerUserId,
                unassigned, query, followUpDue ? startOfDay(today().plusDays(1)) : null,
                createdFrom == null ? null : startOfDay(createdFrom),
                createdTo == null ? null : startOfDay(createdTo.plusDays(1)));
        Page<Lead> page = repository.findAll(LeadSpecifications.matching(filter), pageable);
        Map<Long, String> names = userNames(page.getContent().stream().map(Lead::getOwnerUserId).toList());
        Instant now = Instant.now(clock);
        return PageResponse.of(page, l -> LeadResponse.from(l, names, now));
    }

    @Transactional(readOnly = true)
    public LeadDetailResponse detail(Long id) {
        Lead lead = loadForStaff(id);
        return detailOf(lead);
    }

    LeadDetailResponse detailOf(Lead lead) {
        Instant now = Instant.now(clock);
        List<LeadActivity> activities = activityRepository.findByLeadIdOrderByCreatedAtDescIdDesc(lead.getId());
        List<Long> userIds = new ArrayList<>(activities.stream().map(LeadActivity::getCreatedBy).toList());
        userIds.add(lead.getOwnerUserId());
        Map<Long, String> names = userNames(userIds);
        MemberLink member = lead.getMemberId() == null ? null : memberRepository.findById(lead.getMemberId())
                .map(m -> new MemberLink(m.getId(), m.getMemberCode(), m.fullName())).orElse(null);
        return new LeadDetailResponse(LeadResponse.from(lead, names, now),
                activities.stream().map(a -> ActivityResponse.from(a, names, now)).toList(),
                lead.getStatus().manualTargets(), lead.getStatus().canTransitionTo(LeadStatus.TRIAL_ACTIVE),
                lead.getStatus().canTransitionTo(LeadStatus.CONVERTED), member);
    }

    /** Staff who can own leads at a branch (managers and front desk assigned there, plus an org-wide caller). */
    @Transactional(readOnly = true)
    public List<OwnerOption> owners(Long branchId) {
        access.assertBranchAccess(branchId);
        Map<Long, OwnerOption> options = new LinkedHashMap<>();
        staffAssignmentRepository.findByBranchIdWithUser(branchId).stream()
                .map(a -> a.getUser())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE && u.roleNames().stream().anyMatch(OWNER_ROLES::contains))
                .forEach(u -> options.put(u.getId(), new OwnerOption(u.getId(), u.getFullName())));
        UserPrincipal caller = CurrentUser.get();
        if (caller.isOrgWide()) {
            userRepository.findById(caller.userId())
                    .ifPresent(u -> options.putIfAbsent(u.getId(), new OwnerOption(u.getId(), u.getFullName())));
        }
        return List.copyOf(options.values());
    }

    @Transactional(readOnly = true)
    public PipelineSummary summary(Long branchId) {
        BranchScope scope = access.scope(branchId);
        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        Arrays.stream(LeadStatus.values()).forEach(s -> byStatus.put(s, 0L));
        repository.countByStatus(scope.all(), scope.ids()).forEach(c -> byStatus.put(c.getStatus(), c.getCount()));
        long open = LeadStatus.OPEN.stream().mapToLong(byStatus::get).sum();

        LocalDate today = today();
        Instant startOfToday = startOfDay(today);
        Long me = CurrentUser.get().userId();
        List<LeadActivity> due = activityRepository.findDueFollowUps(startOfDay(today.plusDays(1)), null,
                scope.all(), scope.ids());
        long overdue = due.stream().filter(a -> a.getDueAt().isBefore(startOfToday)).count();
        long mine = due.stream().filter(a -> me.equals(a.getLead().getOwnerUserId())).count();
        Instant monthStart = startOfDay(today.withDayOfMonth(1));
        long trialsEndingSoon = repository.count(LeadSpecifications.matching(new LeadSpecifications.Filter(
                scope.all() ? null : scope.ids(), EnumSet.of(LeadStatus.TRIAL_ACTIVE), null, null, false, null, null,
                null, null)).and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("trialEndsOn"), today.plusDays(2))));
        return new PipelineSummary(byStatus, open, due.size() - overdue, overdue, mine,
                repository.countConvertedSince(scope.all(), scope.ids(), monthStart),
                repository.countCreatedSince(scope.all(), scope.ids(), monthStart), trialsEndingSoon);
    }

    // ================================================================== shared helpers

    public Lead loadForStaff(Long id) {
        Lead lead = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Lead", id));
        access.assertBranchAccess(lead.getBranch().getId());
        return lead;
    }

    LeadResponse toResponse(Lead lead, Instant now) {
        return LeadResponse.from(lead, userNames(java.util.Collections.singletonList(lead.getOwnerUserId())), now);
    }

    void refreshNextFollowUp(Lead lead) {
        activityRepository.flush();
        lead.scheduleNextFollowUp(activityRepository.earliestOpenFollowUp(lead.getId()).orElse(null));
    }

    /** Batch id -> display name lookup so clients never need staff-directory access to show names. */
    Map<Long, String> userNames(Collection<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(distinct).forEach(u -> names.put(u.getId(), u.getFullName()));
        return names;
    }

    private Long requireEligibleOwner(Long branchId, Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessRuleException("User " + userId + " is not active");
        }
        Set<RoleName> roles = user.roleNames();
        boolean orgWide = roles.stream().anyMatch(RoleName.ORG_WIDE::contains);
        boolean branchStaff = roles.stream().anyMatch(OWNER_ROLES::contains)
                && staffAssignmentRepository.findBranchIdsByUserId(userId).contains(branchId);
        if (!orgWide && !branchStaff) {
            throw new BusinessRuleException("Lead owner must be a manager or front-desk member of branch " + branchId);
        }
        return userId;
    }

    private static Long defaultOwner(UserPrincipal caller, Long branchId) {
        boolean worksHere = caller.hasAnyRole(OWNER_ROLES) && caller.branchIds().contains(branchId);
        return worksHere ? caller.userId() : null;
    }

    private void rejectDuplicateOpenLead(Long branchId, String phone, Long exceptLeadId) {
        repository.findOpenByBranchAndPhone(branchId, phone)
                .filter(other -> !other.getId().equals(exceptLeadId))
                .ifPresent(other -> {
                    throw new ConflictException("Lead #" + other.getId() + " (" + other.fullName()
                            + ") is already open for this phone number at this branch");
                });
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private Instant startOfDay(LocalDate date) {
        ZoneId zone = clock.getZone();
        return date.atStartOfDay(zone).toInstant();
    }
}
