package com.gymops.growth.campaign;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.ConflictException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.growth.GrowthProperties;
import com.gymops.growth.campaign.AudienceResolver.Audience;
import com.gymops.growth.campaign.AudienceResolver.AudienceMember;
import com.gymops.growth.campaign.CampaignDtos.RunResponse;
import com.gymops.growth.offer.OfferPurpose;
import com.gymops.growth.offer.PersonalOfferService;
import com.gymops.growth.offer.PersonalOfferService.IssuedOffer;
import com.gymops.growth.referral.RewardTexts;
import com.gymops.member.Member;
import com.gymops.member.MemberRepository;
import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import com.gymops.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends a campaign: resolves the audience, then messages each member in its own transaction
 * (member row lock first, cooldown check, personal offer code, outbox notification and recipient
 * row commit together). A per-campaign run lock ({@code run_lock_until}, taken with a conditional
 * UPDATE) stops a manual "run now" and the scheduled job from sending the same campaign twice at once;
 * the cooldown check under the member lock is the per-recipient de-duplication.
 */
@Slf4j
@Component
public class CampaignRunner {

    private static final Duration RUN_LOCK = Duration.ofMinutes(30);

    private final CampaignRepository repository;
    private final AudienceResolver audienceResolver;
    private final MemberRepository memberRepository;
    private final PersonalOfferService offers;
    private final NotificationService notifications;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CampaignRunner(CampaignRepository repository, AudienceResolver audienceResolver,
                          MemberRepository memberRepository, PersonalOfferService offers,
                          NotificationService notifications, AuditService audit, NamedParameterJdbcTemplate jdbc,
                          GrowthProperties props, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.audienceResolver = audienceResolver;
        this.memberRepository = memberRepository;
        this.offers = offers;
        this.notifications = notifications;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Immutable copy of what a run needs, read once at the start. */
    record Snapshot(Long id, String name, Long branchId, SegmentQueryBuilder.SegmentSpec spec, Campaign.Definition def) {
    }

    enum Outcome { SENT, SKIPPED }

    /**
     * Runs a campaign now. The caller has already checked access (manual) or picked it as due (job).
     *
     * @throws ApiException 409 if the campaign is already running, 503 if the churn-risk segment is unavailable
     */
    public RunResponse run(Long campaignId, RunTrigger trigger) {
        Instant started = Instant.now(clock);
        Long triggeredBy = CurrentUser.idOrNull();
        Snapshot snapshot = Objects.requireNonNull(tx.execute(s -> acquire(campaignId, started)));
        Long runId = Objects.requireNonNull(tx.execute(s -> insertRun(campaignId, trigger, triggeredBy, started)));
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        try {
            Audience audience = audienceResolver.resolve(snapshot.spec(), props.campaigns().maxAudience());
            jdbc.update("update campaign_runs set audience_size = :n where id = :id",
                    Map.of("n", audience.members().size(), "id", runId));
            for (AudienceMember m : audience.members()) {
                try {
                    Outcome outcome = tx.execute(s -> sendTo(snapshot, runId, m));
                    if (outcome == Outcome.SENT) {
                        sent++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.error("campaign_send_failed campaignId={} memberId={}: {}", campaignId, m.memberId(), e.toString(), e);
                }
            }
            finish(snapshot, runId, trigger, RunStatus.COMPLETED, sent, skipped, failed, null);
        } catch (RuntimeException e) {
            String error = e instanceof ApiException api ? api.getMessage() : e.toString();
            finish(snapshot, runId, trigger, RunStatus.FAILED, sent, skipped, failed, error);
            throw e;
        }
        return jdbc.query("select * from campaign_runs where id = :id", Map.of("id", runId), CampaignService::mapRun)
                .getFirst();
    }

    private Snapshot acquire(Long campaignId, Instant now) {
        Campaign c = repository.findById(campaignId).orElseThrow(() -> new ResourceNotFoundException("Campaign", campaignId));
        if (c.getStatus() == CampaignStatus.ARCHIVED) {
            throw CampaignService.notRunnable(c);
        }
        int locked = jdbc.update("""
                update campaigns set run_lock_until = :until
                where id = :id and (run_lock_until is null or run_lock_until < :now)
                """, new MapSqlParameterSource().addValue("id", campaignId)
                .addValue("until", Timestamp.from(now.plus(RUN_LOCK))).addValue("now", Timestamp.from(now)));
        if (locked == 0) {
            throw new ConflictException(ErrorCode.CONCURRENT_MODIFICATION, "Campaign " + campaignId + " is already running");
        }
        return new Snapshot(c.getId(), c.getName(), c.getBranchId(), c.segmentSpec(), CampaignService.definition(c));
    }

    private Long insertRun(Long campaignId, RunTrigger trigger, Long triggeredBy, Instant started) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("""
                insert into campaign_runs (campaign_id, trigger_type, status, triggered_by, started_at)
                values (:campaignId, :trigger, 'RUNNING', :by, :started)
                """, new MapSqlParameterSource().addValue("campaignId", campaignId).addValue("trigger", trigger.name())
                .addValue("by", triggeredBy).addValue("started", Timestamp.from(started)), keys, new String[]{"id"});
        return keys.getKeyAs(Long.class);
    }

    private Outcome sendTo(Snapshot c, Long runId, AudienceMember target) {
        Instant now = Instant.now(clock);
        Member member = memberRepository.findByIdForUpdate(target.memberId()).orElse(null);
        if (member == null || member.getDeletedAt() != null || !member.isActive()) {
            return Outcome.SKIPPED;
        }
        Campaign.Definition d = c.def();
        if (d.cooldownDays() > 0) {
            Boolean recent = jdbc.queryForObject("""
                    select exists (select 1 from campaign_recipients
                                   where campaign_id = :cid and member_id = :mid and sent_at > :cutoff)
                    """, new MapSqlParameterSource().addValue("cid", c.id()).addValue("mid", member.getId())
                    .addValue("cutoff", Timestamp.from(now.minus(Duration.ofDays(d.cooldownDays())))), Boolean.class);
            if (Boolean.TRUE.equals(recent)) {
                return Outcome.SKIPPED;
            }
        }
        IssuedOffer offer = null;
        if (d.offerEnabled()) {
            offer = offers.issue(member.getId(), "WB", d.offerDiscountType(), d.offerValue(), d.offerValidDays(),
                    OfferPurpose.CAMPAIGN_OFFER, "Personal offer from campaign '" + c.name() + "' (#" + c.id()
                            + ") for member " + member.getMemberCode());
        }
        var values = TemplateRenderer.values(member.getFirstName(), target.planName(),
                target.endDate() == null ? null : RewardTexts.day(target.endDate()), offer == null ? null : offer.code());
        String title = TemplateRenderer.render(d.titleTemplate(), values, CampaignService.TITLE_MAX);
        LocalDate validUntil = offer == null ? null : offer.validUntil();
        String body = CampaignService.renderBody(d.messageTemplate(), values, d.offerEnabled(), d.offerDiscountType(),
                d.offerValue(), validUntil);
        notifications.notifyMember(member, NotificationType.CAMPAIGN_MESSAGE, title, body,
                "campaign:" + c.id() + ":run:" + runId + ":member:" + member.getId());
        jdbc.update("""
                insert into campaign_recipients (campaign_id, run_id, member_id, branch_id, sent_at, discount_id, offer_code,
                                                 attribution_ends_at)
                values (:cid, :rid, :mid, :bid, :sent, :did, :code, :until)
                """, new MapSqlParameterSource().addValue("cid", c.id()).addValue("rid", runId)
                .addValue("mid", member.getId()).addValue("bid", member.getHomeBranch().getId())
                .addValue("sent", Timestamp.from(now)).addValue("did", offer == null ? null : offer.discountId())
                .addValue("code", offer == null ? null : offer.code())
                .addValue("until", Timestamp.from(now.plus(Duration.ofDays(d.attributionDays())))));
        return Outcome.SENT;
    }

    private void finish(Snapshot c, Long runId, RunTrigger trigger, RunStatus status, int sent, int skipped, int failed,
                        String error) {
        tx.executeWithoutResult(s -> {
            Instant now = Instant.now(clock);
            jdbc.update("""
                    update campaign_runs set status = :status, sent = :sent, skipped = :skipped, failed = :failed,
                                             error = :error, finished_at = :now
                    where id = :id
                    """, new MapSqlParameterSource().addValue("status", status.name()).addValue("sent", sent)
                    .addValue("skipped", skipped).addValue("failed", failed)
                    .addValue("error", error == null ? null : error.substring(0, Math.min(500, error.length())))
                    .addValue("now", Timestamp.from(now)).addValue("id", runId));
            // plain UPDATE (no @Version bump): a manager editing the campaign meanwhile must not make this fail
            Campaign campaign = repository.findById(c.id()).orElseThrow();
            Instant next = CampaignScheduler.nextRun(campaign.getSchedule(), campaign.getScheduleDayOfWeek(),
                    props.campaigns().sendHour(), clock.getZone(), now);
            jdbc.update("""
                    update campaigns set last_run_at = :now, run_lock_until = null,
                           next_run_at = case when status = 'ACTIVE' then cast(:next as timestamptz) else next_run_at end
                    where id = :id
                    """, new MapSqlParameterSource().addValue("now", Timestamp.from(now))
                    .addValue("next", next == null ? null : Timestamp.from(next)).addValue("id", c.id()));
            audit.record(AuditEvent.of("CAMPAIGN", c.id(), "RUN_" + status.name()).branch(c.branchId())
                    .with("runId", runId).with("trigger", trigger).with("sent", sent).with("skipped", skipped)
                    .with("failed", failed).with("error", error));
        });
        log.info("campaign_run campaignId={} runId={} trigger={} status={} sent={} skipped={} failed={}",
                c.id(), runId, trigger, status, sent, skipped, failed);
    }
}
