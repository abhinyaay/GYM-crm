package com.gymops.member;

import com.gymops.audit.AuditLogRepository;
import com.gymops.audit.AuditLogResponse;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.BranchStatus;
import com.gymops.branch.GymBranch;
import com.gymops.common.api.PageResponse;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.member.dto.EmergencyContactRequest;
import com.gymops.member.dto.MemberListItem;
import com.gymops.member.dto.MemberRequest;
import com.gymops.member.dto.MemberResponse;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.subscription.MembershipQueryService;
import com.gymops.subscription.dto.MembershipSummary;
import com.gymops.user.User;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository repository;
    private final MemberProfileReader profileReader;
    private final MembershipQueryService membershipQuery;
    private final BranchService branchService;
    private final AuditLogRepository auditLogRepository;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Walk-in registration by front-desk staff. */
    @Transactional
    public MemberResponse register(MemberRequest request) {
        access.assertBranchAccess(request.homeBranchId());
        Member member = create(request, null);
        return MemberResponse.from(member, MembershipSummary.none());
    }

    /** Self-service sign-up: creates the member profile linked to a freshly created MEMBER login. */
    @Transactional
    public Member registerForUser(MemberRequest request, User user) {
        return create(request, user);
    }

    @Transactional
    public MemberResponse update(Long id, MemberRequest request) {
        Member member = load(id);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        UserPrincipal caller = CurrentUser.get();
        if (!Objects.equals(member.getHomeBranch().getId(), request.homeBranchId())) {
            if (caller.isMemberOnly()) {
                throw new ForbiddenException("Members cannot change their home branch; ask the front desk");
            }
            access.assertBranchAccess(request.homeBranchId());
            member.setHomeBranch(activeBranch(request.homeBranchId()));
        }
        if (!member.getEmail().equalsIgnoreCase(request.email()) && repository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("A member with email " + request.email() + " already exists");
        }
        apply(member, request, !caller.isMemberOnly());
        audit.record(AuditEvent.of("MEMBER", id, "UPDATED").member(id).branch(member.getHomeBranch().getId()));
        events.publishEvent(new DomainEvents.MemberChanged(id));
        return MemberResponse.from(member, membershipQuery.currentMembership(id));
    }

    @Transactional
    public MemberResponse changeStatus(Long id, MemberStatus status, String reason) {
        Member member = load(id);
        access.assertBranchAccess(member.getHomeBranch().getId());
        MemberStatus previous = member.getStatus();
        member.changeStatus(status);
        audit.record(AuditEvent.of("MEMBER", id, "STATUS_CHANGED").member(id)
                .with("from", previous).with("to", status).with("reason", reason));
        events.publishEvent(new DomainEvents.MemberChanged(id));
        return MemberResponse.from(member, membershipQuery.currentMembership(id));
    }

    @Transactional
    public void delete(Long id) {
        Member member = load(id);
        access.assertBranchAccess(member.getHomeBranch().getId());
        MembershipSummary membership = membershipQuery.currentMembership(id);
        if (membership.state() != MembershipState.NONE) {
            throw new BusinessRuleException("Member has a " + membership.state()
                    + " membership; cancel it before deleting the member");
        }
        member.softDelete(Instant.now(clock));
        audit.record(AuditEvent.of("MEMBER", id, "DELETED").member(id));
        events.publishEvent(new DomainEvents.MemberChanged(id));
    }

    public MemberResponse get(Long id) {
        MemberResponse profile = profileReader.load(id);
        access.assertMemberAccess(profile.id(), profile.homeBranchId());
        return access.isSelf(id) && CurrentUser.get().isMemberOnly() ? profile.withoutStaffNotes() : profile;
    }

    public MemberResponse me() {
        UserPrincipal caller = CurrentUser.get();
        if (caller.memberId() == null) {
            throw new ResourceNotFoundException("Member profile for user", caller.userId());
        }
        return get(caller.memberId());
    }

    @Transactional(readOnly = true)
    public MemberResponse getByCode(String memberCode) {
        Member member = repository.findByMemberCode(memberCode.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberCode));
        return get(member.getId());
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberListItem> search(String query, Long branchId, MemberStatus status,
                                               MembershipState membership, LocalDate registeredFrom,
                                               LocalDate registeredTo, Pageable pageable) {
        BranchScope scope = access.scope(branchId);
        var filter = new MemberSpecifications.Filter(query, scope.all() ? null : scope.ids(), status, membership,
                registeredFrom == null ? null : registeredFrom.atStartOfDay(clock.getZone()).toInstant(),
                registeredTo == null ? null : registeredTo.plusDays(1).atStartOfDay(clock.getZone()).toInstant(),
                LocalDate.now(clock));
        Page<Member> page = repository.findAll(MemberSpecifications.matching(filter), pageable);
        var memberships = membershipQuery.currentMemberships(page.getContent().stream().map(Member::getId).toList());
        return PageResponse.of(page, m -> {
            MembershipSummary s = memberships.get(m.getId());
            return MemberListItem.from(m, s.state(), s.endDate());
        });
    }

    /** Timeline of everything that happened to a member (profile, subscriptions, payments...). */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> history(Long id, Pageable pageable) {
        Member member = load(id);
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        return PageResponse.of(auditLogRepository.findByMemberIdOrderByOccurredAtDesc(id, pageable), AuditLogResponse::from);
    }

    public Member load(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Member", id));
    }

    private Member create(MemberRequest request, User user) {
        if (repository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("A member with email " + request.email() + " already exists");
        }
        GymBranch branch = activeBranch(request.homeBranchId());
        String code = "M%06d".formatted(repository.nextMemberCodeValue());
        Member member = new Member(code, branch, request.firstName(), request.lastName(), request.email(),
                request.phone(), Instant.now(clock));
        member.setUser(user);
        apply(member, request, user == null);
        repository.saveAndFlush(member);
        audit.record(AuditEvent.of("MEMBER", member.getId(), "REGISTERED").member(member.getId())
                .branch(branch.getId()).with("memberCode", code).with("selfService", user != null));
        return member;
    }

    private GymBranch activeBranch(Long branchId) {
        GymBranch branch = branchService.load(branchId);
        if (branch.getStatus() != BranchStatus.ACTIVE) {
            throw new BusinessRuleException("Branch " + branch.getCode() + " is not accepting members");
        }
        return branch;
    }

    private void apply(Member m, MemberRequest r, boolean staffFields) {
        m.setFirstName(r.firstName().trim());
        m.setLastName(r.lastName().trim());
        m.setEmail(r.email().trim().toLowerCase());
        m.setPhone(r.phone());
        m.setDateOfBirth(r.dateOfBirth());
        m.setGender(r.gender());
        m.setAddressLine1(r.addressLine1());
        m.setCity(r.city());
        m.setState(r.state());
        m.setPostalCode(r.postalCode());
        if (staffFields) {
            m.setNotes(r.notes());
        }
        List<EmergencyContactRequest> contacts = r.emergencyContacts() == null ? List.of() : r.emergencyContacts();
        if (contacts.stream().filter(EmergencyContactRequest::primary).count() > 1) {
            throw new BusinessRuleException("Only one emergency contact can be primary");
        }
        if (m.getId() != null && !m.getEmergencyContacts().isEmpty()) {
            // Hibernate flushes inserts before deletes; remove the old rows first so the new primary
            // contact does not collide with the old one on ux_emergency_contact_primary.
            m.replaceEmergencyContacts(List.of());
            repository.flush();
        }
        m.replaceEmergencyContacts(contacts.stream()
                .map(c -> new EmergencyContact(c.name(), c.relationship(), c.phone(), c.email(), c.primary()))
                .toList());
    }
}
