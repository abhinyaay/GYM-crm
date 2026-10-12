package com.gymops.growth;

import com.gymops.growth.offer.OfferPurpose;
import com.gymops.growth.offer.PersonalOfferService;
import com.gymops.growth.referral.ReferralRules;
import com.gymops.member.MemberRepository;
import com.gymops.payment.DiscountType;
import com.gymops.payment.credit.CreditEntryType;
import com.gymops.payment.credit.StoreCreditService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo referrals and campaigns so the growth screens are not empty: three referrers (the first is the
 * {@code member1.*} demo login of the first branch) with converted (rewards paid out or still held),
 * reversed, pending and rejected referrals, plus two example campaigns with a past run, recipients and
 * conversions. Runs only with {@code app.bootstrap.demo-data=true} and while the growth tables are empty.
 */
@Slf4j
@Order(7)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class GrowthDemoSeeder implements ApplicationRunner {

    private static final Duration DAY = Duration.ofDays(1);

    private final JdbcTemplate jdbc;
    private final StoreCreditService storeCredit;
    private final PersonalOfferService offers;
    private final MemberRepository memberRepository;
    private final GrowthProperties props;
    private final Clock clock;
    private final Random random = new Random(11);

    private record Person(long id, String first, String last, String email, String phone, long branchId) {
    }

    private record Referee(Person person, long subscriptionId, long invoiceId, Instant activatedAt) {
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = jdbc.queryForObject("select (select count(*) from referrals) + (select count(*) from campaigns)",
                Long.class);
        List<Person> referrers = referrers();
        if (existing == null || existing > 0 || referrers.isEmpty()) {
            log.info("Growth demo seeding skipped (growth data present or no members)");
            return;
        }
        Instant now = Instant.now(clock);
        Set<Long> used = new HashSet<>();
        referrers.forEach(r -> used.add(r.id()));
        List<Referee> pool = referees(used);
        int referrals = seedReferrals(referrers, pool, now);
        int recipients = seedCampaigns(now);
        log.info("Growth demo data seeded: {} referrals, 2 campaigns, {} campaign recipients", referrals, recipients);
    }

    // ------------------------------------------------------------------ referrals

    private int seedReferrals(List<Person> referrers, List<Referee> pool, Instant now) {
        GrowthProperties.Referral cfg = props.referral();
        int count = 0;
        int next = 0;
        // per referrer: AVAILABLE, AVAILABLE, HELD, REVERSED (first referrer gets the full mix)
        String[][] plans = {{"AVAILABLE", "AVAILABLE", "HELD", "REVERSED", "NOT_NEW"}, {"AVAILABLE", "HELD"}, {"AVAILABLE"}};
        for (int i = 0; i < referrers.size(); i++) {
            Person referrer = referrers.get(i);
            String code = code(referrer);
            for (String outcome : plans[Math.min(i, plans.length - 1)]) {
                if (next >= pool.size()) {
                    break;
                }
                Referee referee = pool.get(next++);
                seedReferral(referrer, code, referee, outcome, now, cfg);
                count++;
            }
        }
        // pending referrals from website enquiries (open leads that came in through a shared link)
        List<Map<String, Object>> leads = jdbc.queryForList("""
                select id, branch_id, first_name, last_name, email, phone from leads
                where status in ('NEW', 'CONTACTED', 'TOUR_SCHEDULED') and member_id is null
                order by created_at desc limit 4
                """);
        for (int i = 0; i < leads.size(); i++) {
            Map<String, Object> l = leads.get(i);
            Person referrer = referrers.get(i % referrers.size());
            long leadId = ((Number) l.get("id")).longValue();
            jdbc.update("update leads set source = 'REFERRAL' where id = ?", leadId);
            Instant created = now.minus(DAY.multipliedBy(1 + random.nextInt(10)));
            jdbc.update("""
                    insert into referrals (referrer_member_id, code, referee_lead_id, referee_first_name, referee_last_name,
                                           referee_email, referee_phone, branch_id, channel, status, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'LINK', 'PENDING', ?, ?)
                    """, referrer.id(), code(referrer), leadId, l.get("first_name"), l.get("last_name"), l.get("email"),
                    l.get("phone"), l.get("branch_id"), ts(created), ts(created));
            count++;
        }
        return count;
    }

    private void seedReferral(Person referrer, String code, Referee referee, String outcome, Instant now,
                              GrowthProperties.Referral cfg) {
        Person p = referee.person();
        Instant created = referee.activatedAt().minus(DAY.multipliedBy(2 + random.nextInt(5)));
        if ("NOT_NEW".equals(outcome)) {
            created = referee.activatedAt().plus(DAY.multipliedBy(30));
            jdbc.update("""
                    insert into referrals (referrer_member_id, code, referee_member_id, referee_first_name, referee_last_name,
                                           referee_email, referee_phone, branch_id, channel, status, rejection_reason,
                                           created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'DESK', 'REJECTED', 'NOT_NEW', ?, ?)
                    """, referrer.id(), code, p.id(), p.first(), p.last(), p.email(), p.phone(), p.branchId(),
                    ts(min(created, now)), ts(min(created, now)));
            return;
        }
        Instant converted = "HELD".equals(outcome) ? now.minus(DAY.multipliedBy(3)) : referee.activatedAt();
        if (!"HELD".equals(outcome) && converted.isAfter(now.minus(DAY.multipliedBy(cfg.holdDays() + 2L)))) {
            converted = now.minus(DAY.multipliedBy(cfg.holdDays() + 5L));
        }
        created = min(created, converted.minus(DAY));
        Instant hold = converted.plus(DAY.multipliedBy(cfg.holdDays()));
        boolean reversed = "REVERSED".equals(outcome);
        Long referralId = jdbc.queryForObject("""
                insert into referrals (referrer_member_id, code, referee_member_id, referee_first_name, referee_last_name,
                                       referee_email, referee_phone, branch_id, channel, status, qualifying_subscription_id,
                                       qualifying_invoice_id, converted_at, hold_until, reversed_at, reversal_reason,
                                       created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, referrer.id(), code, p.id(), p.first(), p.last(), p.email(), p.phone(), p.branchId(),
                random.nextBoolean() ? "LINK" : "DESK", reversed ? "REVERSED" : "CONVERTED", referee.subscriptionId(),
                referee.invoiceId(), ts(converted), ts(hold), reversed ? ts(converted.plus(DAY.multipliedBy(4))) : null,
                reversed ? "Qualifying payment refunded" : null, ts(created), ts(converted));

        String status = switch (outcome) {
            case "AVAILABLE" -> "AVAILABLE";
            case "REVERSED" -> "REVERSED";
            default -> "HELD";
        };
        Long creditEntry = null;
        Long discountId = null;
        if ("AVAILABLE".equals(status)) {
            memberRepository.findByIdForUpdate(Math.min(referrer.id(), p.id()));
            memberRepository.findByIdForUpdate(Math.max(referrer.id(), p.id()));
            creditEntry = storeCredit.post(referrer.id(), CreditEntryType.ISSUED, cfg.referrerCredit(),
                    "Referral reward: " + p.first() + " joined (referral #" + referralId + ")", null, null).getId();
            discountId = offers.issue(p.id(), "REF", cfg.refereeDiscountType(), cfg.refereeRewardValue(),
                    cfg.refereeCodeValidDays(), OfferPurpose.REFERRAL_WELCOME,
                    "Referral welcome reward (demo, referral #" + referralId + ")").discountId();
        }
        insertReward(referralId, referrer.id(), "REFERRER", "STORE_CREDIT", cfg.referrerCredit(), null, status, hold,
                creditEntry, null);
        insertReward(referralId, p.id(), "REFEREE", "DISCOUNT_CODE", cfg.refereeRewardValue(), cfg.refereeDiscountType(),
                status, hold, null, discountId);
    }

    private void insertReward(long referralId, long memberId, String beneficiary, String type, BigDecimal amount,
                              DiscountType discountType, String status, Instant availableAt, Long creditEntry, Long discountId) {
        Instant now = Instant.now(clock);
        jdbc.update("""
                insert into referral_rewards (referral_id, member_id, beneficiary, reward_type, amount, discount_type, status,
                                              available_at, released_at, reversed_at, credit_entry_id, discount_id,
                                              created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, referralId, memberId, beneficiary, type, amount, discountType == null ? null : discountType.name(),
                status, ts(availableAt), "AVAILABLE".equals(status) ? ts(availableAt) : null,
                "REVERSED".equals(status) ? ts(availableAt.minus(DAY.multipliedBy(10))) : null, creditEntry, discountId,
                ts(now), ts(now));
    }

    private List<Person> referrers() {
        List<Person> out = new ArrayList<>(jdbc.query("""
                select m.id, m.first_name, m.last_name, m.email, m.phone, m.home_branch_id from members m
                join users u on u.id = m.user_id
                where m.deleted_at is null and m.status = 'ACTIVE' and u.email like 'member1.%'
                order by m.id limit 1
                """, (rs, n) -> person(rs)));
        out.addAll(jdbc.query("""
                select m.id, m.first_name, m.last_name, m.email, m.phone, m.home_branch_id from members m
                where m.deleted_at is null and m.status = 'ACTIVE' and m.user_id is null
                order by m.id limit 2 offset 7
                """, (rs, n) -> person(rs)));
        return out;
    }

    /** Members with a first paid (non-trial) membership: the referees, newest first. */
    private List<Referee> referees(Set<Long> exclude) {
        return jdbc.query("""
                select m.id, m.first_name, m.last_name, m.email, m.phone, m.home_branch_id, f.sub_id, f.inv_id, f.activated_at
                from members m
                join lateral (
                    select s.id as sub_id, i.id as inv_id, s.activated_at from subscriptions s
                    join invoices i on i.subscription_id = s.id and i.status <> 'VOID'
                    where s.member_id = m.id and s.activated_at is not null and i.paid_at is not null and i.amount_paid > 0
                      and not exists (select 1 from leads l where l.trial_subscription_id = s.id)
                    order by s.activated_at limit 1) f on true
                where m.deleted_at is null and m.status = 'ACTIVE'
                order by f.activated_at desc limit 20
                """, (rs, n) -> new Referee(person(rs), rs.getLong("sub_id"), rs.getLong("inv_id"),
                rs.getTimestamp("activated_at").toInstant()))
                .stream().filter(r -> !exclude.contains(r.person().id())).limit(8).toList();
    }

    private String code(Person p) {
        List<String> existing = jdbc.queryForList("select code from referral_codes where member_id = ?", String.class, p.id());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        for (int attempt = 0; ; attempt++) {
            String code = ReferralRules.generateCode(p.first(), 3 + attempt / 5, random);
            if (jdbc.update("insert into referral_codes (member_id, code, created_at) values (?, ?, ?) on conflict do nothing",
                    p.id(), code, ts(Instant.now(clock))) == 1) {
                return code;
            }
        }
    }

    // ------------------------------------------------------------------ campaigns

    private int seedCampaigns(Instant now) {
        Long branchId = jdbc.queryForList("select id from gym_branches where deleted_at is null and status = 'ACTIVE' "
                + "order by id limit 1", Long.class).stream().findFirst().orElse(null);
        if (branchId == null) {
            return 0;
        }
        GrowthProperties.Campaigns cfg = props.campaigns();
        Instant ran1 = now.minus(DAY.multipliedBy(9));
        long winBack = jdbc.queryForObject("""
                insert into campaigns (name, description, branch_id, segment, segment_days, segment_days_max, title_template,
                                       message_template, offer_enabled, offer_discount_type, offer_value, offer_valid_days,
                                       schedule, status, cooldown_days, attribution_days, last_run_at, created_at, updated_at)
                values ('Win back lapsed members', 'Personal 15% offer for members who left in the last three months', ?,
                        'LAPSED', 7, 90, 'We miss you, {firstName}!',
                        'Hi {firstName}, your {planName} ended on {endDate}. '
                        || 'Come back this month with code {offerCode} for 15% off any membership.',
                        true, 'PERCENTAGE', 15, 21, 'NONE', 'DRAFT', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, branchId, cfg.cooldownDays(), cfg.attributionDays(), ts(ran1),
                ts(ran1.minus(DAY)), ts(ran1));
        LocalDate today = LocalDate.now(clock);
        Instant nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(cfg.sendHour(), 0)
                .atZone(clock.getZone()).toInstant();
        Instant ran2 = now.minus(DAY.multipliedBy(6));
        long expiring = jdbc.queryForObject("""
                insert into campaigns (name, description, branch_id, segment, segment_days, title_template, message_template,
                                       offer_enabled, schedule, schedule_day_of_week, status, cooldown_days, attribution_days,
                                       next_run_at, last_run_at, created_at, updated_at)
                values ('Renewal nudge (no autopay)', 'Weekly reminder for memberships ending within 10 days without autopay',
                        null, 'EXPIRING_NO_AUTOPAY', 10, 'Your {planName} ends on {endDate}',
                        'Hi {firstName}, your {planName} ends on {endDate}. '
                        || 'Renew in the app in a minute, or switch on autopay so you never miss a session.',
                        false, 'WEEKLY', 1, 'ACTIVE', 6, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, cfg.attributionDays(), ts(nextMonday), ts(ran2), ts(ran2.minus(DAY.multipliedBy(30))),
                ts(ran2));

        // past runs: people who bought shortly after the message are the "converted" recipients
        List<Map<String, Object>> buyers = jdbc.queryForList("""
                select i.id as invoice_id, i.member_id, i.amount_paid, i.paid_at, m.home_branch_id
                from invoices i join members m on m.id = i.member_id
                where i.paid_at between ? and ? and i.amount_paid > 0 and i.status <> 'VOID' and m.deleted_at is null
                order by i.paid_at limit 30
                """, ts(ran1.plus(DAY)), ts(now));
        List<Map<String, Object>> others = jdbc.queryForList("""
                select m.id as member_id, m.home_branch_id from members m
                where m.deleted_at is null and m.status = 'ACTIVE'
                  and not exists (select 1 from subscriptions s where s.member_id = m.id and s.status in ('ACTIVE', 'SCHEDULED'))
                order by m.id limit 40
                """);
        int recipients = 0;
        recipients += seedRun(winBack, ran1, buyers.subList(0, Math.min(4, buyers.size())), others.subList(0,
                Math.min(14, others.size())), true, "MANUAL");
        recipients += seedRun(expiring, ran2, buyers.subList(Math.min(4, buyers.size()), Math.min(9, buyers.size())),
                others.subList(Math.min(14, others.size()), Math.min(30, others.size())), false, "SCHEDULED");
        return recipients;
    }

    private int seedRun(long campaignId, Instant sentAt, List<Map<String, Object>> converted,
                        List<Map<String, Object>> notConverted, boolean offer, String trigger) {
        int total = converted.size() + notConverted.size();
        Long runId = jdbc.queryForObject("""
                insert into campaign_runs (campaign_id, trigger_type, status, audience_size, sent, skipped, failed,
                                           started_at, finished_at)
                values (?, ?, 'COMPLETED', ?, ?, 0, 0, ?, ?) returning id
                """, Long.class, campaignId, trigger, total, total, ts(sentAt), ts(sentAt.plusSeconds(4)));
        Set<Long> seen = new HashSet<>();
        int n = 0;
        for (Map<String, Object> r : converted) {
            long memberId = ((Number) r.get("member_id")).longValue();
            if (!seen.add(memberId)) {
                continue;
            }
            insertRecipient(campaignId, runId, memberId, ((Number) r.get("home_branch_id")).longValue(), sentAt, offer,
                    ((Number) r.get("invoice_id")).longValue(), (BigDecimal) r.get("amount_paid"),
                    ((Timestamp) r.get("paid_at")).toInstant());
            n++;
        }
        for (Map<String, Object> r : notConverted) {
            long memberId = ((Number) r.get("member_id")).longValue();
            if (!seen.add(memberId)) {
                continue;
            }
            insertRecipient(campaignId, runId, memberId, ((Number) r.get("home_branch_id")).longValue(), sentAt, offer,
                    null, null, null);
            n++;
        }
        jdbc.update("update campaign_runs set audience_size = ?, sent = ? where id = ?", n, n, runId);
        return n;
    }

    private void insertRecipient(long campaignId, long runId, long memberId, long branchId, Instant sentAt, boolean offer,
                                 Long invoiceId, BigDecimal amount, Instant convertedAt) {
        Long discountId = null;
        String code = null;
        if (offer) {
            var issued = offers.issue(memberId, "WB", DiscountType.PERCENTAGE, BigDecimal.valueOf(15), 21,
                    OfferPurpose.CAMPAIGN_OFFER, "Personal offer from campaign #" + campaignId + " (demo)");
            discountId = issued.discountId();
            code = issued.code();
        }
        jdbc.update("""
                insert into campaign_recipients (campaign_id, run_id, member_id, branch_id, sent_at, discount_id, offer_code,
                                                 attribution_ends_at, converted_at, conversion_invoice_id, conversion_amount)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, campaignId, runId, memberId, branchId, ts(sentAt), discountId, code,
                ts(sentAt.plus(DAY.multipliedBy(props.campaigns().attributionDays()))),
                convertedAt == null ? null : ts(convertedAt), invoiceId, amount);
    }

    // ------------------------------------------------------------------ helpers

    private static Person person(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Person(rs.getLong("id"), rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                rs.getString("phone"), rs.getLong("home_branch_id"));
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
