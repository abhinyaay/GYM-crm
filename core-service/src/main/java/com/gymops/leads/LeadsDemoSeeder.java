package com.gymops.leads;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
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
 * Demo sales pipeline: ~40 leads per branch over the last 90 days with realistic sources, statuses,
 * contact activities and follow-up tasks, a few running free trials (on a FREE-TRIAL-3 plan created
 * here if missing) and conversions linked to members who actually joined. Runs after the core demo
 * seeder, only with {@code app.bootstrap.demo-data=true}, and only while the leads table is empty.
 */
@Slf4j
@Order(4)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class LeadsDemoSeeder implements ApplicationRunner {

    static final String TRIAL_PLAN_CODE = "FREE-TRIAL-3";
    private static final int LEADS_PER_BRANCH = 40;
    private static final String[] FIRST = {"Aditi", "Rohit", "Kavya", "Siddharth", "Ishita", "Varun", "Pooja", "Manish",
            "Shreya", "Harsh", "Nisha", "Yash", "Riya", "Akash", "Tanvi", "Dev", "Simran", "Kunal", "Anjali", "Aman"};
    private static final String[] LAST = {"Agarwal", "Bhat", "Chauhan", "Desai", "Garg", "Hegde", "Jain", "Khanna",
            "Lal", "Mishra", "Naidu", "Ojha", "Pandey", "Saxena", "Thakur", "Vyas"};
    private static final String[] SOURCES = {"WALK_IN", "WALK_IN", "WEBSITE", "WEBSITE", "WEBSITE", "INSTAGRAM",
            "INSTAGRAM", "FACEBOOK", "GOOGLE", "GOOGLE", "REFERRAL", "REFERRAL", "PHONE", "CORPORATE", "OTHER"};
    private static final String[] INTERESTS = {"MEMBERSHIP", "MEMBERSHIP", "MEMBERSHIP", "PERSONAL_TRAINING", "CLASSES",
            "CORPORATE"};
    private static final String[] LOST_REASONS = {"PRICE", "PRICE", "LOCATION", "TIMING", "COMPETITOR", "NOT_INTERESTED",
            "UNREACHABLE", "UNREACHABLE", "OTHER"};
    private static final String[] CONTACT_TYPES = {"CALL", "CALL", "WHATSAPP", "WHATSAPP", "EMAIL", "SMS"};

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Random random = new Random(7);

    private record Prospect(String first, String last, String email, String phone) {
    }

    private record JoinedMember(long id, String first, String last, String email, String phone, Instant joinedAt) {
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long leads = jdbc.queryForObject("select count(*) from leads", Long.class);
        List<Long> branches = jdbc.queryForList(
                "select id from gym_branches where deleted_at is null and status = 'ACTIVE' order by id", Long.class);
        if (leads == null || leads > 0 || branches.isEmpty()) {
            log.info("Leads demo seeding skipped (leads present or no branches)");
            return;
        }
        long trialPlanId = trialPlan();
        Instant now = Instant.now(clock);
        int total = 0;
        for (Long branchId : branches) {
            total += seedBranch(branchId, trialPlanId, now);
        }
        log.info("Leads demo data seeded: {} leads across {} branches", total, branches.size());
    }

    private int seedBranch(long branchId, long trialPlanId, Instant now) {
        List<Long> owners = jdbc.queryForList("""
                select distinct a.user_id from staff_branch_assignments a
                join user_roles ur on ur.user_id = a.user_id join roles r on r.id = ur.role_id
                join users u on u.id = a.user_id
                where a.branch_id = ? and r.name in ('MANAGER', 'RECEPTIONIST') and u.deleted_at is null
                order by a.user_id
                """, Long.class, branchId);
        List<JoinedMember> joined = jdbc.query("""
                select m.id, m.first_name, m.last_name, m.email, m.phone, min(s.activated_at) as joined_at
                from members m join subscriptions s on s.member_id = m.id
                where m.home_branch_id = ? and m.deleted_at is null and s.activated_at is not null
                group by m.id having min(s.activated_at) >= ?
                order by min(s.activated_at) desc limit 10
                """, (rs, i) -> new JoinedMember(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant()), branchId, ts(now.minus(Duration.ofDays(85))));

        int count = 0;
        // conversions linked to members who really joined in the window
        for (int c = 0; c < Math.min(8, joined.size()); c++) {
            JoinedMember m = joined.get(c);
            Instant created = m.joinedAt().minus(Duration.ofHours(24L * (5 + random.nextInt(6)) + random.nextInt(8)));
            Long trialSubId = null;
            LocalDate trialEnd = null;
            if (c % 2 == 0) {
                // half of the conversions came through a free trial that ended just before they joined
                trialEnd = LocalDate.ofInstant(m.joinedAt(), clock.getZone()).minusDays(1);
                trialSubId = insertTrialSubscription(m.id(), trialPlanId, branchId, trialEnd.minusDays(2), trialEnd,
                        created.plus(Duration.ofDays(1)), "EXPIRED");
            }
            long id = insertLead(branchId, new Prospect(m.first(), m.last(), m.email(), Lead.normalizePhone(m.phone())),
                    "CONVERTED", pick(owners), created, m.joinedAt(), null, m.id(), trialSubId, trialEnd);
            firstContact(id, created, pick(owners), 0.8);
            activity(id, "TOUR", "Showed the gym floor and steam room", created.plus(Duration.ofDays(1)), pick(owners));
            activity(id, "STATUS_CHANGE", "TOUR_SCHEDULED → CONVERTED: purchased a membership", m.joinedAt(), null);
            count++;
        }
        int remaining = LEADS_PER_BRANCH - count;
        for (int i = 0; i < remaining; i++) {
            Prospect p = prospect(branchId, i);
            String status = statusFor(i, remaining);
            long ageHours = switch (status) {
                case "NEW" -> random.nextInt(60);
                case "CONTACTED" -> 24 + random.nextInt(24 * 20);
                case "TOUR_SCHEDULED" -> 48 + random.nextInt(24 * 14);
                case "TRIAL_ACTIVE" -> 24 * 2 + random.nextInt(24 * 3);
                default -> 24 * 10 + random.nextInt(24 * 78);
            };
            Instant created = now.minus(Duration.ofHours(ageHours)).minus(Duration.ofMinutes(random.nextInt(60)));
            Long owner = "NEW".equals(status) && random.nextBoolean() ? null : pick(owners);
            String lostReason = "LOST".equals(status) ? LOST_REASONS[random.nextInt(LOST_REASONS.length)] : null;
            Long memberId = null;
            Long trialSubId = null;
            LocalDate trialEnds = null;
            if ("TRIAL_ACTIVE".equals(status)) {
                LocalDate start = LocalDate.now(clock).minusDays(i % 3);
                trialEnds = start.plusDays(2);
                memberId = insertMember(branchId, p, created.plus(Duration.ofHours(30)));
                trialSubId = insertTrialSubscription(memberId, trialPlanId, branchId, start, trialEnds,
                        created.plus(Duration.ofHours(30)), "ACTIVE");
            }
            long id = insertLead(branchId, p, status, owner, created, null, lostReason, memberId, trialSubId, trialEnds);
            activity(id, "NOTE", "Enquiry captured (" + sourceOf(id) + ")", created, null);
            if (!"NEW".equals(status)) {
                firstContact(id, created, owner, 0.7);
            }
            switch (status) {
                case "TOUR_SCHEDULED" -> activity(id, "STATUS_CHANGE", "CONTACTED → TOUR_SCHEDULED",
                        created.plus(Duration.ofHours(30)), owner);
                case "TRIAL_ACTIVE" -> activity(id, "STATUS_CHANGE", "Trial started: Free 3-Day Trial",
                        created.plus(Duration.ofHours(30)), owner);
                case "LOST" -> activity(id, "STATUS_CHANGE", "CONTACTED → LOST (" + lostReason + ")",
                        created.plus(Duration.ofDays(5)), owner);
                default -> {
                }
            }
            if (!"LOST".equals(status)) {
                followUps(id, status, owner, now);
            }
            count++;
        }
        return count;
    }

    /** Pipeline mix: older leads have resolved (lost), recent ones are still being worked. */
    private static String statusFor(int i, int total) {
        double f = (double) i / total;
        if (f < 0.25) {
            return "NEW";
        }
        if (f < 0.45) {
            return "CONTACTED";
        }
        if (f < 0.58) {
            return "TOUR_SCHEDULED";
        }
        if (f < 0.68) {
            return "TRIAL_ACTIVE";
        }
        return "LOST";
    }

    private void firstContact(long leadId, Instant created, Long owner, double within24hShare) {
        long hours = random.nextDouble() < within24hShare ? 1 + random.nextInt(20) : 26 + random.nextInt(60);
        String type = CONTACT_TYPES[random.nextInt(CONTACT_TYPES.length)];
        activity(leadId, type, switch (type) {
            case "CALL" -> "Called: interested, asked about pricing and timings";
            case "WHATSAPP" -> "Sent plan brochure on WhatsApp";
            case "EMAIL" -> "Emailed membership options";
            default -> "SMS sent with branch location";
        }, created.plus(Duration.ofHours(hours)), owner);
    }

    private void followUps(long leadId, String status, Long owner, Instant now) {
        int bucket = random.nextInt(4);   // 0 overdue, 1 due today, 2 upcoming, 3 one completed + one upcoming
        Instant due = switch (bucket) {
            case 0 -> now.minus(Duration.ofHours(6 + random.nextInt(40)));
            case 1 -> now.plus(Duration.ofMinutes(30 + random.nextInt(240)));
            default -> now.plus(Duration.ofHours(30 + random.nextInt(96)));
        };
        if (bucket == 3) {
            jdbc.update("""
                    insert into lead_activities (lead_id, type, body, due_at, completed_at, created_by, created_at)
                    values (?, 'FOLLOW_UP', 'Check if they visited', ?, ?, ?, ?)
                    """, leadId, ts(now.minus(Duration.ofDays(2))), ts(now.minus(Duration.ofDays(2))), owner,
                    ts(now.minus(Duration.ofDays(3))));
        }
        String body = switch (status) {
            case "NEW" -> "First call — introduce the free trial";
            case "TOUR_SCHEDULED" -> "Confirm the tour slot";
            case "TRIAL_ACTIVE" -> "Check in on how the trial is going";
            default -> "Follow up on pricing questions";
        };
        jdbc.update("""
                insert into lead_activities (lead_id, type, body, due_at, created_by, created_at)
                values (?, 'FOLLOW_UP', ?, ?, ?, ?)
                """, leadId, body, ts(due), owner, ts(now.minus(Duration.ofHours(12))));
        jdbc.update("update leads set next_follow_up_at = ? where id = ?", ts(due), leadId);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private long insertLead(long branchId, Prospect p, String status, Long owner, Instant created, Instant convertedAt,
                            String lostReason, Long memberId, Long trialSubId, LocalDate trialEnds) {
        String source = SOURCES[random.nextInt(SOURCES.length)];
        Long id = jdbc.queryForObject("""
                insert into leads (branch_id, first_name, last_name, email, phone, source, interest, status,
                                   owner_user_id, trial_subscription_id, trial_ends_on, member_id, converted_at,
                                   lost_reason, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, branchId, p.first(), p.last(), p.email(), p.phone(), source,
                INTERESTS[random.nextInt(INTERESTS.length)], status, owner, trialSubId,
                trialEnds == null ? null : Date.valueOf(trialEnds), memberId, convertedAt == null ? null : ts(convertedAt),
                lostReason, ts(created), ts(convertedAt != null ? convertedAt : created));
        if (id == null) {
            throw new IllegalStateException("Insert returned no id");
        }
        return id;
    }

    private String sourceOf(long leadId) {
        return jdbc.queryForObject("select source from leads where id = ?", String.class, leadId);
    }

    private void activity(long leadId, String type, String body, Instant at, Long by) {
        jdbc.update("insert into lead_activities (lead_id, type, body, created_by, created_at) values (?, ?, ?, ?, ?)",
                leadId, type, body, by, ts(at));
    }

    private long insertMember(long branchId, Prospect p, Instant at) {
        Long id = jdbc.queryForObject("""
                insert into members (member_code, home_branch_id, first_name, last_name, email, phone, status,
                                     registered_at, notes, created_at)
                values ('M' || lpad(nextval('member_code_seq')::text, 6, '0'), ?, ?, ?, ?, ?, 'ACTIVE', ?,
                        'Registered from a lead (free trial)', ?) returning id
                """, Long.class, branchId, p.first(), p.last(), p.email(), p.phone(), ts(at), ts(at));
        if (id == null) {
            throw new IllegalStateException("Insert returned no id");
        }
        return id;
    }

    /** EXPIRED trials sit outside the no-overlap exclusion constraint, so history before a join is safe. */
    private long insertTrialSubscription(long memberId, long planId, long branchId, LocalDate start, LocalDate end,
                                         Instant at, String status) {
        Long id = jdbc.queryForObject("""
                insert into subscriptions (member_id, plan_id, branch_id, status, change_type, start_date, end_date,
                                           price, activated_at, created_at)
                values (?, ?, ?, ?, 'NEW', ?, ?, 0, ?, ?) returning id
                """, Long.class, memberId, planId, branchId, status, Date.valueOf(start), Date.valueOf(end), ts(at), ts(at));
        if (id == null) {
            throw new IllegalStateException("Insert returned no id");
        }
        return id;
    }

    private long trialPlan() {
        List<Long> existing = jdbc.queryForList("select id from membership_plans where code = ?", Long.class,
                TRIAL_PLAN_CODE);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Long id = jdbc.queryForObject("""
                insert into membership_plans (code, name, description, billing_period, duration_days, price, currency,
                    joining_fee, all_branch_access, freeze_allowed, cancellation_policy, grace_period_days, status)
                values (?, 'Free 3-Day Trial', 'Three days of full gym access for prospects', 'CUSTOM', 3, 0, 'INR', 0,
                        false, false, 'NON_REFUNDABLE', 0, 'ACTIVE') returning id
                """, Long.class, TRIAL_PLAN_CODE);
        if (id == null) {
            throw new IllegalStateException("Insert returned no id");
        }
        jdbc.update("insert into plan_benefits (plan_id, position, benefit) values (?, 0, 'Full gym floor access'), "
                + "(?, 1, 'One fitness assessment')", id, id);
        return id;
    }

    private Prospect prospect(long branchId, int i) {
        String first = FIRST[random.nextInt(FIRST.length)];
        String last = LAST[random.nextInt(LAST.length)];
        String email = (first + "." + last + "." + branchId + "-" + i).toLowerCase() + "@example.com";
        String phone = "+919" + (100_000_000 + random.nextInt(899_999_999));
        return new Prospect(first, last, email, phone);
    }

    private Long pick(List<Long> owners) {
        return owners.isEmpty() ? null : owners.get(random.nextInt(owners.size()));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
