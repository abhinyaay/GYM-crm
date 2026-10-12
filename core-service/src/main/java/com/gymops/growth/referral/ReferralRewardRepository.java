package com.gymops.growth.referral;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReferralRewardRepository extends JpaRepository<ReferralReward, Long> {

    List<ReferralReward> findByReferralId(Long referralId);

    List<ReferralReward> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    List<ReferralReward> findByReferralIdIn(List<Long> referralIds);

    @Query("select r.id from ReferralReward r where r.status = com.gymops.growth.referral.RewardStatus.HELD "
            + "and r.availableAt <= :now order by r.availableAt")
    List<Long> findDueIds(@Param("now") Instant now);

    /** Referrer rewards (not reversed) for referrals converted in [from, to): the monthly cap counter. */
    @Query("""
            select count(w) from ReferralReward w, Referral r
            where w.referralId = r.id and r.referrerMemberId = :referrerId
              and w.beneficiary = com.gymops.growth.referral.Beneficiary.REFERRER
              and w.status <> com.gymops.growth.referral.RewardStatus.REVERSED
              and r.convertedAt >= :from and r.convertedAt < :to
            """)
    long countReferrerRewardsBetween(@Param("referrerId") Long referrerId, @Param("from") Instant from,
                                     @Param("to") Instant to);
}
