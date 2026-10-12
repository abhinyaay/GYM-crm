package com.gymops.payment;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    @Query(value = "select nextval('invoice_number_seq')", nativeQuery = true)
    long nextInvoiceSequence();

    @Query("select i.member.id from Invoice i where i.id = :id")
    Optional<Long> findMemberIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    @Query("select i from Invoice i where i.subscription.id = :subscriptionId and i.status <> 'VOID'")
    Optional<Invoice> findLiveBySubscriptionId(@Param("subscriptionId") Long subscriptionId);

    @Query("""
            select coalesce(sum(i.totalAmount), 0) from Invoice i
            where i.status = 'ISSUED' and (:allBranches = true or i.branch.id in :branchIds)
            """)
    BigDecimal sumOutstanding(@Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);

    @Query("""
            select count(i) from Invoice i
            where i.status = 'ISSUED' and (:allBranches = true or i.branch.id in :branchIds)
            """)
    long countOutstanding(@Param("allBranches") boolean allBranches, @Param("branchIds") Collection<Long> branchIds);

    @Query("""
            select count(i) from Invoice i
            where i.status = 'ISSUED' and i.dueDate < :today and (:allBranches = true or i.branch.id in :branchIds)
            """)
    long countOverdue(@Param("today") LocalDate today, @Param("allBranches") boolean allBranches,
                      @Param("branchIds") Collection<Long> branchIds);
}
