package com.gymops.leads;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadActivityRepository extends JpaRepository<LeadActivity, Long> {

    List<LeadActivity> findByLeadIdOrderByCreatedAtDescIdDesc(Long leadId);

    Optional<LeadActivity> findByIdAndLeadId(Long id, Long leadId);

    @Query("""
            select min(a.dueAt) from LeadActivity a
            where a.lead.id = :leadId and a.type = com.gymops.leads.LeadActivityType.FOLLOW_UP and a.completedAt is null
            """)
    Optional<Instant> earliestOpenFollowUp(@Param("leadId") Long leadId);

    boolean existsByLeadIdAndTypeAndBody(Long leadId, LeadActivityType type, String body);

    /** Open follow-ups due before {@code dueBefore} on open leads, optionally limited to an owner and branches. */
    @Query("""
            select a from LeadActivity a join fetch a.lead l
            where a.type = com.gymops.leads.LeadActivityType.FOLLOW_UP and a.completedAt is null and a.dueAt < :dueBefore
              and l.status in (com.gymops.leads.LeadStatus.NEW, com.gymops.leads.LeadStatus.CONTACTED,
                               com.gymops.leads.LeadStatus.TOUR_SCHEDULED, com.gymops.leads.LeadStatus.TRIAL_ACTIVE)
              and (:ownerId is null or l.ownerUserId = :ownerId)
              and (:all = true or l.branch.id in :branchIds)
            order by a.dueAt asc
            """)
    List<LeadActivity> findDueFollowUps(@Param("dueBefore") Instant dueBefore, @Param("ownerId") Long ownerId,
                                        @Param("all") boolean all, @Param("branchIds") Collection<Long> branchIds);

    interface OwnerDue {
        Long getOwnerUserId();

        long getDue();

        long getOverdue();
    }

    @Query("""
            select l.ownerUserId as ownerUserId, count(a) as due,
                   sum(case when a.dueAt < :overdueBefore then 1 else 0 end) as overdue
            from LeadActivity a join a.lead l
            where a.type = com.gymops.leads.LeadActivityType.FOLLOW_UP and a.completedAt is null and a.dueAt < :dueBefore
              and l.ownerUserId is not null
              and l.status in (com.gymops.leads.LeadStatus.NEW, com.gymops.leads.LeadStatus.CONTACTED,
                               com.gymops.leads.LeadStatus.TOUR_SCHEDULED, com.gymops.leads.LeadStatus.TRIAL_ACTIVE)
            group by l.ownerUserId
            """)
    List<OwnerDue> countDueByOwner(@Param("dueBefore") Instant dueBefore, @Param("overdueBefore") Instant overdueBefore);
}
