package com.gymops.classes;

import com.gymops.classes.dto.ClassDtos.CreditsResponse;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.member.Member;
import com.gymops.plan.MembershipPlan;
import com.gymops.subscription.MembershipQueryService;
import com.gymops.subscription.Subscription;
import com.gymops.subscription.SubscriptionRepository;
import com.gymops.subscription.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may book which class: membership on the class date, the plan's branch and time rules (the same
 * rules the turnstile applies at check-in), the plan's monthly class credits and the no-show penalty.
 */
@Service
@RequiredArgsConstructor
public class ClassEntitlementService {

    private final MembershipQueryService membershipQuery;
    private final SubscriptionRepository subscriptionRepository;
    private final ClassBookingRepository bookingRepository;
    private final ClassesProperties props;
    private final Clock clock;

    /**
     * The subscription that covers {@code date}: an ACTIVE/GRACE one (as for check-in), or an already
     * paid, SCHEDULED renewal starting by then (so members can book classes after their renewal date).
     */
    @Transactional(readOnly = true)
    public Optional<Subscription> subscriptionOn(Long memberId, LocalDate date) {
        return membershipQuery.accessGrantingSubscription(memberId, date)
                .or(() -> subscriptionRepository.findByMemberAndStatuses(memberId, EnumSet.of(SubscriptionStatus.SCHEDULED))
                        .stream()
                        .filter(s -> !date.isBefore(s.getStartDate()) && !date.isAfter(s.getEndDate()))
                        .findFirst());
    }

    /** Every rule a new booking must satisfy, evaluated for the session's local date and time. */
    @Transactional(readOnly = true)
    public void assertCanBook(Member member, ClassSession session, Instant now) {
        if (!member.isActive()) {
            throw new ApiException(ErrorCode.ACCESS_RESTRICTED, "Member account is " + member.getStatus());
        }
        ZoneId zone = session.getBranch().zone();
        LocalDate date = session.getStartsAt().atZone(zone).toLocalDate();
        Subscription subscription = subscriptionOn(member.getId(), date).orElseThrow(() -> new ApiException(
                ErrorCode.MEMBERSHIP_NOT_ACTIVE, "No active membership on " + date + " (the class date)"));
        enforcePlanRules(subscription, session);
        YearMonth month = YearMonth.from(date);
        ClassBookingRules.checkEntitlement(subscription.getPlan().getClassCreditsPerMonth(),
                creditsUsed(member.getId(), month, zone), month);
        ClassBookingRules.checkNoShowPenalty(blockedUntil(member.getId(), now), props.noShowThreshold(),
                props.noShowWindow(), zone);
    }

    /** Whether a waitlisted member can still take a freed seat (credits are consumed only on promotion). */
    @Transactional(readOnly = true)
    public boolean hasCreditFor(Long memberId, ClassSession session) {
        ZoneId zone = session.getBranch().zone();
        LocalDate date = session.getStartsAt().atZone(zone).toLocalDate();
        Optional<Subscription> subscription = subscriptionOn(memberId, date);
        if (subscription.isEmpty()) {
            return false;
        }
        Integer credits = subscription.get().getPlan().getClassCreditsPerMonth();
        return credits == null || creditsUsed(memberId, YearMonth.from(date), zone) < credits;
    }

    public long creditsUsed(Long memberId, YearMonth month, ZoneId zone) {
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return bookingRepository.countMemberBookingsBetween(memberId, BookingStatus.CREDIT_CONSUMING, from, to);
    }

    public Optional<Instant> blockedUntil(Long memberId, Instant now) {
        Instant since = now.minus(props.noShowWindow()).minus(props.noShowBlock());
        List<Instant> noShows = bookingRepository.findNoShowTimes(memberId, since);
        return ClassBookingRules.noShowBlockedUntil(noShows, now, props.noShowThreshold(), props.noShowWindow(),
                props.noShowBlock());
    }

    @Transactional(readOnly = true)
    public CreditsResponse credits(Long memberId, YearMonth requested) {
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        YearMonth month = requested != null ? requested : YearMonth.from(today);
        LocalDate reference = month.equals(YearMonth.from(today)) ? today : month.atDay(1);
        Optional<MembershipPlan> plan = subscriptionOn(memberId, reference).map(Subscription::getPlan);
        long used = creditsUsed(memberId, month, zone);
        // no membership = no classes; a plan with null credits = unlimited (Optional.map cannot carry that null)
        Integer credits = plan.isPresent() ? plan.get().getClassCreditsPerMonth() : Integer.valueOf(0);
        boolean unlimited = plan.isPresent() && credits == null;
        Instant now = Instant.now(clock);
        long recentNoShows = bookingRepository.findNoShowTimes(memberId, now.minus(props.noShowWindow())).stream()
                .filter(t -> !t.isAfter(now)).count();
        return new CreditsResponse(memberId, month.toString(), plan.map(MembershipPlan::getCode).orElse(null),
                plan.map(MembershipPlan::getName).orElse(null), unlimited || (credits != null && credits > 0), unlimited,
                unlimited ? null : credits, used, unlimited ? null : ClassBookingRules.remaining(credits, used),
                recentNoShows, props.noShowThreshold(), blockedUntil(memberId, now).orElse(null));
    }

    /** Plan rules as for check-in: home-branch-only plans and plan access hours. */
    private static void enforcePlanRules(Subscription subscription, ClassSession session) {
        MembershipPlan plan = subscription.getPlan();
        if (!plan.isAllBranchAccess() && !subscription.getBranch().getId().equals(session.getBranch().getId())) {
            throw new ApiException(ErrorCode.ACCESS_RESTRICTED,
                    "Plan " + plan.getCode() + " is valid only at the member's home branch");
        }
        ZoneId zone = session.getBranch().zone();
        LocalTime start = session.getStartsAt().atZone(zone).toLocalTime();
        LocalTime lastMinute = session.getEndsAt().minusSeconds(60).atZone(zone).toLocalTime();
        if (!plan.isAccessibleAt(start) || !plan.isAccessibleAt(lastMinute)) {
            throw new ApiException(ErrorCode.ACCESS_RESTRICTED, "Plan " + plan.getCode() + " allows entry only between "
                    + plan.getAccessStartTime() + " and " + plan.getAccessEndTime());
        }
    }
}
