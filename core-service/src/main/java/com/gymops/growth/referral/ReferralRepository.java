package com.gymops.growth.referral;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReferralRepository extends JpaRepository<Referral, Long> {

    Optional<Referral> findByRefereeMemberId(Long memberId);

    Optional<Referral> findByRefereeLeadId(Long leadId);

    Optional<Referral> findByQualifyingInvoiceIdAndStatus(Long invoiceId, ReferralStatus status);

    Optional<Referral> findByQualifyingSubscriptionIdAndStatus(Long subscriptionId, ReferralStatus status);

    List<Referral> findByReferrerMemberIdOrderByCreatedAtDesc(Long referrerMemberId);

    /** Pending referrals whose referee is known (member linked) - candidates for the conversion sweep. */
    @Query("select r.refereeMemberId from Referral r where r.status = com.gymops.growth.referral.ReferralStatus.PENDING "
            + "and r.refereeMemberId is not null")
    List<Long> findPendingRefereeMemberIds();

    /** Pending, not yet linked referrals captured for a lead that has since been linked to this member. */
    @Query(value = """
            select r.id from referrals r join leads l on l.id = r.referee_lead_id
            where l.member_id = :memberId and r.status = 'PENDING' and r.referee_member_id is null
            order by r.created_at, r.id limit 1
            """, nativeQuery = true)
    Optional<Long> findUnlinkedByLeadMember(@Param("memberId") Long memberId);

    /** Pending, not yet linked referrals whose captured contact details match the member. */
    @Query(value = """
            select r.id from referrals r
            where r.status = 'PENDING' and r.referee_member_id is null
              and ((cast(:email as text) is not null and lower(r.referee_email) = cast(:email as text))
                   or (cast(:phoneTail as text) is not null
                       and right(regexp_replace(r.referee_phone, '[^0-9]', '', 'g'), 10) = cast(:phoneTail as text)))
            order by r.created_at, r.id limit 1
            """, nativeQuery = true)
    Optional<Long> findUnlinkedByContact(@Param("email") String email, @Param("phoneTail") String phoneTail);
}
