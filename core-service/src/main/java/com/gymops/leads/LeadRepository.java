package com.gymops.leads;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    @Query("""
            select l from Lead l
            where l.branch.id = :branchId and l.phone = :phone
              and l.status in (com.gymops.leads.LeadStatus.NEW, com.gymops.leads.LeadStatus.CONTACTED,
                               com.gymops.leads.LeadStatus.TOUR_SCHEDULED, com.gymops.leads.LeadStatus.TRIAL_ACTIVE)
            """)
    Optional<Lead> findOpenByBranchAndPhone(@Param("branchId") Long branchId, @Param("phone") String phone);

    @Query("""
            select l from Lead l
            where l.memberId = :memberId
              and l.status in (com.gymops.leads.LeadStatus.NEW, com.gymops.leads.LeadStatus.CONTACTED,
                               com.gymops.leads.LeadStatus.TOUR_SCHEDULED, com.gymops.leads.LeadStatus.TRIAL_ACTIVE)
            """)
    List<Lead> findOpenByMemberId(@Param("memberId") Long memberId);

    @Query("select l.id from Lead l where l.status = com.gymops.leads.LeadStatus.TRIAL_ACTIVE and l.trialEndsOn = :date")
    List<Long> findTrialsEndingOn(@Param("date") LocalDate date);

    @Query("""
            select l.id from Lead l
            where l.status = com.gymops.leads.LeadStatus.TRIAL_ACTIVE and l.trialEndsOn <= :endedOnOrBefore
            """)
    List<Long> findTrialsEndedOnOrBefore(@Param("endedOnOrBefore") LocalDate endedOnOrBefore);

    interface StatusCount {
        LeadStatus getStatus();

        long getCount();
    }

    @Query("""
            select l.status as status, count(l) as count from Lead l
            where (:all = true or l.branch.id in :branchIds)
            group by l.status
            """)
    List<StatusCount> countByStatus(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds);

    @Query("""
            select count(l) from Lead l
            where (:all = true or l.branch.id in :branchIds)
              and l.status = com.gymops.leads.LeadStatus.CONVERTED and l.convertedAt >= :since
            """)
    long countConvertedSince(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds,
                             @Param("since") Instant since);

    @Query("""
            select count(l) from Lead l
            where (:all = true or l.branch.id in :branchIds) and l.createdAt >= :since
            """)
    long countCreatedSince(@Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds,
                           @Param("since") Instant since);

    /** Per-user daily de-duplication for the follow-up digest (in-app notifications carry no dedup key). */
    @Query(value = """
            select count(*) > 0 from notifications
            where type = 'LEAD_FOLLOW_UP_DUE' and recipient_user_id = :userId and created_at >= :since
            """, nativeQuery = true)
    boolean digestSentSince(@Param("userId") Long userId, @Param("since") Instant since);
}
