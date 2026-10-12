package com.gymops.growth;

import com.gymops.common.event.DomainEvents;
import com.gymops.growth.campaign.CampaignAttributionService;
import com.gymops.growth.referral.ReferralConversionService;
import com.gymops.growth.referral.ReferralService;
import com.gymops.leads.LeadEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to business events AFTER they commit, each in its own transaction. Growth bookkeeping must
 * never roll back a payment, refund or enquiry, so failures are logged and left to the hourly
 * {@link GrowthJobs} sweep (pending referrals are re-checked; rewards are re-validated at release).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrowthEventListener {

    private final ReferralService referrals;
    private final ReferralConversionService conversion;
    private final CampaignAttributionService attribution;
    private final GrowthProperties props;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(DomainEvents.SubscriptionActivated e) {
        safely("referral conversion", e, () -> conversion.onMembershipActivated(e.memberId()));
        safely("campaign attribution", e, () -> attribution.attributeSubscription(e.subscriptionId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(DomainEvents.PaymentSucceeded e) {
        safely("campaign attribution", e, () -> attribution.attributePayment(e.paymentId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(DomainEvents.RefundProcessed e) {
        safely("referral reversal", e, () -> conversion.onRefund(e.refundId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(DomainEvents.SubscriptionCancelled e) {
        safely("referral reversal", e, () -> conversion.onSubscriptionCancelled(e.subscriptionId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(LeadEvents.ReferralCodeSubmitted e) {
        safely("referral capture", e, () -> referrals.captureFromEnquiry(e.leadId(), e.referralCode()));
    }

    private void safely(String what, Object event, Runnable action) {
        if (!props.enabled()) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.error("growth_{}_failed event={}: {}", what.replace(' ', '_'), event, ex.toString(), ex);
        }
    }
}
