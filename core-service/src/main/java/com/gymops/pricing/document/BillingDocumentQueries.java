package com.gymops.pricing.document;

import com.gymops.payment.Payment;
import com.gymops.payment.Refund;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads for tax documents: an invoice's payment history and refunds. */
public interface BillingDocumentQueries extends Repository<Payment, Long> {

    @Query("select p from Payment p where p.invoice.id = :invoiceId order by p.processedAt asc, p.id asc")
    List<Payment> paymentsOf(@Param("invoiceId") Long invoiceId);

    @Query("""
            select r from Refund r join fetch r.payment p
            where p.invoice.id = :invoiceId and r.status = 'SUCCEEDED' order by r.processedAt asc, r.id asc
            """)
    List<Refund> refundsOf(@Param("invoiceId") Long invoiceId);

    @Query("""
            select r from Refund r join fetch r.payment p join fetch p.invoice i join fetch i.member join fetch i.branch
            where r.id = :refundId
            """)
    Refund refund(@Param("refundId") Long refundId);
}
