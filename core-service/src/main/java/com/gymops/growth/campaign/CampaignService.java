package com.gymops.growth.campaign;

import com.gymops.audit.AuditService;
import com.gymops.audit.AuditService.AuditEvent;
import com.gymops.common.api.PageResponse;
import com.gymops.common.exception.ApiException;
import com.gymops.common.exception.BusinessRuleException;
import com.gymops.common.exception.ErrorCode;
import com.gymops.common.exception.ForbiddenException;
import com.gymops.common.exception.ResourceNotFoundException;
import com.gymops.growth.GrowthProperties;
import com.gymops.growth.campaign.AudienceResolver.Audience;
import com.gymops.growth.campaign.AudienceResolver.AudienceMember;
import com.gymops.growth.campaign.CampaignDtos.CampaignRequest;
import com.gymops.growth.campaign.CampaignDtos.CampaignResponse;
import com.gymops.growth.campaign.CampaignDtos.CampaignStats;
import com.gymops.growth.campaign.CampaignDtos.PreviewRecipient;
import com.gymops.growth.campaign.CampaignDtos.PreviewResponse;
import com.gymops.growth.campaign.CampaignDtos.RecipientResponse;
import com.gymops.growth.campaign.CampaignDtos.RunResponse;
import com.gymops.growth.referral.RewardTexts;
import com.gymops.payment.DiscountType;
import com.gymops.security.AccessPolicy;
import com.gymops.security.BranchScope;
import com.gymops.security.CurrentUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign definitions, preview, status changes and reporting. Campaigns belong to one branch
 * (managers of that branch) or to every branch (org-wide staff only). Sending is in {@link CampaignRunner}.
 */
@Service
@RequiredArgsConstructor
public class CampaignService {

    static final int TITLE_MAX = 200;
    static final int BODY_MAX = 2000;
    static final String SAMPLE_CODE = "WBSAMPLE01";

    private final CampaignRepository repository;
    private final AudienceResolver audience;
    private final AccessPolicy access;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final GrowthProperties props;
    private final Clock clock;

    // ================================================================== CRUD

    @Transactional
    public CampaignResponse create(CampaignRequest r) {
        assertBranchScope(r.branchId());
        Campaign c = repository.saveAndFlush(new Campaign(r.branchId(), validate(r)));
        audit.record(AuditEvent.of("CAMPAIGN", c.getId(), "CREATED").branch(c.getBranchId())
                .with("segment", c.getSegment()).with("schedule", c.getSchedule()));
        return response(c);
    }

