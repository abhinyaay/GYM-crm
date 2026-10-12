package com.gymops.payment.autopay;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutopayAttemptRepository extends JpaRepository<AutopayAttempt, Long>, JpaSpecificationExecutor<AutopayAttempt> {

    @Query("select a.id from AutopayAttempt a where a.status = 'SCHEDULED' and a.scheduledAt <= :now order by a.scheduledAt")
    List<Long> findDueIds(@Param("now") Instant now);

    Optional<AutopayAttempt> findByProviderOrderId(String providerOrderId);

    @Query("select a from AutopayAttempt a where a.subscriptionId = :subscriptionId and a.status in :statuses")
    List<AutopayAttempt> findBySubscriptionAndStatuses(@Param("subscriptionId") Long subscriptionId,
                                                       @Param("statuses") Collection<AutopayStatus> statuses);

    @Query("select coalesce(max(a.attemptNo), 0) from AutopayAttempt a where a.invoice.id = :invoiceId")
    int maxAttemptNo(@Param("invoiceId") Long invoiceId);

    @Query("select a.invoice.member.id from AutopayAttempt a where a.id = :id")
    Optional<Long> findMemberIdById(@Param("id") Long id);

}
