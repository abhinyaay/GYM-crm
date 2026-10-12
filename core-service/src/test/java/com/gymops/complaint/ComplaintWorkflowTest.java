package com.gymops.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.support.Fixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ComplaintWorkflowTest {

    private final Instant now = Instant.parse("2026-03-01T10:00:00Z");

    private Complaint complaint() {
        return new Complaint("CMP-26-00001", null, Fixtures.branch(1), 1L, ComplaintCategory.EQUIPMENT,
                ComplaintPriority.MEDIUM, "Broken", "Broken bench", now.plusSeconds(3600));
    }

    @Test
    void stateMachineAllowsOnlyDefinedTransitions() {
        assertThat(ComplaintStatus.OPEN.canTransitionTo(ComplaintStatus.IN_PROGRESS)).isTrue();
        assertThat(ComplaintStatus.OPEN.canTransitionTo(ComplaintStatus.CLOSED)).isFalse();
        assertThat(ComplaintStatus.RESOLVED.canTransitionTo(ComplaintStatus.REOPENED)).isTrue();
        assertThat(ComplaintStatus.CLOSED.canTransitionTo(ComplaintStatus.REOPENED)).isFalse();
    }

    @Test
    void resolveRequiresResolutionText() {
        Complaint c = complaint();
        c.assignTo(9L, now);
        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.IN_PROGRESS);
        assertThat(c.getFirstResponseAt()).isEqualTo(now);
        assertThatThrownBy(() -> c.transitionTo(ComplaintStatus.RESOLVED, " ", now)).isInstanceOf(BusinessRuleException.class);
        c.transitionTo(ComplaintStatus.RESOLVED, "Replaced", now);
        c.transitionTo(ComplaintStatus.CLOSED, null, now);
        assertThatThrownBy(() -> c.transitionTo(ComplaintStatus.IN_PROGRESS, null, now))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void escalationRaisesLevelAndPriorityUpToMaximum() {
        Complaint c = complaint();
        assertThat(c.isSlaBreached(now.plusSeconds(7200))).isTrue();
        c.escalate(now.plusSeconds(10_000), now);
        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.ESCALATED);
        assertThat(c.getEscalationLevel()).isEqualTo((short) 1);
        assertThat(c.getPriority()).isEqualTo(ComplaintPriority.HIGH);
        c.escalate(now.plusSeconds(10_000), now);
        c.escalate(now.plusSeconds(10_000), now);
        assertThat(c.getPriority()).isEqualTo(ComplaintPriority.CRITICAL);
        assertThatThrownBy(() -> c.escalate(now, now)).isInstanceOf(BusinessRuleException.class);
    }
}
