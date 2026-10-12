package com.gymops.subscription;

import com.gymops.member.MembershipState;
import com.gymops.subscription.dto.MembershipSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-side answers about a member's membership, shared by members, attendance and dashboards. */
@Service
@RequiredArgsConstructor
public class MembershipQueryService {

    private static final EnumSet<SubscriptionStatus> RELEVANT = EnumSet.of(SubscriptionStatus.ACTIVE,
            SubscriptionStatus.GRACE, SubscriptionStatus.FROZEN, SubscriptionStatus.SCHEDULED,
            SubscriptionStatus.PENDING_PAYMENT);

    private final SubscriptionRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MembershipSummary currentMembership(Long memberId) {
        LocalDate today = LocalDate.now(clock);
        List<Subscription> subs = repository.findByMemberAndStatuses(memberId, RELEVANT);
        return pick(subs, today).map(s -> summarize(s, today)).orElseGet(MembershipSummary::none);
    }

    /** Batch variant for list screens (one query instead of one per member). */
    @Transactional(readOnly = true)
    public java.util.Map<Long, MembershipSummary> currentMemberships(java.util.Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return java.util.Map.of();
        }
        LocalDate today = LocalDate.now(clock);
        var byMember = repository.findByMembersAndStatuses(memberIds, RELEVANT).stream()
                .collect(java.util.stream.Collectors.groupingBy(s -> s.getMember().getId()));
        java.util.Map<Long, MembershipSummary> result = new java.util.HashMap<>();
        for (Long id : memberIds) {
            result.put(id, pick(byMember.getOrDefault(id, List.of()), today)
                    .map(s -> summarize(s, today)).orElseGet(MembershipSummary::none));
        }
        return result;
    }

    /** The subscription that grants access today (ACTIVE or GRACE covering today), if any. */
    @Transactional(readOnly = true)
    public Optional<Subscription> accessGrantingSubscription(Long memberId, LocalDate date) {
        return repository.findByMemberAndStatuses(memberId, SubscriptionStatus.ACCESS_GRANTING).stream()
                .filter(s -> s.grantsAccessOn(date))
                .findFirst();
    }

    static Optional<Subscription> pick(List<Subscription> subs, LocalDate today) {
        Optional<Subscription> current = subs.stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.FROZEN || s.grantsAccessOn(today))
                .findFirst();
        if (current.isPresent()) {
            return current;
        }
        return subs.stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.SCHEDULED)
                .min(Comparator.comparing(Subscription::getStartDate))
                .or(() -> subs.stream().filter(s -> s.getStatus() == SubscriptionStatus.PENDING_PAYMENT).findFirst());
    }

    private static MembershipSummary summarize(Subscription s, LocalDate today) {
        MembershipState state = switch (s.getStatus()) {
            case ACTIVE -> MembershipState.ACTIVE;
            case GRACE -> MembershipState.GRACE;
            case FROZEN -> MembershipState.FROZEN;
            case SCHEDULED -> MembershipState.SCHEDULED;
            case PENDING_PAYMENT -> MembershipState.PENDING_PAYMENT;
            default -> MembershipState.NONE;
        };
        Long daysRemaining = state == MembershipState.ACTIVE || state == MembershipState.FROZEN
                ? Math.max(0, ChronoUnit.DAYS.between(today, s.getEndDate()) + 1) : null;
        return new MembershipSummary(state, s.getId(), s.getPlan().getCode(), s.getPlan().getName(),
                s.getStartDate(), s.getEndDate(), daysRemaining);
    }
}
