package com.gymops.leads;

import com.gymops.common.event.DomainEvents;
import com.gymops.subscription.SubscriptionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Auto-conversion: when a member linked to an open lead activates a subscription that is not that
 * lead's own trial (i.e. they bought and paid for a real membership), the lead becomes CONVERTED.
 * Runs synchronously inside the activating transaction, so the conversion commits with the payment.
 */
@Component
@RequiredArgsConstructor
public class LeadConversionListener {

    private final LeadRepository repository;
    private final SubscriptionRepository subscriptionRepository;
    private final LeadTrialService trialService;

    @EventListener
    public void on(DomainEvents.SubscriptionActivated e) {
        if (TrialStartGuard.isStartingTrialFor(e.memberId())) {
            return;   // the trial's own activation while it is being started
        }
        List<Lead> open = repository.findOpenByMemberId(e.memberId());
        if (open.isEmpty()) {
            return;
        }
        String plan = subscriptionRepository.findById(e.subscriptionId())
                .map(s -> s.getPlan().getName()).orElse("a membership");
        for (Lead lead : open) {
            if (!lead.isTrialSubscription(e.subscriptionId())) {
                trialService.convert(lead, e.memberId(), "purchased " + plan, true);
            }
        }
    }
}
