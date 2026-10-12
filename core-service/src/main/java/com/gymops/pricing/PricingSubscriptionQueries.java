package com.gymops.pricing;

import com.gymops.subscription.Subscription;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Subscription reads and the bulk price-protection update used by the pricing module. */
public interface PricingSubscriptionQueries extends Repository<Subscription, Long> {

    @Query("""
            select count(s) from Subscription s
            where s.plan.id = :planId and s.status in ('PENDING_PAYMENT','SCHEDULED','ACTIVE','FROZEN','GRACE')
            """)
    long countOpenOnPlan(@Param("planId") Long planId);

    /** Autopay terms on the plan: the term carrying the autopay flag is the one that will be renewed. */
    @Query("""
            select s from Subscription s join fetch s.member
            where s.plan.id = :planId and s.autoRenew = true and s.status in ('SCHEDULED','ACTIVE','FROZEN','GRACE')
              and not exists (select 1 from Subscription n where n.previousSubscription = s
                              and n.status in ('PENDING_PAYMENT','SCHEDULED','ACTIVE','FROZEN','GRACE'))
            order by s.endDate asc
            """)
    List<Subscription> findAutopayOnPlan(@Param("planId") Long planId);

    @Query("""
            select count(s) > 0 from Subscription s
            where s.plan.id = :planId and s.autoRenew = true and s.status in ('SCHEDULED','ACTIVE','FROZEN','GRACE')
            """)
    boolean existsAutopayOnPlan(@Param("planId") Long planId);

    /** Autopay terms ending soon whose renewal price may rise (daily notice sweep). */
    @Query("""
            select s.id from Subscription s
            where s.autoRenew = true and s.status in ('SCHEDULED','ACTIVE','FROZEN') and s.endDate <= :until
              and not exists (select 1 from Subscription n where n.previousSubscription = s
                              and n.status in ('PENDING_PAYMENT','SCHEDULED','ACTIVE','FROZEN','GRACE'))
            """)
    List<Long> findAutopayEndingBy(@Param("until") LocalDate until);

    @Query("select s from Subscription s join fetch s.member join fetch s.plan where s.id = :id")
    Subscription loadWithMemberAndPlan(@Param("id") Long id);

    /**
     * Price protection for members who held the plan before the change took effect (and the renewals
     * they queued since). A member already protected at a lower price keeps it; the protection end
     * becomes the later of the two dates. The version bump makes concurrent edits of these rows fail
     * with an optimistic-lock conflict instead of silently overwriting the lock.
     */
    @Modifying
    @Query(value = """
            UPDATE subscriptions s
            SET locked_price = CASE WHEN s.locked_price IS NOT NULL AND s.price_protected_until >= :today
                                         AND s.locked_price < :oldPrice THEN s.locked_price ELSE :oldPrice END,
                price_protected_until = CASE WHEN s.price_protected_until IS NOT NULL
                                              AND s.price_protected_until > :until
                                         THEN s.price_protected_until ELSE :until END,
                price_change_id = :changeId,
                version = s.version + 1,
                updated_at = :now
            WHERE s.plan_id = :planId
              AND s.status IN ('PENDING_PAYMENT', 'SCHEDULED', 'ACTIVE', 'FROZEN', 'GRACE')
              AND (s.created_at < :effectiveStart
                   OR EXISTS (SELECT 1 FROM subscriptions p WHERE p.id = s.previous_subscription_id
                              AND p.plan_id = :planId AND p.created_at < :effectiveStart))
            """, nativeQuery = true)
    int protectExisting(@Param("planId") Long planId, @Param("changeId") Long changeId,
                        @Param("oldPrice") BigDecimal oldPrice, @Param("until") LocalDate until,
                        @Param("today") LocalDate today, @Param("effectiveStart") Instant effectiveStart,
                        @Param("now") Instant now);
}
