package com.gymops.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    @Query(value = "select nextval('credit_note_number_seq')", nativeQuery = true)
    long nextCreditNoteSequence();

    List<Refund> findByPaymentIdOrderByProcessedAtAsc(Long paymentId);

    @Query("""
            select coalesce(sum(r.amount), 0) from Refund r
            where r.status = 'SUCCEEDED' and r.processedAt >= :from and r.processedAt < :to
              and (:allBranches = true or r.payment.invoice.branch.id in :branchIds)
            """)
    BigDecimal sumRefunded(@Param("from") Instant from, @Param("to") Instant to,
                           @Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);
}
