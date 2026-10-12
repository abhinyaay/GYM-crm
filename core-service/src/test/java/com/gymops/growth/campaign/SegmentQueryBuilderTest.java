package com.gymops.growth.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.growth.campaign.SegmentQueryBuilder.SegmentQuery;
import com.gymops.growth.campaign.SegmentQueryBuilder.SegmentSpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SegmentQueryBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static SegmentQuery build(SegmentSpec spec) {
        return SegmentQueryBuilder.build(spec, TODAY, IST, null);
    }

    @Test
    void everySegmentTargetsActiveMembersAndHonoursTheBranch() {
        for (CampaignSegment segment : CampaignSegment.values()) {
            SegmentQuery q = build(new SegmentSpec(segment, ChurnBand.HIGH, 7, 90, 4L));
            assertThat(q.sql()).contains("m.deleted_at is null and m.status = 'ACTIVE'")
                    .contains("(:allBranches or m.home_branch_id = :branchId)");
            assertThat(q.params()).containsEntry("allBranches", false).containsEntry("branchId", 4L);
        }
        assertThat(build(new SegmentSpec(CampaignSegment.LAPSED, null, 7, 90, null)).params())
                .containsEntry("allBranches", true);
    }

    @Test
    void expiringWithoutAutopay() {
        SegmentQuery q = build(new SegmentSpec(CampaignSegment.EXPIRING_NO_AUTOPAY, null, 7, null, null));
        assertThat(q.params()).containsEntry("today", TODAY).containsEntry("untilDate", LocalDate.of(2026, 10, 20));
        assertThat(q.sql()).contains("s.status = 'ACTIVE' and not s.auto_renew")
                .contains("s.end_date between :today and :untilDate")
                .contains("l.trial_subscription_id = s.id")
                .contains("n.status in ('SCHEDULED', 'PENDING_PAYMENT')");
    }

    @Test
    void lapsedWindowExcludesTrialsAndCurrentCustomers() {
        SegmentQuery q = build(new SegmentSpec(CampaignSegment.LAPSED, null, 7, 90, null));
        assertThat(q.params()).containsEntry("endedFrom", LocalDate.of(2026, 7, 15))
                .containsEntry("endedTo", LocalDate.of(2026, 10, 6));
        assertThat(q.sql()).contains("s.status = 'EXPIRED'").contains("l.trial_subscription_id = s.id")
                .contains("x.end_date between :endedFrom and :endedTo").contains(SegmentQueryBuilder.LIVE_STATUSES);
    }

    @Test
    void noRecentVisitUsesALocalMidnightCutoff() {
        SegmentQuery q = build(new SegmentSpec(CampaignSegment.NO_RECENT_VISIT, null, 14, null, null));
        assertThat(q.params()).containsEntry("visitCutoff", Timestamp.from(Instant.parse("2026-09-28T18:30:00Z")));
        assertThat(q.sql()).contains("a.check_in_time >= :visitCutoff").contains("m.registered_at < :visitCutoff");
    }

    @Test
    void trialEndedWithoutConversion() {
        SegmentQuery q = build(new SegmentSpec(CampaignSegment.TRIAL_NOT_CONVERTED, null, 10, null, null));
        assertThat(q.params()).containsEntry("trialEndedFrom", LocalDate.of(2026, 10, 3));
        assertThat(q.sql()).contains("l.status = 'TRIAL_ACTIVE'").contains("l.trial_ends_on < :today");
    }

    @Test
    void churnRiskRestrictsToTheAnalyticsIdsAndNeverToAnEmptyInList() {
        SegmentQuery q = SegmentQueryBuilder.build(new SegmentSpec(CampaignSegment.CHURN_RISK, ChurnBand.MEDIUM, null,
                null, null), TODAY, IST, List.of(5L, 6L));
        assertThat(q.sql()).contains("m.id in (:churnIds)");
        assertThat(q.params()).containsEntry("churnIds", List.of(5L, 6L));
        assertThat(build(new SegmentSpec(CampaignSegment.CHURN_RISK, ChurnBand.HIGH, null, null, null)).params())
                .containsEntry("churnIds", List.of(-1L));
    }

    @Test
    void missingParametersFailFastAndDescriptionsAreReadable() {
        assertThatThrownBy(() -> build(new SegmentSpec(CampaignSegment.LAPSED, null, 7, null, null)))
                .isInstanceOf(NullPointerException.class);
        assertThat(SegmentQueryBuilder.describe(new SegmentSpec(CampaignSegment.LAPSED, null, 7, 90, null)))
                .isEqualTo("Members whose membership expired 7–90 days ago");
        assertThat(SegmentQueryBuilder.describe(new SegmentSpec(CampaignSegment.CHURN_RISK, ChurnBand.MEDIUM, null, null,
                null))).isEqualTo("Members at medium or high churn risk");
    }
}
