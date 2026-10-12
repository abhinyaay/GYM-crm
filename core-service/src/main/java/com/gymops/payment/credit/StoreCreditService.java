package com.gymops.payment.credit;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.common.util.Money;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.security.AccessPolicy;
import com.gymops.security.CurrentUser;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member store credit (wallet). The balance is the {@code balance_after} of the member's latest
 * ledger row; every posting happens under the member row lock, so postings are serialised and the
 * running balance is exact.
 */
@Service
@RequiredArgsConstructor
public class StoreCreditService {

    private final CreditLedgerRepository ledger;
    private final MemberRepository memberRepository;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public record CreditEntryResponse(Long id, CreditEntryType entryType, BigDecimal amount, BigDecimal balanceAfter,
                                      String reason, Long paymentId, Long refundId, Instant createdAt) {
        static CreditEntryResponse from(CreditLedgerEntry e) {
            return new CreditEntryResponse(e.getId(), e.getEntryType(), e.getAmount(), e.getBalanceAfter(), e.getReason(),
                    e.getPaymentId(), e.getRefundId(), e.getCreatedAt());
        }
    }

    public record CreditAccountResponse(Long memberId, BigDecimal balance, List<CreditEntryResponse> entries) {
    }

    /** Positive amount = credit issued; negative = correction (ADJUSTMENT). */
    public record CreditAdjustmentRequest(
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 300) String reason) {
    }

    @Transactional(readOnly = true)
    public CreditAccountResponse account(Long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertMemberAccess(member.getId(), member.getHomeBranch().getId());
        List<CreditEntryResponse> entries = ledger.findByMemberIdOrderByIdDesc(memberId, PageRequest.of(0, 50))
                .map(CreditEntryResponse::from).getContent();
        return new CreditAccountResponse(memberId, balance(memberId), entries);
    }

    @Transactional
    public CreditAccountResponse adjust(Long memberId, CreditAdjustmentRequest request) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", memberId));
        access.assertBranchAccess(member.getHomeBranch().getId());
        if (request.amount().signum() == 0) {
            throw new BusinessRuleException("Amount must not be zero");
        }
        boolean issue = request.amount().signum() > 0;
        CreditLedgerEntry entry = post(memberId, issue ? CreditEntryType.ISSUED : CreditEntryType.ADJUSTMENT,
                request.amount(), request.reason(), null, null);
        audit.record(AuditEvent.of("STORE_CREDIT", entry.getId(), issue ? "ISSUED" : "ADJUSTED").member(memberId)
                .branch(member.getHomeBranch().getId()).with("amount", request.amount())
                .with("balanceAfter", entry.getBalanceAfter()).with("reason", request.reason()));
        if (issue) {
            notifications.notifyMember(member, NotificationType.STORE_CREDIT_ISSUED, "Store credit added",
                    "₹" + request.amount() + " store credit was added to your account (" + request.reason()
                            + "). Balance: ₹" + entry.getBalanceAfter() + ".", null);
        }
        return account(memberId);
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(Long memberId) {
        return ledger.findFirstByMemberIdOrderByIdDesc(memberId).map(CreditLedgerEntry::getBalanceAfter).orElse(Money.ZERO);
    }

    /**
     * Appends a ledger row. The caller must hold the member row lock.
     *
     * @throws BusinessRuleException if the posting would make the balance negative
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditLedgerEntry post(Long memberId, CreditEntryType type, BigDecimal amount, String reason, Long paymentId,
                                  Long refundId) {
        BigDecimal after = balance(memberId).add(amount);
        if (after.signum() < 0) {
            throw new BusinessRuleException("Insufficient store credit (balance ₹" + balance(memberId) + ")");
        }
        return ledger.save(new CreditLedgerEntry(memberId, type, amount, after, reason, paymentId, refundId,
                CurrentUser.idOrNull(), Instant.now(clock)));
    }
}