    @Transactional
    public CampaignResponse update(Long id, CampaignRequest r) {
        Campaign c = load(id);
        if (r.branchId() != null ? !r.branchId().equals(c.getBranchId()) : c.getBranchId() != null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "A campaign's branch cannot be changed");
        }
        c.apply(validate(r));
        if (c.getStatus() == CampaignStatus.ACTIVE) {
            c.activate(nextRun(c, Instant.now(clock)));   // schedule may have changed
        }
        repository.saveAndFlush(c);
        audit.record(AuditEvent.of("CAMPAIGN", id, "UPDATED").branch(c.getBranchId()));
        return response(c);
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(Long id) {
        return response(load(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignResponse> list(Long branchId, CampaignStatus status, int page, int size) {
        BranchScope scope = access.scope(branchId);
        boolean orgWide = CurrentUser.get().isOrgWide();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("all", scope.all())
                .addValue("branchIds", scope.ids()).addValue("orgWide", orgWide && branchId == null)
                .addValue("status", status == null ? null : status.name())
                .addValue("limit", size).addValue("offset", (long) page * size);
        String where = """
                 where ((c.branch_id is null and :orgWide) or (c.branch_id is not null and (:all or c.branch_id in (:branchIds))))
                   and (cast(:status as text) is null or c.status = cast(:status as text))
                """;
        Long total = jdbc.queryForObject("select count(*) from campaigns c" + where, p, Long.class);
        List<Long> ids = jdbc.queryForList("select c.id from campaigns c" + where
                + " order by (c.status = 'ARCHIVED'), c.created_at desc, c.id desc limit :limit offset :offset", p, Long.class);
        Map<Long, CampaignStats> stats = stats(ids);
        Map<Long, Campaign> byId = new HashMap<>();
        repository.findAllById(ids).forEach(c -> byId.put(c.getId(), c));
        Map<Long, String> branchNames = branchNames(byId.values());
        List<CampaignResponse> content = ids.stream().map(byId::get)
                .map(c -> response(c, stats.getOrDefault(c.getId(), CampaignStats.EMPTY), branchNames)).toList();
        long count = total == null ? 0 : total;
        int pages = (int) ((count + size - 1) / size);
        return new PageResponse<>(content, page, size, count, pages, page + 1 >= pages);
    }

    // ================================================================== status

    @Transactional
    public CampaignResponse activate(Long id) {
        Campaign c = load(id);
        c.activate(nextRun(c, Instant.now(clock)));
        repository.saveAndFlush(c);
        audit.record(AuditEvent.of("CAMPAIGN", id, "ACTIVATED").branch(c.getBranchId()).with("nextRunAt", c.getNextRunAt()));
        return response(c);
    }

    @Transactional
    public CampaignResponse pause(Long id) {
        Campaign c = load(id);
        c.pause();
        repository.saveAndFlush(c);
        audit.record(AuditEvent.of("CAMPAIGN", id, "PAUSED").branch(c.getBranchId()));
        return response(c);
    }

    @Transactional
    public CampaignResponse archive(Long id) {
        Campaign c = load(id);
        c.archive();
        repository.saveAndFlush(c);
        audit.record(AuditEvent.of("CAMPAIGN", id, "ARCHIVED").branch(c.getBranchId()));
        return response(c);
    }

    // ================================================================== preview

    /** Preview of an unsaved definition. */
    @Transactional(readOnly = true)
    public PreviewResponse preview(CampaignRequest r) {
        assertBranchScope(r.branchId());
        Campaign.Definition d = validate(r);
        return preview(new SegmentQueryBuilder.SegmentSpec(d.segment(), d.churnBand(), d.segmentDays(),
                d.segmentDaysMax(), r.branchId()), d, null);
    }

    /** Preview of a saved campaign, including how many members are still in its cooldown. */
    @Transactional(readOnly = true)
    public PreviewResponse preview(Long id) {
        Campaign c = load(id);
        return preview(c.segmentSpec(), definition(c), c);
    }

    private PreviewResponse preview(SegmentQueryBuilder.SegmentSpec spec, Campaign.Definition d, Campaign saved) {
        GrowthProperties.Campaigns cfg = props.campaigns();
        Audience a = audience.resolve(spec, cfg.maxAudience());
        Set<Long> cooling = saved == null ? Set.of()
                : inCooldown(saved.getId(), saved.getCooldownDays(), a.members().stream().map(AudienceMember::memberId).toList());
        List<PreviewRecipient> sample = a.members().stream().limit(cfg.previewSample()).map(m -> {
            var values = TemplateRenderer.values(m.firstName(), m.planName(),
                    m.endDate() == null ? null : RewardTexts.day(m.endDate()), d.offerEnabled() ? SAMPLE_CODE : null);
            return new PreviewRecipient(m.memberId(), m.memberCode(), m.fullName(), m.branchName(), m.planName(),
                    m.endDate(), cooling.contains(m.memberId()), TemplateRenderer.render(d.titleTemplate(), values, TITLE_MAX),
                    renderBody(d.messageTemplate(), values, d.offerEnabled(), d.offerDiscountType(), d.offerValue(),
                            d.offerEnabled() ? LocalDate.now(clock).plusDays(d.offerValidDays() - 1L) : null));
        }).toList();
        List<String> warnings = new ArrayList<>();
        if (a.truncated()) {
            warnings.add("Audience capped at " + cfg.maxAudience() + " members per run (app.growth.campaigns.max-audience)");
        }
        if (d.offerEnabled() && !TemplateRenderer.placeholders(d.messageTemplate()).contains(TemplateRenderer.OFFER_CODE)) {
            warnings.add("The message has no {offerCode}; the personal code is appended at the end");
        }
        return new PreviewResponse(SegmentQueryBuilder.describe(spec), a.members().size(), a.truncated(), cooling.size(),
                a.members().size() - cooling.size(), sample, warnings);
    }

    /** Message body: the rendered template, plus the offer line when the template does not place the code itself. */
    static String renderBody(String template, Map<String, String> values, boolean offerEnabled, DiscountType type,
                             BigDecimal value, LocalDate validUntil) {
        String body = TemplateRenderer.render(template, values, BODY_MAX);
        if (offerEnabled && !TemplateRenderer.placeholders(template).contains(TemplateRenderer.OFFER_CODE)) {
            String offer = "\n\nYour personal code " + values.get(TemplateRenderer.OFFER_CODE) + ": "
                    + RewardTexts.discount(type, value) + " your next membership, valid until "
                    + RewardTexts.day(validUntil) + ".";
            body = (body.length() + offer.length() > BODY_MAX ? body.substring(0, BODY_MAX - offer.length()) : body) + offer;
        }
        return body;
    }

    // ================================================================== runs & recipients

    @Transactional(readOnly = true)
    public List<RunResponse> runs(Long id) {
        load(id);
        return jdbc.query("select * from campaign_runs where campaign_id = :id order by started_at desc, id desc limit 50",
                Map.of("id", id), CampaignService::mapRun);
    }

    @Transactional(readOnly = true)
    public PageResponse<RecipientResponse> recipients(Long id, Boolean converted, int page, int size) {
        load(id);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id)
                .addValue("converted", converted).addValue("limit", size).addValue("offset", (long) page * size);
        String where = " where r.campaign_id = :id and (cast(:converted as boolean) is null"
                + " or (r.converted_at is not null) = cast(:converted as boolean))";
        Long total = jdbc.queryForObject("select count(*) from campaign_recipients r" + where, p, Long.class);
        List<RecipientResponse> rows = jdbc.query("""
                select r.*, m.member_code, m.first_name || ' ' || m.last_name as member_name, b.name as branch_name
                from campaign_recipients r join members m on m.id = r.member_id join gym_branches b on b.id = r.branch_id
                """ + where + " order by r.sent_at desc, r.id desc limit :limit offset :offset", p,
                (rs, n) -> new RecipientResponse(rs.getLong("id"), rs.getLong("run_id"), rs.getLong("member_id"),
                        rs.getString("member_code"), rs.getString("member_name"), rs.getString("branch_name"),
                        instant(rs, "sent_at"), rs.getString("offer_code"), instant(rs, "attribution_ends_at"),
                        instant(rs, "converted_at"), rs.getBigDecimal("conversion_amount"), rs.getBoolean("offer_redeemed")));
        long count = total == null ? 0 : total;
        int pages = (int) ((count + size - 1) / size);
        return new PageResponse<>(rows, page, size, count, pages, page + 1 >= pages);
    }

    // ================================================================== shared helpers

    /** Loads a campaign the caller may manage (403 otherwise). */
    @Transactional(readOnly = true)
    public Campaign load(Long id) {
        Campaign c = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Campaign", id));
        assertBranchScope(c.getBranchId());
        return c;
    }

    private void assertBranchScope(Long branchId) {
        if (branchId == null) {
            if (!CurrentUser.get().isOrgWide()) {
                throw new ForbiddenException("Only organisation-wide staff can manage all-branch campaigns; choose a branch");
            }
        } else {
            access.assertBranchAccess(branchId);
        }
    }

    Instant nextRun(Campaign c, Instant after) {
        return CampaignScheduler.nextRun(c.getSchedule(), c.getScheduleDayOfWeek(), props.campaigns().sendHour(),
                clock.getZone(), after);
    }

    /** Members of {@code memberIds} messaged by this campaign within its cooldown. */
    Set<Long> inCooldown(Long campaignId, int cooldownDays, Collection<Long> memberIds) {
        if (cooldownDays <= 0 || memberIds.isEmpty()) {
            return Set.of();
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(cooldownDays));
        return new HashSet<>(jdbc.queryForList("""
                select distinct member_id from campaign_recipients
                where campaign_id = :id and sent_at > :cutoff and member_id in (:ids)
                """, new MapSqlParameterSource().addValue("id", campaignId).addValue("cutoff", Timestamp.from(cutoff))
                .addValue("ids", memberIds), Long.class));
    }

    /** Validates a request into a definition (applies configured defaults). */
    Campaign.Definition validate(CampaignRequest r) {
        List<String> errors = new ArrayList<>();
        switch (r.segment()) {
            case CHURN_RISK -> {
                if (r.churnBand() == null) {
                    errors.add("churnBand is required for the churn-risk segment (HIGH or MEDIUM)");
                }
            }
            case LAPSED -> {
                if (r.segmentDays() == null || r.segmentDaysMax() == null) {
                    errors.add("segmentDays and segmentDaysMax (days since expiry, e.g. 7 and 90) are required");
                } else if (r.segmentDaysMax() < r.segmentDays()) {
                    errors.add("segmentDaysMax must not be less than segmentDays");
                }
            }
            default -> {
                if (r.segmentDays() == null) {
                    errors.add("segmentDays is required for " + r.segment());
                }
            }
        }
        if (r.offerEnabled()) {
            if (r.offerDiscountType() == null || r.offerValue() == null || r.offerValidDays() == null) {
                errors.add("an offer needs offerDiscountType, offerValue and offerValidDays");
            } else if (r.offerDiscountType() == DiscountType.PERCENTAGE && r.offerValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                errors.add("a percentage offer cannot exceed 100");
            }
        }
        CampaignSchedule schedule = r.schedule() == null ? CampaignSchedule.NONE : r.schedule();
        if (schedule == CampaignSchedule.WEEKLY && r.scheduleDayOfWeek() == null) {
            errors.add("scheduleDayOfWeek (1 = Monday ... 7 = Sunday) is required for a weekly campaign");
        }
        errors.addAll(TemplateRenderer.validate(r.titleTemplate(), r.offerEnabled()).stream().map(e -> "title: " + e).toList());
        errors.addAll(TemplateRenderer.validate(r.messageTemplate(), r.offerEnabled()).stream().map(e -> "message: " + e)
                .toList());
        if (!errors.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, String.join("; ", errors));
        }
        GrowthProperties.Campaigns cfg = props.campaigns();
        return new Campaign.Definition(r.name(), r.description(), r.segment(), r.churnBand(), r.segmentDays(),
                r.segmentDaysMax(), r.titleTemplate(), r.messageTemplate(), r.offerEnabled(), r.offerDiscountType(),
                r.offerValue(), r.offerValidDays(), schedule, r.scheduleDayOfWeek(),
                r.cooldownDays() == null ? cfg.cooldownDays() : r.cooldownDays(),
                r.attributionDays() == null ? cfg.attributionDays() : r.attributionDays());
    }

    static Campaign.Definition definition(Campaign c) {
        return new Campaign.Definition(c.getName(), c.getDescription(), c.getSegment(), c.getChurnBand(),
                c.getSegmentDays(), c.getSegmentDaysMax(), c.getTitleTemplate(), c.getMessageTemplate(), c.isOfferEnabled(),
                c.getOfferDiscountType(), c.getOfferValue(), c.getOfferValidDays(), c.getSchedule(),
                c.getScheduleDayOfWeek(), c.getCooldownDays(), c.getAttributionDays());
    }

    CampaignResponse response(Campaign c) {
        return response(c, stats(List.of(c.getId())).getOrDefault(c.getId(), CampaignStats.EMPTY),
                branchNames(List.of(c)));
    }

    private CampaignResponse response(Campaign c, CampaignStats stats, Map<Long, String> branchNames) {
        return new CampaignResponse(c.getId(), c.getName(), c.getDescription(), c.getBranchId(),
                c.getBranchId() == null ? null : branchNames.get(c.getBranchId()), c.getSegment(), c.getChurnBand(),
                c.getSegmentDays(), c.getSegmentDaysMax(), SegmentQueryBuilder.describe(c.segmentSpec()),
                c.getTitleTemplate(), c.getMessageTemplate(), c.isOfferEnabled(), c.getOfferDiscountType(),
                c.getOfferValue(), c.getOfferValidDays(), c.getSchedule(), c.getScheduleDayOfWeek(), c.getStatus(),
                c.getCooldownDays(), c.getAttributionDays(), c.getNextRunAt(), c.getLastRunAt(), c.getCreatedAt(),
                c.getUpdatedAt(), c.getVersion(), stats);
    }

    private Map<Long, String> branchNames(Collection<Campaign> campaigns) {
        List<Long> ids = campaigns.stream().map(Campaign::getBranchId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.query("select id, name from gym_branches where id in (:ids)", Map.of("ids", ids),
                    rs -> {
                        names.put(rs.getLong("id"), rs.getString("name"));
                    });
        }
        return names;
    }

    Map<Long, CampaignStats> stats(Collection<Long> campaignIds) {
        Map<Long, CampaignStats> out = new HashMap<>();
        if (campaignIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select r.campaign_id, count(*) as sent, count(distinct r.member_id) as members,
                       count(r.converted_at) as converted, coalesce(sum(r.conversion_amount), 0) as revenue,
                       count(r.discount_id) as offers, count(*) filter (where r.offer_redeemed) as redeemed,
                       (select count(*) from campaign_runs x where x.campaign_id = r.campaign_id) as runs
                from campaign_recipients r where r.campaign_id in (:ids) group by r.campaign_id
                """, Map.of("ids", campaignIds), rs -> {
                    long sent = rs.getLong("sent");
                    long converted = rs.getLong("converted");
                    double rate = sent == 0 ? 0 : BigDecimal.valueOf(converted)
                            .divide(BigDecimal.valueOf(sent), 4, RoundingMode.HALF_UP).doubleValue();
                    out.put(rs.getLong("campaign_id"), new CampaignStats(sent, rs.getLong("members"), converted,
                            rs.getBigDecimal("revenue"), rs.getLong("offers"), rs.getLong("redeemed"), rate,
                            rs.getLong("runs")));
                });
        // campaigns that ran without reaching anyone still report their runs
        jdbc.query("select campaign_id, count(*) as runs from campaign_runs where campaign_id in (:ids) group by campaign_id",
                Map.of("ids", campaignIds), rs -> {
                    out.putIfAbsent(rs.getLong("campaign_id"), new CampaignStats(0, 0, 0, BigDecimal.ZERO, 0, 0, 0,
                            rs.getLong("runs")));
                });
        return out;
    }

    static RunResponse mapRun(ResultSet rs, int n) throws SQLException {
        return new RunResponse(rs.getLong("id"), rs.getLong("campaign_id"), RunTrigger.valueOf(rs.getString("trigger_type")),
                RunStatus.valueOf(rs.getString("status")), rs.getInt("audience_size"), rs.getInt("sent"),
                rs.getInt("skipped"), rs.getInt("failed"), rs.getString("error"), instant(rs, "started_at"),
                instant(rs, "finished_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static ApiException notRunnable(Campaign c) {
        return new BusinessRuleException("Campaign " + c.getId() + " is " + c.getStatus() + " and cannot be run");
    }
}
