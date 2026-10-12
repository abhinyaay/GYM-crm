package com.gymops.growth.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.payment.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Referral and reward state machines (pure entity logic). */
class ReferralStateTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private static Referral pending() {
        return new Referral(1L, "ASHA7K2", new Referral.Referee(null, 50L, "Rahul", "Sharma", "R@x.com", "+91 9", 3L),
                ReferralChannel.LINK);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,CONVERTED,true", "PENDING,REJECTED,true", "PENDING,REVERSED,false",
            "CONVERTED,REVERSED,true", "CONVERTED,PENDING,false", "CONVERTED,REJECTED,false",
            "REVERSED,CONVERTED,false", "REJECTED,CONVERTED,false", "REJECTED,PENDING,false"})
    void transitions(ReferralStatus from, ReferralStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void convertsOnlyAfterTheRefereeIsLinkedAndHoldsUntilTheGivenTime() {
        Referral r = pending();
        assertThat(r.getRefereeEmail()).isEqualTo("r@x.com");
        assertThat(r.refereeDisplayName()).isEqualTo("Rahul S.");
        r.linkMember(9L);
        r.convert(100L, 200L, NOW, NOW.plusSeconds(86400), null);
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.CONVERTED);
        assertThat(r.isInHold(NOW.plusSeconds(3600))).isTrue();
        assertThat(r.isInHold(NOW.plusSeconds(86400))).isFalse();
        assertThatThrownBy(() -> r.linkMember(10L)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> r.reject(RejectionReason.NOT_NEW)).isInstanceOf(InvalidStateTransitionException.class);
        r.reverse("refund", NOW.plusSeconds(60));
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.REVERSED);
        assertThat(r.getReversedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> r.reverse("again", NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cannotRelinkToAnotherMemberOrConvertARejectedReferral() {
        Referral r = new Referral(1L, "ASHA7K2", new Referral.Referee(9L, null, "Rahul", null, null, null, 3L),
                ReferralChannel.DESK);
        assertThatThrownBy(() -> r.linkMember(10L)).isInstanceOf(InvalidStateTransitionException.class);
        r.reject(RejectionReason.SELF_REFERRAL);
        assertThatThrownBy(() -> r.convert(1L, 2L, NOW, NOW, null)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rewardIsReleasedOnlyOnceItsHoldIsOver() {
        ReferralReward credit = ReferralReward.storeCredit(1L, 2L, Beneficiary.REFERRER, new BigDecimal("500"),
                NOW.plusSeconds(100));
        assertThat(credit.isDue(NOW)).isFalse();
        assertThatThrownBy(() -> credit.releaseAsCredit(7L, NOW)).isInstanceOf(InvalidStateTransitionException.class);
        credit.releaseAsCredit(7L, NOW.plusSeconds(100));
        assertThat(credit.getStatus()).isEqualTo(RewardStatus.AVAILABLE);
        assertThat(credit.getCreditEntryId()).isEqualTo(7L);
        assertThatThrownBy(() -> credit.reverse(NOW)).as("paid out rewards are final")
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> credit.releaseAsCredit(8L, NOW.plusSeconds(200)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void heldRewardCanBeReversedAndTypesMustMatch() {
        ReferralReward code = ReferralReward.discountCode(1L, 3L, Beneficiary.REFEREE, DiscountType.PERCENTAGE,
                BigDecimal.TEN, NOW);
        assertThatThrownBy(() -> code.releaseAsCredit(1L, NOW)).isInstanceOf(IllegalStateException.class);
        code.reverse(NOW);
        assertThat(code.getStatus()).isEqualTo(RewardStatus.REVERSED);
        assertThat(code.isDue(NOW.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> ReferralReward.storeCredit(1L, 2L, Beneficiary.REFERRER, BigDecimal.ZERO, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
