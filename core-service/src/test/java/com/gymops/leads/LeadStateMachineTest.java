package com.gymops.leads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class LeadStateMachineTest {

    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");

    private Lead lead() {
        return new Lead(Fixtures.branch(1), " Asha ", " ", "Asha@Example.COM", "+91 98765-43210", LeadSource.WALK_IN,
                null, null);
    }

    @Test
    void transitionTableMatchesThePipeline() {
        assertThat(LeadStatus.NEW.canTransitionTo(LeadStatus.CONTACTED)).isTrue();
        assertThat(LeadStatus.CONTACTED.canTransitionTo(LeadStatus.TOUR_SCHEDULED)).isTrue();
        assertThat(LeadStatus.TOUR_SCHEDULED.canTransitionTo(LeadStatus.TRIAL_ACTIVE)).isTrue();
        assertThat(LeadStatus.TRIAL_ACTIVE.canTransitionTo(LeadStatus.CONVERTED)).isTrue();
        assertThat(LeadStatus.TOUR_SCHEDULED.canTransitionTo(LeadStatus.CONTACTED)).as("no going backwards").isFalse();
        assertThat(LeadStatus.TRIAL_ACTIVE.canTransitionTo(LeadStatus.TOUR_SCHEDULED)).isFalse();
        for (LeadStatus open : LeadStatus.OPEN) {
            assertThat(open.canTransitionTo(LeadStatus.LOST)).as(open + " -> LOST").isTrue();
            assertThat(open.canTransitionTo(LeadStatus.CONVERTED)).as(open + " -> CONVERTED").isTrue();
        }
        assertThat(LeadStatus.LOST.canTransitionTo(LeadStatus.NEW)).isTrue();
        assertThat(LeadStatus.LOST.canTransitionTo(LeadStatus.CONTACTED)).isFalse();
        assertThat(EnumSet.allOf(LeadStatus.class).stream().noneMatch(LeadStatus.CONVERTED::canTransitionTo))
                .as("CONVERTED is final").isTrue();
    }

    @Test
    void manualTargetsExcludeWorkflowOnlyStates() {
        assertThat(LeadStatus.NEW.manualTargets())
                .containsExactlyInAnyOrder(LeadStatus.CONTACTED, LeadStatus.TOUR_SCHEDULED, LeadStatus.LOST);
        assertThat(LeadStatus.TRIAL_ACTIVE.manualTargets()).containsExactly(LeadStatus.LOST);
        assertThat(LeadStatus.LOST.manualTargets()).containsExactly(LeadStatus.NEW);
        assertThat(LeadStatus.CONVERTED.manualTargets()).isEmpty();
    }

    @Test
    void newLeadIsNormalised() {
        Lead l = lead();
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getFirstName()).isEqualTo("Asha");
        assertThat(l.getLastName()).isNull();
        assertThat(l.getEmail()).isEqualTo("asha@example.com");
        assertThat(l.getPhone()).isEqualTo("+919876543210");
        assertThat(l.getInterest()).isEqualTo(LeadInterest.MEMBERSHIP);
        assertThat(Lead.normalizePhone("98765 43210")).isEqualTo("9876543210");
    }

    @Test
    void lostRequiresReasonAndReopenClearsIt() {
        Lead l = lead();
        assertThatThrownBy(() -> l.moveTo(LeadStatus.LOST, null)).isInstanceOf(BusinessRuleException.class);
        l.scheduleNextFollowUp(now);
        l.moveTo(LeadStatus.LOST, LostReason.PRICE);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.LOST);
        assertThat(l.getLostReason()).isEqualTo(LostReason.PRICE);
        assertThat(l.getNextFollowUpAt()).isNull();
        assertThatThrownBy(() -> l.moveTo(LeadStatus.CONTACTED, null)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> l.assignOwner(5L)).isInstanceOf(InvalidStateTransitionException.class);
        l.moveTo(LeadStatus.NEW, null);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(l.getLostReason()).isNull();
    }

    @Test
    void trialAndConversionUseDedicatedActions() {
        Lead l = lead();
        assertThatThrownBy(() -> l.moveTo(LeadStatus.TRIAL_ACTIVE, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> l.moveTo(LeadStatus.CONVERTED, null)).isInstanceOf(BusinessRuleException.class);
        assertThat(l.recordContact()).isTrue();
        assertThat(l.recordContact()).as("only NEW moves on contact").isFalse();
        l.startTrial(77L, 9L, LocalDate.of(2026, 10, 3));
        assertThat(l.getStatus()).isEqualTo(LeadStatus.TRIAL_ACTIVE);
        assertThat(l.isTrialSubscription(77L)).isTrue();
        assertThat(l.isTrialSubscription(78L)).isFalse();
        assertThatThrownBy(() -> l.startTrial(78L, 9L, null)).isInstanceOf(InvalidStateTransitionException.class);
        l.convert(9L, now);
        assertThat(l.getStatus()).isEqualTo(LeadStatus.CONVERTED);
        assertThat(l.getConvertedAt()).isEqualTo(now);
        assertThat(l.isOpen()).isFalse();
        assertThatThrownBy(() -> l.moveTo(LeadStatus.LOST, LostReason.OTHER))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void followUpTasksCompleteOnce() {
        Lead l = lead();
        LeadActivity f = LeadActivity.followUp(l, "Call back", now.plusSeconds(60), 1L, now);
        assertThat(f.isOpenFollowUp()).isTrue();
        assertThat(f.isOverdue(now)).isFalse();
        assertThat(f.isOverdue(now.plusSeconds(120))).isTrue();
        f.complete(now);
        assertThat(f.isOverdue(now.plusSeconds(120))).isFalse();
        assertThatThrownBy(() -> f.complete(now)).isInstanceOf(BusinessRuleException.class);
        LeadActivity note = LeadActivity.log(l, LeadActivityType.NOTE, "x".repeat(3000), 1L, now);
        assertThat(note.getBody()).hasSize(LeadActivity.MAX_BODY);
        assertThatThrownBy(() -> note.complete(now)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> LeadActivity.log(l, LeadActivityType.FOLLOW_UP, "x", 1L, now))
                .isInstanceOf(BusinessRuleException.class);
    }
}
