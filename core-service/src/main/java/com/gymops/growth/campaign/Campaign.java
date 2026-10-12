package com.gymops.growth.campaign;

import com.gymops.common.exception.InvalidStateTransitionException;
import com.gymops.common.persistence.BaseEntity;
import com.gymops.payment.DiscountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A retention / win-back campaign definition. Validation lives in {@link CampaignService}; the DB CHECKs mirror it. */
@Getter
@Entity
@Table(name = "campaigns")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "branch_id", updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(name = "churn_band")
    private ChurnBand churnBand;

    @Column(name = "segment_days")
    private Integer segmentDays;

    @Column(name = "segment_days_max")
    private Integer segmentDaysMax;

    @Column(name = "title_template", nullable = false)
    private String titleTemplate;

    @Column(name = "message_template", nullable = false)
    private String messageTemplate;

    @Column(name = "offer_enabled", nullable = false)
    private boolean offerEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "offer_discount_type")
    private DiscountType offerDiscountType;

    @Column(name = "offer_value")
    private BigDecimal offerValue;

    @Column(name = "offer_valid_days")
    private Integer offerValidDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignSchedule schedule = CampaignSchedule.NONE;

    @Column(name = "schedule_day_of_week")
    private Integer scheduleDayOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "cooldown_days", nullable = false)
    private int cooldownDays;

    @Column(name = "attribution_days", nullable = false)
    private int attributionDays;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** Validated definition (see {@link CampaignService#validate}). */
    public record Definition(String name, String description, CampaignSegment segment, ChurnBand churnBand,
                             Integer segmentDays, Integer segmentDaysMax, String titleTemplate, String messageTemplate,
                             boolean offerEnabled, DiscountType offerDiscountType, BigDecimal offerValue,
                             Integer offerValidDays, CampaignSchedule schedule, Integer scheduleDayOfWeek,
                             int cooldownDays, int attributionDays) {
    }

    public Campaign(Long branchId, Definition d) {
        this.branchId = branchId;
        apply(d);
    }

    public final void apply(Definition d) {
        requireNotArchived("edit");
        this.name = d.name().trim();
        this.description = d.description() == null || d.description().isBlank() ? null : d.description().trim();
        this.segment = d.segment();
        this.churnBand = d.segment() == CampaignSegment.CHURN_RISK ? d.churnBand() : null;
        this.segmentDays = d.segment() == CampaignSegment.CHURN_RISK ? null : d.segmentDays();
        this.segmentDaysMax = d.segment() == CampaignSegment.LAPSED ? d.segmentDaysMax() : null;
        this.titleTemplate = d.titleTemplate().trim();
        this.messageTemplate = d.messageTemplate().trim();
        this.offerEnabled = d.offerEnabled();
        this.offerDiscountType = d.offerEnabled() ? d.offerDiscountType() : null;
        this.offerValue = d.offerEnabled() ? d.offerValue() : null;
        this.offerValidDays = d.offerEnabled() ? d.offerValidDays() : null;
        this.schedule = d.schedule();
        this.scheduleDayOfWeek = d.schedule() == CampaignSchedule.WEEKLY ? d.scheduleDayOfWeek() : null;
        this.cooldownDays = d.cooldownDays();
        this.attributionDays = d.attributionDays();
        if (schedule == CampaignSchedule.NONE && status == CampaignStatus.ACTIVE) {
            this.status = CampaignStatus.DRAFT;   // a one-off campaign cannot stay scheduled
            this.nextRunAt = null;
        }
    }

    /** Puts a recurring campaign on its schedule. */
    public void activate(Instant firstRun) {
        requireNotArchived("activate");
        if (schedule == CampaignSchedule.NONE) {
            throw new InvalidStateTransitionException("campaign", status, "schedule a one-off");
        }
        this.status = CampaignStatus.ACTIVE;
        this.nextRunAt = firstRun;
    }

    public void pause() {
        if (status != CampaignStatus.ACTIVE) {
            throw new InvalidStateTransitionException("campaign", status, "pause");
        }
        this.status = CampaignStatus.PAUSED;
        this.nextRunAt = null;
    }

    public void archive() {
        requireNotArchived("archive");
        this.status = CampaignStatus.ARCHIVED;
        this.nextRunAt = null;
    }

    public void requireNotArchived(String action) {
        if (status == CampaignStatus.ARCHIVED) {
            throw new InvalidStateTransitionException("campaign", status, action);
        }
    }

    public SegmentQueryBuilder.SegmentSpec segmentSpec() {
        return new SegmentQueryBuilder.SegmentSpec(segment, churnBand, segmentDays, segmentDaysMax, branchId);
    }
}
