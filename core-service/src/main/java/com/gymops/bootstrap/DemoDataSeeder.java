package com.gymops.bootstrap;

import com.gymops.config.AppProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a realistic, deterministic demo dataset (3 branches, staff, trainers, ~360 members and
 * 12 months of subscriptions, payments, refunds, visits, sessions and complaints) so dashboards and
 * the analytics service have meaningful history. Enabled only with {@code app.bootstrap.demo-data=true}
 * (never in production) and skipped when any branch already exists.
 *
 * <p>History is written with plain JDBC batch inserts for speed; it respects every database
 * constraint (non-overlapping subscriptions, one open visit per member, no double-booked sessions).
 */
@Slf4j
@Order(2)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final String[] FIRST = {"Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Ayaan",
            "Krishna", "Ishaan", "Ananya", "Diya", "Aadhya", "Saanvi", "Pari", "Anika", "Navya", "Myra", "Sara", "Ira",
            "Rohan", "Kabir", "Meera", "Tara", "Nikhil", "Priya", "Neha", "Rahul", "Sneha", "Karan"};
    private static final String[] LAST = {"Sharma", "Verma", "Iyer", "Reddy", "Nair", "Gupta", "Mehta", "Kapoor",
            "Joshi", "Rao", "Menon", "Patel", "Singh", "Das", "Bose", "Kulkarni", "Chopra", "Malhotra", "Pillai", "Shetty"};

    private record Branch(long id, String code, ZoneId zone, int capacity) {
    }

    private record Plan(long id, String code, int months, int days, BigDecimal price, BigDecimal joiningFee,
                        int graceDays, boolean freezeAllowed) {
        LocalDate end(LocalDate start) {
            return months > 0 ? start.plusMonths(months).minusDays(1) : start.plusDays(days - 1L);
        }
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;
    private final Clock clock;

    private final Random random = new Random(42);
    private final List<Object[]> attendanceRows = new ArrayList<>();
    private BigDecimal taxRate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long branches = jdbc.queryForObject("select count(*) from gym_branches", Long.class);
        if (branches != null && branches > 0) {
            log.info("Demo data seeding skipped: data already present");
            return;
        }
        String demoPassword = props.bootstrap().demoPassword();
        if (demoPassword == null || demoPassword.length() < 10) {
            log.warn("app.bootstrap.demo-data=true but DEMO_USER_PASSWORD (min 10 chars) is not set; skipping demo data");
            return;
        }
        long started = System.currentTimeMillis();
        taxRate = props.billing().taxRate();
        String passwordHash = passwordEncoder.encode(demoPassword);
        LocalDate today = LocalDate.now(clock);

        List<Branch> branchList = List.of(
                branch("BLR-KOR", "GymOps Koramangala", "80 Feet Road, Koramangala", "Bengaluru", "Karnataka", 140),
                branch("MUM-AND", "GymOps Andheri West", "Link Road, Andheri West", "Mumbai", "Maharashtra", 160),
                branch("DEL-HKV", "GymOps Hauz Khas", "Hauz Khas Village", "New Delhi", "Delhi", 100));
        List<Plan> plans = plans();
        jdbc.update("""
                insert into discounts (code, description, discount_type, value, valid_from, valid_until, max_redemptions)
                values ('WELCOME10', 'New member welcome offer', 'PERCENTAGE', 10, ?, ?, 500),
                       ('FESTIVE500', 'Festive season flat discount', 'FIXED_AMOUNT', 500, ?, ?, null)
                """, Date.valueOf(today.minusMonths(6)), Date.valueOf(today.plusMonths(6)),
                Date.valueOf(today.minusDays(20)), Date.valueOf(today.plusDays(40)));

        long gymAdmin = user("admin@gymops.dev", "Gym Administrator", passwordHash, "GYM_ADMIN");
        log.info("Demo GYM_ADMIN user id={}", gymAdmin);
        int b = 0;
        int memberCount = 0;
        for (Branch branch : branchList) {
            b++;
            String tag = branch.code().substring(0, 3).toLowerCase();
            long manager = user("manager." + tag + "@gymops.dev", "Manager " + branch.code(), passwordHash, "MANAGER");
            long receptionist = user("frontdesk." + tag + "@gymops.dev", "Front Desk " + branch.code(), passwordHash, "RECEPTIONIST");
            assign(manager, branch.id());
            assign(receptionist, branch.id());
            List<Long> trainers = new ArrayList<>();
            for (int t = 1; t <= 2; t++) {
                long trainerUser = user("trainer" + t + "." + tag + "@gymops.dev", FIRST[(b * 3 + t) % FIRST.length] + " "
                        + LAST[(b * 5 + t) % LAST.length] + " (Coach)", passwordHash, "TRAINER");
                assign(trainerUser, branch.id());
                trainers.add(trainer(trainerUser, branch, "EMP-" + branch.code() + "-" + t, t));
            }
            long template = workoutTemplate(trainers.get(0), branch.code());
            int members = 120;
            for (int i = 0; i < members; i++) {
                memberCount++;
                boolean withLogin = i < 3;
                seedMember(branch, plans, trainers, template, today, passwordHash, withLogin, tag, i, receptionist);
            }
            complaints(branch, manager, receptionist, today);
        }
        jdbc.batchUpdate("""
                insert into attendance (member_id, branch_id, subscription_id, check_in_time, check_out_time,
                                        check_in_method, checked_in_by, auto_checked_out, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attendanceRows);
        log.info("Demo data seeded: {} branches, {} members, {} visits in {} ms (login password = DEMO_USER_PASSWORD)",
                branchList.size(), memberCount, attendanceRows.size(), System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------ master data

    private Branch branch(String code, String name, String address, String city, String state, int capacity) {
        long id = insertReturningId("""
                insert into gym_branches (code, name, phone, email, address_line1, city, state, postal_code, country,
                                          timezone, capacity)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'IN', 'Asia/Kolkata', ?) returning id
                """, code, name, "+91 80000 " + (10000 + capacity), code.toLowerCase() + "@gymops.dev", address, city,
                state, "5600" + capacity, capacity);
        for (int day = 1; day <= 7; day++) {
            if (day == 7) {
                jdbc.update("insert into branch_operating_hours (branch_id, day_of_week, open_time, close_time, closed) "
                        + "values (?, 7, '07:00', '13:00', false)", id);
            } else {
                jdbc.update("insert into branch_operating_hours (branch_id, day_of_week, open_time, close_time, closed) "
                        + "values (?, ?, '05:30', '22:30', false)", id, day);
            }
        }
        for (String facility : List.of("Free weights", "Cardio zone", "Functional training", "Steam room", "Locker rooms")) {
            jdbc.update("insert into branch_facilities (branch_id, name, available) values (?, ?, true)", id, facility);
        }
        return new Branch(id, code, ZoneId.of("Asia/Kolkata"), capacity);
    }

    private List<Plan> plans() {
        List<Plan> list = new ArrayList<>();
        list.add(plan("MONTHLY-STD", "Monthly Standard", "MONTHLY", 1, null, "2499", "1000", false, null, null, null,
                true, 7, 15, 1, "PRORATED", 7, "500", 7, "Gym floor access", "Locker"));
        list.add(plan("QUARTERLY-STD", "Quarterly Standard", "QUARTERLY", 3, null, "6999", "1000", false, null, null, null,
                true, 7, 30, 1, "PRORATED", 7, "500", 7, "Gym floor access", "1 fitness assessment"));
        list.add(plan("HALFYEAR-PLUS", "Half-Yearly Plus", "HALF_YEARLY", 6, null, "12999", "0", true, null, null, null,
                true, 7, 45, 2, "COOLING_OFF_FULL_REFUND", 7, "1000", 10, "All-branch access", "Steam room"));
        list.add(plan("ANNUAL-PREMIUM", "Annual Premium", "ANNUAL", 12, null, "22999", "0", true, null, null, null,
                true, 7, 60, 3, "COOLING_OFF_FULL_REFUND", 14, "1500", 15, "All-branch access", "4 PT sessions"));
        list.add(plan("OFFPEAK-MONTHLY", "Off-Peak Monthly", "MONTHLY", 1, null, "1499", "500", false,
                LocalTime.of(10, 0), LocalTime.of(16, 0), null, false, 0, 0, 0, "NON_REFUNDABLE", 0, "0", 3,
                "Weekday off-peak access", "Gym floor access"));
        list.add(plan("TRIAL-15", "15-Day Trial", "CUSTOM", 0, 15, "999", "0", false, null, null, 12,
                false, 0, 0, 0, "NON_REFUNDABLE", 0, "0", 0, "12 visits in 15 days"));
        return list;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Plan plan(String code, String name, String period, int months, Integer days, String price, String joining,
                      boolean allBranches, LocalTime accessStart, LocalTime accessEnd, Integer maxVisits,
                      boolean freeze, int minFreeze, int maxFreeze, int freezeCount, String policy, int coolingOff,
                      String cancelFee, int grace, String... benefits) {
        long id = insertReturningId("""
                insert into membership_plans (code, name, description, billing_period, duration_months, duration_days,
                    price, currency, joining_fee, all_branch_access, access_start_time, access_end_time,
                    max_visits_per_month, freeze_allowed, min_freeze_days, max_freeze_days, max_freeze_count,
                    cancellation_policy, cooling_off_days, cancellation_fee, grace_period_days, status)
                values (?, ?, ?, ?, ?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE') returning id
                """, code, name, name + " membership", period, months > 0 ? months : null, days, new BigDecimal(price),
                new BigDecimal(joining), allBranches, accessStart == null ? null : Time.valueOf(accessStart),
                accessEnd == null ? null : Time.valueOf(accessEnd), maxVisits, freeze, minFreeze, maxFreeze, freezeCount,
                policy, coolingOff, new BigDecimal(cancelFee), grace);
        for (int i = 0; i < benefits.length; i++) {
            jdbc.update("insert into plan_benefits (plan_id, position, benefit) values (?, ?, ?)", id, i, benefits[i]);
        }
        return new Plan(id, code, months, days == null ? 0 : days, new BigDecimal(price), new BigDecimal(joining), grace, freeze);
    }

    private long user(String email, String name, String hash, String role) {
        long id = insertReturningId("insert into users (email, password_hash, full_name, status) values (?, ?, ?, 'ACTIVE') "
                + "returning id", email, hash, name);
        jdbc.update("insert into user_roles (user_id, role_id) select ?, id from roles where name = ?", id, role);
        return id;
    }

    private void assign(long userId, long branchId) {
        jdbc.update("insert into staff_branch_assignments (user_id, branch_id) values (?, ?)", userId, branchId);
    }

    private long trainer(long userId, Branch branch, String code, int n) {
        long id = insertReturningId("""
                insert into trainers (user_id, home_branch_id, employee_code, bio, certifications, years_of_experience,
                                      hourly_rate, max_active_clients)
                values (?, ?, ?, 'Certified strength & conditioning coach', 'ACE-CPT', ?, ?, 25) returning id
                """, userId, branch.id(), code, 3 + n * 2, new BigDecimal(800 + n * 200));
        jdbc.update("insert into trainer_specializations (trainer_id, specialization) values (?, ?), (?, ?)",
                id, n == 1 ? "STRENGTH" : "WEIGHT_LOSS", id, n == 1 ? "POWERLIFTING" : "HIIT");
        for (int day = 1; day <= 6; day++) {
            jdbc.update("insert into trainer_availability (trainer_id, branch_id, day_of_week, start_time, end_time) "
                    + "values (?, ?, ?, '06:00', '12:00'), (?, ?, ?, '16:00', '21:00')",
                    id, branch.id(), day, id, branch.id(), day);
        }
        return id;
    }

    private long workoutTemplate(long trainerId, String branchCode) {
        long id = insertReturningId("""
                insert into workout_plans (name, description, goal, difficulty, duration_weeks, template, trainer_id, status)
                values (?, 'Three-day full-body programme', 'STRENGTH', 'BEGINNER', 8, true, ?, 'ACTIVE') returning id
                """, "Foundations Strength (" + branchCode + ")", trainerId);
        insertPlanExercises(id);
        return id;
    }

    private void insertPlanExercises(long planId) {
        Object[][] rows = {
                {1, 1, "Barbell Back Squat", 4, 8}, {1, 2, "Barbell Bench Press", 4, 8}, {1, 3, "Seated Cable Row", 3, 12},
                {3, 1, "Romanian Deadlift", 4, 10}, {3, 2, "Overhead Press", 3, 10}, {3, 3, "Lat Pulldown", 3, 12},
                {5, 1, "Leg Press", 3, 12}, {5, 2, "Incline Dumbbell Press", 3, 10}, {5, 3, "Plank", 3, null}};
        for (Object[] r : rows) {
            jdbc.update("""
                    insert into workout_plan_exercises (workout_plan_id, exercise_id, day_number, position, sets, reps,
                                                        duration_seconds, rest_seconds)
                    select ?, id, ?, ?, ?, ?, ?, 90 from exercises where name = ?
                    """, planId, r[0], r[1], r[3], r[4], r[4] == null ? 60 : null, r[2]);
        }
    }

    // ------------------------------------------------------------------ members & history

    private void seedMember(Branch branch, List<Plan> plans, List<Long> trainers, long template, LocalDate today,
                            String hash, boolean withLogin, String tag, int index, long receptionist) {
        String first = FIRST[random.nextInt(FIRST.length)];
        String last = LAST[random.nextInt(LAST.length)];
        String email = withLogin ? "member" + (index + 1) + "." + tag + "@gymops.dev"
                : (first + "." + last + "." + tag + index).toLowerCase() + "@example.com";
        Long userId = withLogin ? user(email, first + " " + last, hash, "MEMBER") : null;
        LocalDate registered = today.minusDays(random.nextInt(365));
        ZonedDateTime registeredAt = registered.atTime(9 + random.nextInt(10), random.nextInt(60)).atZone(branch.zone());
        long memberId = insertReturningId("""
                insert into members (member_code, user_id, home_branch_id, first_name, last_name, email, phone,
                                     date_of_birth, gender, city, status, registered_at, created_at)
                values ('M' || lpad(nextval('member_code_seq')::text, 6, '0'), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                returning id
                """, userId, branch.id(), first, last, email, "+91 9" + (100000000 + random.nextInt(899999999)),
                Date.valueOf(today.minusYears(18 + random.nextInt(35)).minusDays(random.nextInt(365))),
                random.nextBoolean() ? "MALE" : "FEMALE", branch.code().substring(0, 3), ts(registeredAt.toInstant()),
                ts(registeredAt.toInstant()));
        jdbc.update("insert into emergency_contacts (member_id, name, relationship, phone, is_primary) values (?, ?, ?, ?, true)",
                memberId, FIRST[random.nextInt(FIRST.length)] + " " + last, random.nextBoolean() ? "SPOUSE" : "PARENT",
                "+91 9" + (100000000 + random.nextInt(899999999)));

        double engagement = 0.08 + random.nextDouble() * 0.55;       // probability of visiting on a given day
        double renewProbability = 0.55 + random.nextDouble() * 0.4;
        Plan plan = pickPlan(plans);
        LocalDate start = registered;
        Long previousId = null;
        boolean first0 = true;
        Long currentSubscription = null;
        while (!start.isAfter(today)) {
            LocalDate end = plan.end(start);
            String status;
            if (end.isBefore(today)) {
                status = today.isAfter(end.plusDays(plan.graceDays())) ? "EXPIRED" : "GRACE";
            } else {
                status = "ACTIVE";
            }
            boolean renews = random.nextDouble() < renewProbability;
            if ("GRACE".equals(status) && renews) {
                status = "EXPIRED"; // renewed -> the renewal below continues the membership
            }
            if ("GRACE".equals(status) || "ACTIVE".equals(status) || !renews || end.plusDays(1).isAfter(today)) {
                // last subscription of the chain
                long subId = subscription(memberId, plan, branch, previousId, first0 ? "NEW" : "RENEWAL", start, end,
                        status, registeredAt, receptionist, first0);
                visits(memberId, branch, subId, start, end.isAfter(today) ? today : end, engagement, today, receptionist);
                currentSubscription = "ACTIVE".equals(status) ? subId : null;
                // some active members already renewed early (scheduled), some have unpaid renewals
                if ("ACTIVE".equals(status) && end.isBefore(today.plusDays(10)) && random.nextDouble() < 0.4) {
                    long next = subscription(memberId, plan, branch, subId, "RENEWAL", end.plusDays(1), plan.end(end.plusDays(1)),
                            random.nextBoolean() ? "SCHEDULED" : "PENDING_PAYMENT", registeredAt, receptionist, false);
                    log.debug("queued renewal {}", next);
                }
                break;
            }
            long subId = subscription(memberId, plan, branch, previousId, first0 ? "NEW" : "RENEWAL", start, end, status,
                    registeredAt, receptionist, first0);
            visits(memberId, branch, subId, start, end, engagement, today, receptionist);
            previousId = subId;
            first0 = false;
            start = end.plusDays(1);
            engagement = Math.max(0.03, engagement + (random.nextDouble() - 0.6) * 0.1);   // engagement drifts down
            if (random.nextDouble() < 0.15) {
                plan = pickPlan(plans);
            }
        }
        if (currentSubscription != null && random.nextDouble() < 0.22) {
            trainerRelationship(memberId, trainers.get(random.nextInt(trainers.size())), branch, template, today);
        }
    }

    private Plan pickPlan(List<Plan> plans) {
        double r = random.nextDouble();
        if (r < 0.40) {
            return plans.get(0);
        } else if (r < 0.62) {
            return plans.get(1);
        } else if (r < 0.74) {
            return plans.get(2);
        } else if (r < 0.84) {
            return plans.get(3);
        } else if (r < 0.95) {
            return plans.get(4);
        }
        return plans.get(5);
    }

    private long subscription(long memberId, Plan plan, Branch branch, Long previousId, String changeType, LocalDate start,
                              LocalDate end, String status, ZonedDateTime registeredAt, long receptionist, boolean first) {
        boolean paid = !"PENDING_PAYMENT".equals(status);
        Instant activatedAt = paid ? start.atTime(8 + random.nextInt(11), random.nextInt(60)).atZone(branch.zone()).toInstant()
                : null;
        if (activatedAt != null && activatedAt.isBefore(registeredAt.toInstant())) {
            activatedAt = registeredAt.toInstant().plusSeconds(600);
        }
        if ("SCHEDULED".equals(status)) {
            activatedAt = Instant.now(clock).minusSeconds(3600L * (1 + random.nextInt(48)));
        }
        long subId = insertReturningId("""
                insert into subscriptions (member_id, plan_id, branch_id, previous_subscription_id, status, change_type,
                                           start_date, end_date, price, activated_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, memberId, plan.id(), branch.id(), previousId, status, changeType, Date.valueOf(start), Date.valueOf(end),
                plan.price(), activatedAt == null ? null : ts(activatedAt),
                ts(activatedAt != null ? activatedAt : Instant.now(clock).minusSeconds(86_400)));

        BigDecimal subtotal = plan.price().add(first ? plan.joiningFee() : BigDecimal.ZERO);
        BigDecimal discount = first && random.nextDouble() < 0.2
                ? subtotal.multiply(BigDecimal.valueOf(0.10)).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal tax = subtotal.subtract(discount).multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.subtract(discount).add(tax);
        Instant issuedAt = activatedAt != null ? activatedAt.minusSeconds(300) : Instant.now(clock).minusSeconds(86_400);
        long invoiceId = insertReturningId("""
                insert into invoices (invoice_number, member_id, subscription_id, branch_id, status, currency, subtotal,
                                      discount_id, discount_amount, tax_rate, tax_amount, total_amount, amount_paid,
                                      due_date, issued_at, paid_at, created_at)
                values ('INV-' || extract(year from ?::timestamptz)::int || '-' || lpad(nextval('invoice_number_seq')::text, 6, '0'),
                        ?, ?, ?, ?, 'INR', ?, (select id from discounts where code = 'WELCOME10' and ? > 0), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, ts(issuedAt), memberId, subId, branch.id(), paid ? "PAID" : "ISSUED", subtotal, discount, discount,
                taxRate, tax, total, paid ? total : BigDecimal.ZERO,
                Date.valueOf(issuedAt.atZone(branch.zone()).toLocalDate().plusDays(7)), ts(issuedAt),
                paid ? ts(activatedAt) : null, ts(issuedAt));
        jdbc.update("insert into invoice_lines (invoice_id, position, line_type, description, amount) values (?, 1, 'PLAN_FEE', ?, ?)",
                invoiceId, plan.code() + " membership", plan.price());
        if (first && plan.joiningFee().signum() > 0) {
            jdbc.update("insert into invoice_lines (invoice_id, position, line_type, description, amount) "
                    + "values (?, 2, 'JOINING_FEE', 'One-time joining fee', ?)", invoiceId, plan.joiningFee());
        }
        if (paid) {
            String method = random.nextDouble() < 0.45 ? "UPI" : random.nextDouble() < 0.6 ? "CARD" : "CASH";
            if (!"CASH".equals(method) && random.nextDouble() < 0.06) {
                payment(invoiceId, memberId, total, method, "FAILED", activatedAt.minusSeconds(120), receptionist);
            }
            long paymentId = payment(invoiceId, memberId, total, method, "SUCCEEDED", activatedAt, receptionist);
            if ("EXPIRED".equals(status) && random.nextDouble() < 0.02) {
                BigDecimal refund = total.multiply(BigDecimal.valueOf(0.25)).setScale(2, RoundingMode.HALF_UP);
                jdbc.update("update payments set status = 'PARTIALLY_REFUNDED', refunded_amount = ? where id = ?", refund, paymentId);
                jdbc.update("update invoices set status = 'PARTIALLY_REFUNDED', amount_refunded = ? where id = ?", refund, invoiceId);
                jdbc.update("insert into refunds (payment_id, amount, reason, status, gateway_reference, processed_at) "
                        + "values (?, ?, 'Goodwill refund', 'SUCCEEDED', 'SIMRF-DEMO', ?)", paymentId, refund,
                        ts(activatedAt.plusSeconds(86_400L * 20)));
            }
        }
        return subId;
    }

    private long payment(long invoiceId, long memberId, BigDecimal amount, String method, String status, Instant at,
                         long receptionist) {
        boolean offline = "CASH".equals(method) || "BANK_TRANSFER".equals(method);
        String key = "demo-" + invoiceId + "-" + status + "-" + random.nextInt(1_000_000);
        return insertReturningId("""
                insert into payments (invoice_id, member_id, amount, currency, method, status, idempotency_key, request_hash,
                                      gateway_reference, failure_reason, processed_at, received_by, created_at, provider,
                                      instrument)
                values (?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, invoiceId, memberId, amount, method, status, key, String.format("%064d", invoiceId),
                "FAILED".equals(status) ? null : "SIM-DEMO-" + invoiceId, "FAILED".equals(status) ? "card_declined" : null,
                ts(at), receptionist, ts(at), offline ? "OFFLINE" : "SIMULATED", switch (method) {
                    case "CARD" -> "Visa •••• 4242";
                    case "UPI" -> "UPI me***@okaxis";
                    case "CASH" -> "Cash";
                    default -> "Bank transfer";
                });
    }

    private void visits(long memberId, Branch branch, long subscriptionId, LocalDate from, LocalDate to, double engagement,
                        LocalDate today, long receptionist) {
        LocalDate start = from.isBefore(today.minusDays(240)) ? today.minusDays(240) : from;
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            boolean sunday = d.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (random.nextDouble() > (sunday ? engagement * 0.3 : engagement)) {
                continue;
            }
            LocalTime time = sunday ? LocalTime.of(7 + random.nextInt(5), random.nextInt(60)) : visitTime();
            Instant in = d.atTime(time).atZone(branch.zone()).toInstant();
            if (in.isAfter(Instant.now(clock).minusSeconds(4 * 3600))) {
                continue;   // keep "today" free of open visits so demo check-ins work
            }
            boolean auto = random.nextDouble() < 0.03;
            Instant out = in.plusSeconds(60L * (auto ? 90 : 35 + random.nextInt(85)));
            attendanceRows.add(new Object[]{memberId, branch.id(), subscriptionId, ts(in), ts(out),
                    random.nextDouble() < 0.6 ? "QR_CODE" : "FRONT_DESK", receptionist, auto, ts(in)});
        }
    }

    /** Bimodal weekday distribution: morning peak around 07:00, evening peak around 18:30. */
    private LocalTime visitTime() {
        double r = random.nextDouble();
        double hour;
        if (r < 0.42) {
            hour = 7.0 + random.nextGaussian() * 0.9;
        } else if (r < 0.88) {
            hour = 18.5 + random.nextGaussian() * 1.2;
        } else {
            hour = 10 + random.nextDouble() * 5.5;
        }
        hour = Math.max(5.6, Math.min(21.4, hour));
        int minutes = (int) (hour * 60);
        return LocalTime.of(minutes / 60, minutes % 60);
    }

    private void trainerRelationship(long memberId, long trainerId, Branch branch, long template, LocalDate today) {
        Integer active = jdbc.queryForObject("select count(*) from member_trainers where trainer_id = ? and status = 'ACTIVE'",
                Integer.class, trainerId);
        if (active != null && active >= 25) {
            return;
        }
        jdbc.update("insert into member_trainers (member_id, trainer_id, status, goal, assigned_at) values (?, ?, 'ACTIVE', ?, ?)",
                memberId, trainerId, "Build strength and lose fat", ts(today.minusDays(60).atStartOfDay(branch.zone()).toInstant()));
        long planId = insertReturningId("""
                insert into workout_plans (name, description, goal, difficulty, duration_weeks, template, member_id,
                                           trainer_id, source_template_id, status, start_date, end_date, trainer_notes)
                select name, description, goal, difficulty, duration_weeks, false, ?, ?, id, 'ACTIVE', ?, ?, 'Focus on form first'
                from workout_plans where id = ? returning id
                """, memberId, trainerId, Date.valueOf(today.minusDays(30)), Date.valueOf(today.minusDays(30).plusWeeks(8).minusDays(1)),
                template);
        insertPlanExercises(planId);
        double weight = 62 + random.nextInt(30);
        for (int w = 8; w >= 0; w -= 2) {
            jdbc.update("""
                    insert into member_progress (member_id, workout_plan_id, recorded_at, weight_kg, body_fat_pct, waist_cm,
                                                 resting_heart_rate, recorded_by)
                    values (?, ?, ?, ?, ?, ?, ?, (select user_id from trainers where id = ?))
                    """, memberId, planId, ts(today.minusWeeks(w).atTime(9, 0).atZone(branch.zone()).toInstant()),
                    BigDecimal.valueOf(weight - (8 - w) * 0.35).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(26 - (8 - w) * 0.3).setScale(1, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(88 - (8 - w) * 0.4).setScale(1, RoundingMode.HALF_UP), 72 - (8 - w) / 2, trainerId);
        }
        // weekly sessions: past four weeks + next week, one fixed slot per member to avoid double booking
        int slotHour = 6 + random.nextInt(5);
        int slotDay = 1 + random.nextInt(6);
        for (int week = -4; week <= 1; week++) {
            LocalDate day = today.with(DayOfWeek.of(slotDay)).plusWeeks(week);
            Instant startsAt = day.atTime(slotHour, random.nextBoolean() ? 0 : 0).atZone(branch.zone()).toInstant();
            Integer clash = jdbc.queryForObject("""
                    select count(*) from training_sessions where trainer_id = ? and status in ('SCHEDULED','COMPLETED')
                      and tstzrange(starts_at, ends_at) && tstzrange(?, ?)
                    """, Integer.class, trainerId, ts(startsAt), ts(startsAt.plusSeconds(3600)));
            if (clash != null && clash > 0) {
                continue;
            }
            String status = startsAt.isAfter(Instant.now(clock)) ? "SCHEDULED"
                    : random.nextDouble() < 0.82 ? "COMPLETED" : random.nextBoolean() ? "NO_SHOW" : "CANCELLED";
            jdbc.update("insert into training_sessions (trainer_id, member_id, branch_id, starts_at, ends_at, status) "
                    + "values (?, ?, ?, ?, ?, ?)", trainerId, memberId, branch.id(), ts(startsAt), ts(startsAt.plusSeconds(3600)), status);
        }
    }

    private void complaints(Branch branch, long manager, long receptionist, LocalDate today) {
        Object[][] items = {
                {"EQUIPMENT", "HIGH", "OPEN", "Treadmill 4 belt slipping", 1},
                {"HYGIENE", "MEDIUM", "IN_PROGRESS", "Locker room needs more frequent cleaning", 3},
                {"BILLING", "MEDIUM", "RESOLVED", "Charged joining fee twice", 12},
                {"FACILITY", "LOW", "CLOSED", "Request for more yoga mats", 30},
                {"STAFF", "CRITICAL", "ESCALATED", "Unprofessional behaviour at front desk", 2}};
        for (Object[] c : items) {
            Instant created = today.minusDays((Integer) c[4]).atTime(11, 0).atZone(branch.zone()).toInstant();
            String status = (String) c[2];
            jdbc.update("""
                    insert into complaints (ticket_number, branch_id, raised_by, category, priority, status, subject, description,
                                            assigned_to, resolution, escalation_level, sla_due_at, first_response_at,
                                            resolved_at, closed_at, created_at)
                    values ('CMP-' || (extract(year from now())::int % 100) || '-' || lpad(nextval('complaint_ticket_seq')::text, 5, '0'),
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, branch.id(), receptionist, c[0], c[1], status, c[3], c[3] + " - reported at the front desk.",
                    "OPEN".equals(status) ? null : manager,
                    "RESOLVED".equals(status) || "CLOSED".equals(status) ? "Issue fixed and member informed." : null,
                    "ESCALATED".equals(status) ? 1 : 0, ts(created.plusSeconds(48 * 3600)),
                    "OPEN".equals(status) ? null : ts(created.plusSeconds(3600)),
                    "RESOLVED".equals(status) || "CLOSED".equals(status) ? ts(created.plusSeconds(86_400)) : null,
                    "CLOSED".equals(status) ? ts(created.plusSeconds(2 * 86_400)) : null, ts(created));
        }
    }

    private long insertReturningId(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        if (id == null) {
            throw new IllegalStateException("Insert returned no id");
        }
        return id;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
