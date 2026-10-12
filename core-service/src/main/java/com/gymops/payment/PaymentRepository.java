package com.gymops.payment;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    @Query("select p.member.id from Payment p where p.id = :id")
    Optional<Long> findMemberIdById(@Param("id") Long id);

    Optional<Payment> findByProviderAndGatewayReference(PaymentProvider provider, String gatewayReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select p from Payment p where p.invoice.id = :invoiceId
              and p.status in ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')
            """)
    Optional<Payment> findSettledByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.status <> 'FAILED' and p.processedAt >= :from and p.processedAt < :to
              and (:allBranches = true or p.invoice.branch.id in :branchIds)
            """)
    BigDecimal sumCollected(@Param("from") Instant from, @Param("to") Instant to,
                            @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);
}
