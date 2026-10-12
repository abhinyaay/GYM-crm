package com.gymops.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.branch.GymBranch;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.member.Member;
import com.gymops.plan.MembershipPlan;
import com.gymops.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SubscriptionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final Instant NOW = Instant.parse("2026-03-10T05:00:00Z");

    private GymBranch branch;
    private Member member;
    private MembershipPlan plan;

    @BeforeEach
    void setUp() {
        branch = Fixtures.branch(1);
        member = Fixtures.member(1, branch);
        plan = Fixtures.monthlyPlan();
    }

    private Subscription activeFrom(LocalDate start) {
        Subscription s = Subscription.pending(member, plan, branch, start, ChangeType.NEW, null);
        s.activate(start, NOW);
        return s;
    }

    @Nested
    class Activation {

        @Test
        void startingTodayBecomesActiveWithInclusiveMonthTerm() {
            Subscription s = Subscription.pending(member, plan, branch, TODAY, ChangeType.NEW, null);
            s.activate(TODAY, NOW);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
            assertThat(s.getEndDate()).isEqualTo(LocalDate.of(2026, 4, 9));
            assertThat(s.getActivatedAt()).isEqualTo(NOW);
        }

        @Test
        void futureStartBecomesScheduled() {
            Subscription s = Subscription.pending(member, plan, branch, TODAY.plusDays(5), ChangeType.NEW, null);
            s.activate(TODAY, NOW);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SCHEDULED);
            assertThat(s.startIfDue(TODAY.plusDays(4))).isFalse();
            assertThat(s.startIfDue(TODAY.plusDays(5))).isTrue();
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }

        @Test
        void latePaymentOfNewSubscriptionStartsTermOnPaymentDate() {
            Subscription s = Subscription.pending(member, plan, branch, TODAY.minusDays(3), ChangeType.NEW, null);
            s.activate(TODAY, NOW);
            assertThat(s.getStartDate()).isEqualTo(TODAY);
            assertThat(s.getEndDate()).isEqualTo(plan.endDateFor(TODAY));
        }

        @Test
        void renewalPaidDuringGraceStaysBackDatedForContinuity() {
            Subscription previous = activeFrom(TODAY.minusMonths(1).minusDays(3));
            previous.evaluateExpiry(TODAY, false);   // -> GRACE
            assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.GRACE);
            Subscription renewal = Subscription.pending(member, plan, branch, previous.getEndDate().plusDays(1),
                    ChangeType.RENEWAL, previous);
            renewal.activate(TODAY, NOW);
            assertThat(renewal.getStartDate()).isEqualTo(previous.getEndDate().plusDays(1));
            assertThat(renewal.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }

        @Test
        void cannotActivateTwice() {
            Subscription s = activeFrom(TODAY);
            assertThatThrownBy(() -> s.activate(TODAY, NOW)).isInstanceOf(InvalidStateTransitionException.class);
        }
    }

    @Nested
    class FreezeAndResume {

        @Test
        void freezeExtendsEndDateAndConsumesAllowance() {
            Subscription s = activeFrom(TODAY);
            LocalDate originalEnd = s.getEndDate();
            s.freeze(TODAY, 10);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.FROZEN);
            assertThat(s.getEndDate()).isEqualTo(originalEnd.plusDays(10));
            assertThat(s.getFrozenUntil()).isEqualTo(TODAY.plusDays(9));
            assertThat(s.getFreezeDaysUsed()).isEqualTo(10);
            assertThat(s.grantsAccessOn(TODAY)).isFalse();
        }

        @Test
        void freezeOutsidePolicyIsRejected() {
            Subscription s = activeFrom(TODAY);
            assertThatThrownBy(() -> s.freeze(TODAY, 3)).isInstanceOf(BusinessRuleException.class);
            assertThatThrownBy(() -> s.freeze(TODAY, 31)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void freezeCountLimitIsEnforced() {
            Subscription s = activeFrom(TODAY);
            s.freeze(TODAY, 7);
            s.resume(TODAY.plusDays(7));
            assertThatThrownBy(() -> s.freeze(TODAY.plusDays(8), 7)).isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("Freeze limit");
        }

        @Test
        void freezeNotAllowedByPlan() {
            plan.setFreezeAllowed(false);
            Subscription s = activeFrom(TODAY);
            assertThatThrownBy(() -> s.freeze(TODAY, 10)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void earlyResumeReturnsUnusedDays() {
            Subscription s = activeFrom(TODAY);
            LocalDate originalEnd = s.getEndDate();
            s.freeze(TODAY, 10);
            int unused = s.resume(TODAY.plusDays(4));   // used days 0..3 = 4 days
            assertThat(unused).isEqualTo(6);
            assertThat(s.getEndDate()).isEqualTo(originalEnd.plusDays(4));
            assertThat(s.getFreezeDaysUsed()).isEqualTo(4);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        }

        @Test
        void autoResumeAfterFreezeWindow() {
            Subscription s = activeFrom(TODAY);
            s.freeze(TODAY, 7);
            assertThat(s.resumeIfFreezeEnded(TODAY.plusDays(6))).isFalse();
            assertThat(s.resumeIfFreezeEnded(TODAY.plusDays(7))).isTrue();
            assertThat(s.getFreezeDaysUsed()).isEqualTo(7);
        }
    }

    @Nested
    class Expiry {

        @Test
        void endedSubscriptionEntersGraceThenExpires() {
            Subscription s = activeFrom(TODAY);
            LocalDate end = s.getEndDate();
            assertThat(s.evaluateExpiry(end, false)).isFalse();
            assertThat(s.evaluateExpiry(end.plusDays(1), false)).isTrue();
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.GRACE);
            assertThat(s.grantsAccessOn(end.plusDays(7))).isTrue();
            assertThat(s.grantsAccessOn(end.plusDays(8))).isFalse();
            assertThat(s.evaluateExpiry(end.plusDays(8), false)).isTrue();
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        }

        @Test
        void renewedSubscriptionSkipsGrace() {
            Subscription s = activeFrom(TODAY);
            s.evaluateExpiry(s.getEndDate().plusDays(1), true);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        }

        @Test
        void planWithoutGraceExpiresImmediately() {
            plan.setGracePeriodDays(0);
            Subscription s = activeFrom(TODAY);
            s.evaluateExpiry(s.getEndDate().plusDays(1), false);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        }
    }

    @Nested
    class CancellationAndProration {

        @Test
        void cancelEndsAccessToday() {
            Subscription s = activeFrom(TODAY.minusDays(10));
            s.cancel(TODAY, "moving", NOW);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
            assertThat(s.getEndDate()).isEqualTo(TODAY);
            assertThatThrownBy(() -> s.cancel(TODAY, "again", NOW)).isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        void unusedPaidDaysExcludeTodayAndFreezeExtension() {
            Subscription s = activeFrom(TODAY);           // 31-day term, 10 Mar - 9 Apr
            assertThat(s.paidDays()).isEqualTo(31);
            assertThat(s.unusedPaidDays(TODAY)).isEqualTo(30);
            s.freeze(TODAY, 10);
            assertThat(s.paidDays()).isEqualTo(31);
            // the freeze starts today, so today is a frozen (free) day rather than a consumed paid day
            assertThat(s.unusedPaidDays(TODAY)).isEqualTo(31);
        }

        @Test
        void supersedeEndsTheDayBeforeTheUpgrade() {
            Subscription s = activeFrom(TODAY.minusDays(5));
            s.supersede(TODAY);
            assertThat(s.getStatus()).isEqualTo(SubscriptionStatus.SUPERSEDED);
            assertThat(s.getEndDate()).isEqualTo(TODAY.minusDays(1));
        }
    }
}
