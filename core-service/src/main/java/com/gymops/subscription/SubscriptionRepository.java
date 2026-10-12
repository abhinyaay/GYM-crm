package com.gymops.subscription;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long>, JpaSpecificationExecutor<Subscription> {

    @Query("""
            select s from Subscription s join fetch s.plan
            where s.member.id = :memberId and s.status in :statuses
            order by s.startDate asc
            """)
    List<Subscription> findByMemberAndStatuses(@Param("memberId") Long memberId,
                                               @Param("statuses") Collection<SubscriptionStatus> statuses);

    @Query("""
            select s from Subscription s join fetch s.plan
            where s.member.id in :memberIds and s.status in :statuses
            order by s.startDate asc
            """)
    List<Subscription> findByMembersAndStatuses(@Param("memberIds") Collection<Long> memberIds,
                                                @Param("statuses") Collection<SubscriptionStatus> statuses);

    Page<Subscription> findByMemberIdOrderByStartDateDesc(Long memberId, Pageable pageable);

    @Query("""
            select count(s) > 0 from Subscription s
            where s.previousSubscription.id = :subscriptionId and s.status in :statuses
            """)
    boolean existsSuccessorWithStatus(@Param("subscriptionId") Long subscriptionId,
                                      @Param("statuses") Collection<SubscriptionStatus> statuses);

    @Query("""
            select s from Subscription s
            where s.previousSubscription.id = :subscriptionId and s.status in :statuses
            """)
    List<Subscription> findSuccessors(@Param("subscriptionId") Long subscriptionId,
                                      @Param("statuses") Collection<SubscriptionStatus> statuses);

    @Query("select s.member.id from Subscription s where s.id = :id")
    Optional<Long> findMemberIdById(@Param("id") Long id);

    @Query("""
            select count(s) > 0 from Subscription s
            where s.member.id = :memberId and s.status in ('SCHEDULED','ACTIVE','FROZEN','GRACE')
              and s.startDate <= :end and s.endDate >= :start
            """)
    boolean existsCommittedOverlap(@Param("memberId") Long memberId, @Param("start") LocalDate start,
                                   @Param("end") LocalDate end);

    @Query("select count(s) > 0 from Subscription s where s.member.id = :memberId and s.activatedAt is not null")
    boolean hasEverActivated(@Param("memberId") Long memberId);

    Optional<Subscription> findFirstByMemberIdAndStatus(Long memberId, SubscriptionStatus status);

    // ---------------------------------------------------------- lifecycle job selectors (ids only)

    @Query("select s.id from Subscription s where s.status = 'SCHEDULED' and s.startDate <= :today")
    List<Long> findDueToStart(@Param("today") LocalDate today);

    @Query("select s.id from Subscription s where s.status in ('ACTIVE','GRACE') and s.endDate < :today")
    List<Long> findPastEndDate(@Param("today") LocalDate today);

    @Query("select s.id from Subscription s where s.status = 'FROZEN' and s.frozenUntil < :today")
    List<Long> findFreezeEnded(@Param("today") LocalDate today);

    /** Unpaid terms past the payment window, except autopay renewals whose debit is still being retried. */
    @Query("""
            select s.id from Subscription s where s.status = 'PENDING_PAYMENT' and s.createdAt < :cutoff
              and not exists (select 1 from AutopayAttempt a where a.renewalSubscriptionId = s.id
                              and a.status in ('SCHEDULED','PROCESSING'))
            """)
    List<Long> findStalePending(@Param("cutoff") java.time.Instant cutoff);

    @Query("""
            select s from Subscription s join fetch s.member m join fetch s.plan
            where s.status = 'ACTIVE' and s.endDate = :endDate
              and not exists (select 1 from Subscription n where n.previousSubscription = s
                              and n.status in ('PENDING_PAYMENT','SCHEDULED','ACTIVE'))
            """)
    List<Subscription> findEndingOnWithoutRenewal(@Param("endDate") LocalDate endDate);

    /** Autopay subscriptions whose renewal should be raised now (term ends within the lead time). */
    @Query("""
            select s.id from Subscription s
            where s.autoRenew = true and s.status = 'ACTIVE' and s.endDate <= :until
              and not exists (select 1 from Subscription n where n.previousSubscription = s
                              and n.status in ('PENDING_PAYMENT','SCHEDULED','ACTIVE','FROZEN','GRACE'))
            """)
    List<Long> findAutopayDue(@Param("until") LocalDate until);

    @Query("""
            select s from Subscription s join fetch s.member join fetch s.plan
            where s.autoRenew = true and s.status in ('ACTIVE','FROZEN','SCHEDULED') and s.endDate <= :until
              and (:allBranches = true or s.branch.id in :branchIds)
            order by s.endDate asc
            """)
    List<Subscription> findAutopayUpcoming(@Param("until") LocalDate until, @Param("allBranches") boolean allBranches,
                                           @Param("branchIds") Collection<Long> branchIds);

    List<Subscription> findByAutopayMethodIdAndAutoRenewTrue(Long autopayMethodId);

    // ---------------------------------------------------------- dashboard counters

    @Query("""
            select count(distinct s.member.id) from Subscription s
            where s.status in :statuses and (:allBranches = true or s.branch.id in :branchIds)
            """)
    long countMembersWithStatus(@Param("statuses") Collection<SubscriptionStatus> statuses,
                                @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);

    @Query("""
            select count(s) from Subscription s
            where s.status = 'EXPIRED' and s.endDate between :from and :to
              and (:allBranches = true or s.branch.id in :branchIds)
            """)
    long countExpiredBetween(@Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);

    @Query("""
            select count(s) from Subscription s
            where s.changeType = 'RENEWAL' and s.activatedAt is not null
              and s.activatedAt >= :from and s.activatedAt < :to
              and (:allBranches = true or s.branch.id in :branchIds)
            """)
    long countRenewalsActivatedBetween(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to,
                                       @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);

    @Query("""
            select count(s) from Subscription s
            where s.status = 'ACTIVE' and s.endDate between :from and :to
              and (:allBranches = true or s.branch.id in :branchIds)
            """)
    long countActiveEndingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);
}
