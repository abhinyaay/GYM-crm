package com.gymops.complaint;

import com.gymops.audit.AuditLogRepository;
import com.gymops.audit.AuditLogResponse;
import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.branch.BranchService;
import com.gymops.branch.StaffAssignmentRepository;
import com.gymops.common.api.PageResponse;
import com.gymops.common.event.DomainEvents;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.complaint.dto.ComplaintDtos.CommentRequest;
import com.gymops.complaint.dto.ComplaintDtos.CommentResponse;
import com.gymops.complaint.dto.ComplaintDtos.ComplaintResponse;
import com.gymops.complaint.dto.ComplaintDtos.CreateRequest;
import com.gymops.complaint.dto.ComplaintDtos.StatusRequest;
import com.gymops.config.AppProperties;
import com.gymops.member.Member;
import com.gymops.member.MemberService;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import com.gymops.security.UserPrincipal;
import com.gymops.user.RoleName;
import com.gymops.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository repository;
    private final ComplaintCommentRepository commentRepository;
    private final AuditLogRepository auditLogRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final UserRepository userRepository;
    private final MemberService memberService;
    private final BranchService branchService;
    private final AccessPolicy access;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    @Transactional
    public ComplaintResponse create(CreateRequest r) {
        UserPrincipal caller = CurrentUser.get();
        Member member;
        Long branchId;
        if (caller.isMemberOnly()) {
            member = memberService.load(caller.memberId());
            branchId = member.getHomeBranch().getId();
        } else {
            member = r.memberId() == null ? null : memberService.load(r.memberId());
            branchId = r.branchId() != null ? r.branchId() : member != null ? member.getHomeBranch().getId() : null;
            if (branchId == null) {
                throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "branchId or memberId is required");
            }
            access.assertBranchAccess(branchId);
        }
        ComplaintPriority priority = r.priority() != null ? r.priority() : ComplaintPriority.MEDIUM;
        Instant now = Instant.now(clock);
        Complaint complaint = repository.save(new Complaint(nextTicketNumber(), member, branchService.load(branchId),
                caller.userId(), r.category(), priority, r.subject(), r.description(), now.plus(sla(priority))));
        audit.record(AuditEvent.of("COMPLAINT", complaint.getId(), "CREATED").branch(branchId)
                .member(member == null ? null : member.getId()).with("ticket", complaint.getTicketNumber())
                .with("category", r.category()).with("priority", priority));
        return toResponse(complaint, now);
    }

    @Transactional(readOnly = true)
    public ComplaintResponse get(Long id) {
        Complaint c = loadVisible(id);
        return toResponse(c, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public PageResponse<ComplaintResponse> search(Long branchId, ComplaintStatus status, ComplaintPriority priority,
                                                  ComplaintCategory category, Long assignedTo, boolean slaBreachedOnly,
                                                  Pageable pageable) {
        UserPrincipal caller = CurrentUser.get();
        Instant now = Instant.now(clock);
        BranchScope scope = caller.isMemberOnly() ? BranchScope.ALL : access.scope(branchId);
        Specification<Complaint> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (caller.isMemberOnly()) {
                // a member sees tickets they raised and tickets staff filed about them
                p.add(caller.memberId() == null ? cb.equal(root.get("raisedBy"), caller.userId())
                        : cb.or(cb.equal(root.get("raisedBy"), caller.userId()),
                                cb.equal(root.get("member").get("id"), caller.memberId())));
            } else if (!scope.all()) {
                p.add(root.get("branch").get("id").in(scope.ids()));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (priority != null) {
                p.add(cb.equal(root.get("priority"), priority));
            }
            if (category != null) {
                p.add(cb.equal(root.get("category"), category));
            }
            if (assignedTo != null) {
                p.add(cb.equal(root.get("assignedTo"), assignedTo));
            }
            if (slaBreachedOnly) {
                p.add(root.get("status").in(ComplaintStatus.UNRESOLVED));
                p.add(cb.lessThan(root.get("slaDueAt"), now));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var page = repository.findAll(spec, pageable);
        Map<Long, String> names = userNames(page.getContent().stream()
                .flatMap(c -> java.util.stream.Stream.of(c.getRaisedBy(), c.getAssignedTo())).toList());
        return PageResponse.of(page, c -> ComplaintResponse.from(c, now, names));
    }

    @Transactional
    public ComplaintResponse assign(Long id, Long assigneeUserId) {
        Complaint c = loadForStaff(id);
        var assignee = userRepository.findById(assigneeUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", assigneeUserId));
        boolean orgWide = assignee.roleNames().stream().anyMatch(RoleName.ORG_WIDE::contains);
        if (!orgWide && !staffAssignmentRepository.findBranchIdsByUserId(assigneeUserId).contains(c.getBranch().getId())) {
            throw new BusinessRuleException("Assignee must be staff of branch " + c.getBranch().getId());
        }
        c.assignTo(assigneeUserId, Instant.now(clock));
        audit.record(AuditEvent.of("COMPLAINT", id, "ASSIGNED").branch(c.getBranch().getId()).with("assignee", assigneeUserId));
        events.publishEvent(new DomainEvents.ComplaintUpdated(id, c.getStatus().name()));
        return toResponse(c, Instant.now(clock));
    }

    /**
     * Staff move the ticket through the workflow; the member who raised it may only CLOSE a resolved
     * ticket (accept the resolution) or REOPEN it.
     */
    @Transactional
    public ComplaintResponse changeStatus(Long id, StatusRequest r) {
        Complaint c = loadVisible(id);
        UserPrincipal caller = CurrentUser.get();
        if (caller.isMemberOnly() && r.status() != ComplaintStatus.CLOSED && r.status() != ComplaintStatus.REOPENED) {
            throw new ForbiddenException("Members can only close or reopen their complaints");
        }
        ComplaintStatus from = c.getStatus();
        c.transitionTo(r.status(), r.resolution(), Instant.now(clock));
        if (r.status() == ComplaintStatus.REOPENED) {
            // a reopened ticket gets a fresh SLA window; escalation stays with managers and the SLA job
            c.restartSla(Instant.now(clock).plus(sla(c.getPriority())));
        }
        audit.record(AuditEvent.of("COMPLAINT", id, "STATUS_CHANGED").branch(c.getBranch().getId())
                .with("from", from).with("to", c.getStatus()).with("resolution", r.resolution()));
        events.publishEvent(new DomainEvents.ComplaintUpdated(id, c.getStatus().name()));
        return toResponse(c, Instant.now(clock));
    }

    @Transactional
    public ComplaintResponse escalate(Long id, String reason) {
        Complaint c = loadForStaff(id);
        doEscalate(c, reason);
        return toResponse(c, Instant.now(clock));
    }

    /** Called by the SLA job for breached tickets (own transaction per ticket). */
    @Transactional
    public boolean escalateIfSlaBreached(Long id) {
        Complaint c = repository.findById(id).orElse(null);
        if (c == null || !c.isSlaBreached(Instant.now(clock)) || c.getEscalationLevel() >= Complaint.MAX_ESCALATION_LEVEL) {
            return false;
        }
        doEscalate(c, "SLA breached (due " + c.getSlaDueAt() + ")");
        return true;
    }

    @Transactional
    public CommentResponse addComment(Long id, CommentRequest r) {
        Complaint c = loadVisible(id);
        UserPrincipal caller = CurrentUser.get();
        boolean internal = r.internal() && !caller.isMemberOnly();
        Instant now = Instant.now(clock);
        if (!caller.isMemberOnly()) {
            c.markResponded(now);
        }
        ComplaintComment comment = commentRepository.save(new ComplaintComment(c, caller.userId(), r.body(), internal, now));
        audit.record(AuditEvent.of("COMPLAINT", id, "COMMENTED").branch(c.getBranch().getId()).with("internal", internal));
        return CommentResponse.from(comment, userNames(List.of(caller.userId())));
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(Long id) {
        loadVisible(id);
        var comments = CurrentUser.get().isMemberOnly()
                ? commentRepository.findByComplaintIdAndInternalFalseOrderByCreatedAtAsc(id)
                : commentRepository.findByComplaintIdOrderByCreatedAtAsc(id);
        Map<Long, String> names = userNames(comments.stream().map(ComplaintComment::getAuthorId).toList());
        return comments.stream().map(c -> CommentResponse.from(c, names)).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> history(Long id, Pageable pageable) {
        loadForStaff(id);
        return PageResponse.of(auditLogRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc("COMPLAINT", id, pageable),
                AuditLogResponse::from);
    }

    private ComplaintResponse toResponse(Complaint c, Instant now) {
        return ComplaintResponse.from(c, now, userNames(java.util.Arrays.asList(c.getRaisedBy(), c.getAssignedTo())));
    }

    /** Batch id -> display name lookup so clients never need staff-directory access to show names. */
    private Map<Long, String> userNames(java.util.Collection<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new java.util.HashMap<>();
        userRepository.findAllById(distinct).forEach(u -> names.put(u.getId(), u.getFullName()));
        return names;
    }

    private void doEscalate(Complaint c, String reason) {
        Instant now = Instant.now(clock);
        c.escalate(now.plus(sla(c.getPriority()).dividedBy(2)), now);
        audit.record(AuditEvent.of("COMPLAINT", c.getId(), "ESCALATED").branch(c.getBranch().getId())
                .with("level", c.getEscalationLevel()).with("priority", c.getPriority()).with("reason", reason));
        events.publishEvent(new DomainEvents.ComplaintEscalated(c.getId(), c.getBranch().getId(), c.getEscalationLevel()));
    }

    private Complaint loadVisible(Long id) {
        Complaint c = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Complaint", id));
        UserPrincipal caller = CurrentUser.get();
        if (caller.isMemberOnly()) {
            boolean aboutMe = c.getMember() != null && Objects.equals(c.getMember().getId(), caller.memberId());
            if (!Objects.equals(c.getRaisedBy(), caller.userId()) && !aboutMe) {
                throw new ResourceNotFoundException("Complaint", id);   // do not reveal other members' tickets
            }
            return c;
        }
        access.assertBranchAccess(c.getBranch().getId());
        return c;
    }

    private Complaint loadForStaff(Long id) {
        if (CurrentUser.get().isMemberOnly()) {
            throw new ForbiddenException("Staff only");
        }
        return loadVisible(id);
    }

    private Duration sla(ComplaintPriority priority) {
        AppProperties.Complaints s = props.complaints();
        return switch (priority) {
            case CRITICAL -> s.slaCritical();
            case HIGH -> s.slaHigh();
            case MEDIUM -> s.slaMedium();
            case LOW -> s.slaLow();
        };
    }

    private String nextTicketNumber() {
        return "CMP-%d-%05d".formatted(Year.now(clock).getValue() % 100, repository.nextTicketSequence());
    }
}
