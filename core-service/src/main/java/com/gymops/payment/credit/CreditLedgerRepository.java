package com.gymops.payment.credit;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditLedgerRepository extends JpaRepository<CreditLedgerEntry, Long> {

    Optional<CreditLedgerEntry> findFirstByMemberIdOrderByIdDesc(Long memberId);

    Page<CreditLedgerEntry> findByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);
}
