package com.gymops.classes;

import com.gymops.config.CacheConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo group-class data: a class catalogue, monthly class credits on the demo plans and, per branch,
 * a weekly timetable taught by the branch's trainers for the past three and the next two weeks, with
 * realistic bookings, waitlists, cancellations and attendance. Runs after the core demo seeder and
 * only when {@code class_types} is empty and branches exist (so it also back-fills an existing demo DB).
 *
 * <p>Respects every constraint the live system enforces: no instructor overlap, one live booking per
 * member and class, plan credits per month, plan access hours, booking window (no bookings before it
 * opens), and no member reaching the no-show block.
 */
@Slf4j
@Order(3)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class ClassesDemoSeeder implements ApplicationRunner {

    private static final int PAST_DAYS = 21;
    private static final int FUTURE_DAYS = 14;
    private static final Set<Integer> WEEKDAYS = Set.of(1, 2, 3, 4, 5);

    private record TypeDef(String name, String description, String category, String intensity, int minutes, int capacity) {
    }

    /** One weekly slot: days, local time, class type index, trainer index, room and a popularity factor. */
    private record Slot(Set<Integer> days, LocalTime time, int type, int trainer, String room, double popularity) {
    }

    private record SeedType(long id, int minutes, int capacity) {
    }

    private record Eligible(long memberId, Long userId, Integer credits, LocalDate start, LocalDate end, LocalTime accessStart,
                            LocalTime accessEnd) {
        boolean admits(LocalDate date, LocalTime from, LocalTime to) {
            if (date.isBefore(start) || date.isAfter(end) || (credits != null && credits == 0)) {
                return false;
            }
            return accessStart == null || (!from.isBefore(accessStart) && !to.isAfter(accessEnd));
        }
    }

    private static final List<TypeDef> TYPES = List.of(
            new TypeDef("Power Yoga", "Dynamic vinyasa flow building strength and flexibility", "YOGA", "LOW", 60, 20),
            new TypeDef("HIIT Blast", "Intervals of all-out effort and short recovery", "HIIT", "HIGH", 45, 16),
            new TypeDef("Spin Cycle", "Indoor cycling to the beat, hills and sprints", "CYCLING", "HIGH", 45, 18),
            new TypeDef("Strength Circuit", "Full-body barbell, kettlebell and dumbbell circuit", "STRENGTH", "MEDIUM", 50, 14),
            new TypeDef("Zumba Party", "Latin-inspired dance cardio", "DANCE", "MEDIUM", 60, 25),
            new TypeDef("Core Pilates", "Mat pilates for core stability and posture", "PILATES", "LOW", 50, 12));

    private static final List<Slot> TIMETABLE = List.of(
            new Slot(WEEKDAYS, LocalTime.of(6, 30), 2, 0, "Studio B", 0.75),
            new Slot(WEEKDAYS, LocalTime.of(7, 0), 0, 1, "Studio A", 0.85),
            new Slot(Set.of(1, 3, 5), LocalTime.of(12, 30), 3, 0, "Studio A", 0.5),
            new Slot(Set.of(2, 4), LocalTime.of(12, 30), 5, 1, "Studio B", 0.55),
            new Slot(WEEKDAYS, LocalTime.of(18, 0), 1, 1, "Studio A", 1.2),
            new Slot(WEEKDAYS, LocalTime.of(18, 30), 2, 0, "Studio B", 1.05),
            new Slot(Set.of(1, 2, 3, 4), LocalTime.of(19, 30), 4, 1, "Studio A", 1.0),
            new Slot(Set.of(6), LocalTime.of(8, 0), 0, 0, "Studio A", 1.1),
            new Slot(Set.of(6), LocalTime.of(9, 30), 4, 1, "Studio A", 1.0),
            new Slot(Set.of(6), LocalTime.of(10, 0), 1, 0, "Studio B", 0.9),
            new Slot(Set.of(7), LocalTime.of(8, 0), 0, 1, "Studio A", 0.8));

    private static final List<Slot> BY_POPULARITY = TIMETABLE.stream()
            .sorted(java.util.Comparator.comparingDouble(Slot::popularity).reversed()).toList();

    private static final Map<String, Integer> PLAN_CREDITS = Map.of(
            "MONTHLY-STD", 8, "QUARTERLY-STD", 8, "OFFPEAK-MONTHLY", 4, "TRIAL-15", 2);

    private final JdbcTemplate jdbc;
    private final CacheManager cacheManager;
    private final Clock clock;
    private final Random random = new Random(7);

    private final Map<String, Integer> creditsUsed = new HashMap<>();
    private final Map<Long, List<Instant>> memberClassTimes = new HashMap<>();
    private final Map<Long, Integer> noShows = new HashMap<>();
    private final List<Object[]> bookingRows = new ArrayList<>();

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long types = jdbc.queryForObject("select count(*) from class_types", Long.class);
        Long branches = jdbc.queryForObject("select count(*) from gym_branches where deleted_at is null", Long.class);
        if (types == null || types > 0 || branches == null || branches == 0) {
            log.info("Class demo data seeding skipped: {} class types, {} branches", types, branches);
            return;
        }
        long started = System.currentTimeMillis();
        PLAN_CREDITS.forEach((code, credits) -> jdbc.update(
                "update membership_plans set class_credits_per_month = ? where code = ? and class_credits_per_month is null",
                credits, code));
        evictPlanCaches();
        List<SeedType> seedTypes = new ArrayList<>();
        for (TypeDef t : TYPES) {
            Long id = jdbc.queryForObject("""
                    insert into class_types (name, description, category, intensity, default_duration_min, default_capacity)
                    values (?, ?, ?, ?, ?, ?) returning id
                    """, Long.class, t.name(), t.description(), t.category(), t.intensity(), t.minutes(), t.capacity());
            seedTypes.add(new SeedType(id == null ? 0 : id, t.minutes(), t.capacity()));
        }
        Instant now = Instant.now(clock);
        int sessions = 0;
        for (Map<String, Object> branch : jdbc.queryForList(
                "select id, timezone from gym_branches where deleted_at is null and status = 'ACTIVE' order by id")) {
            sessions += seedBranch(((Number) branch.get("id")).longValue(), ZoneId.of((String) branch.get("timezone")),
                    seedTypes, now);
        }
        jdbc.batchUpdate("""
                insert into class_bookings (session_id, member_id, status, booked_at, promoted_at, cancelled_at, attended_at,
                                            booked_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bookingRows);
        log.info("Class demo data seeded: {} class types, {} sessions, {} bookings in {} ms", seedTypes.size(), sessions,
                bookingRows.size(), System.currentTimeMillis() - started);
    }

    /** Plans are served from Redis; the credit update above bypassed the service, so drop the cached copies. */
    private void evictPlanCaches() {
        for (String name : List.of(CacheConfig.PLANS, CacheConfig.ACTIVE_PLANS)) {
            try {
                Cache cache = cacheManager.getCache(name);
                if (cache != null) {
                    cache.clear();
                }
            } catch (RuntimeException e) {
                log.warn("Could not evict cache {}: {}", name, e.getMessage());
            }
        }
    }

    private int seedBranch(long branchId, ZoneId zone, List<SeedType> types, Instant now) {
        List<Long> trainers = jdbc.queryForList("""
                select id from trainers where home_branch_id = ? and deleted_at is null and status = 'ACTIVE' order by id
                """, Long.class, branchId);
        if (trainers.isEmpty()) {
            return 0;
        }
        List<Eligible> members = eligibleMembers(branchId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Map<Long, List<Instant[]>> trainerBusy = new HashMap<>();
        int created = 0;
        for (LocalDate day = today.minusDays(PAST_DAYS); day.isBefore(today.plusDays(FUTURE_DAYS)); day = day.plusDays(1)) {
            // popular slots are filled first, so the limited member pool lands where demand really is
            for (Slot slot : BY_POPULARITY) {
                if (!slot.days().contains(day.getDayOfWeek().getValue())) {
                    continue;
                }
                SeedType type = types.get(slot.type());
                long trainer = trainers.get(slot.trainer() % trainers.size());
                Instant start = day.atTime(slot.time()).atZone(zone).toInstant();
                Instant end = start.plus(Duration.ofMinutes(type.minutes()));
                List<Instant[]> busy = trainerBusy.computeIfAbsent(trainer, k -> new ArrayList<>());
                if (busy.stream().anyMatch(b -> b[0].isBefore(end) && b[1].isAfter(start))) {
                    continue;   // single-trainer branch: never double-book the instructor
                }
                busy.add(new Instant[] {start, end});
                boolean past = !end.plus(Duration.ofMinutes(30)).isAfter(now);
                boolean cancelled = random.nextDouble() < (past ? 0.03 : 0.02);
                String status = cancelled ? "CANCELLED" : past ? "COMPLETED" : "SCHEDULED";
                Long sessionId = jdbc.queryForObject("""
                        insert into class_sessions (class_type_id, branch_id, instructor_id, room, starts_at, ends_at, capacity,
                                                    waitlist_capacity, status, cancellation_reason, series_id, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, 5, ?, ?, ?, ?) returning id
                        """, Long.class, type.id(), branchId, trainer, slot.room(), Timestamp.from(start), Timestamp.from(end),
                        type.capacity(), status, cancelled ? "Instructor unavailable" : null,
                        UUID.nameUUIDFromBytes((branchId + "-" + TIMETABLE.indexOf(slot)).getBytes(StandardCharsets.UTF_8)),
                        Timestamp.from(start.minus(Duration.ofDays(30))));
                created++;
                if (!cancelled && sessionId != null) {
                    seedBookings(sessionId, day, start, end, zone, type.capacity(), slot.popularity(), members, past, now);
                }
            }
        }
        return created;
    }

    private List<Eligible> eligibleMembers(long branchId) {
        return jdbc.query("""
                select m.id, m.user_id, p.class_credits_per_month, s.start_date, s.end_date + p.grace_period_days as end_date,
                       p.access_start_time, p.access_end_time
                from members m
                join subscriptions s on s.member_id = m.id and s.status in ('ACTIVE', 'GRACE')
                join membership_plans p on p.id = s.plan_id
                where m.home_branch_id = ? and m.deleted_at is null and m.status = 'ACTIVE'
                order by m.id
                """, (rs, i) -> {
                Time accessStart = rs.getTime("access_start_time");
                Time accessEnd = rs.getTime("access_end_time");
                Integer credits = (Integer) rs.getObject("class_credits_per_month");
                Long userId = (Long) rs.getObject("user_id");
                return new Eligible(rs.getLong("id"), userId, credits, rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(), accessStart == null ? null : accessStart.toLocalTime(),
                        accessEnd == null ? null : accessEnd.toLocalTime());
            }, branchId);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void seedBookings(long sessionId, LocalDate day, Instant start, Instant end, ZoneId zone, int capacity,
                              double popularity, List<Eligible> members, boolean past, Instant now) {
        Instant opens = start.minus(Duration.ofHours(168));
        if (opens.isAfter(now)) {
            return;   // booking window not open yet
        }
        // classes further out are emptier: demand ramps up as the class approaches
        double ramp = past || start.isBefore(now) ? 1.0
                : Math.max(0.25, 1.0 - Duration.between(now, start).toHours() / 168.0 * 0.75);
        int demand = (int) Math.round(capacity * popularity * ramp * (0.6 + random.nextDouble() * 0.45));
        LocalTime from = start.atZone(zone).toLocalTime();
        LocalTime to = end.minusSeconds(60).atZone(zone).toLocalTime();
        YearMonth month = YearMonth.from(day);
        List<Eligible> shuffled = new ArrayList<>(members.stream().filter(m -> m.admits(day, from, to)).toList());
        Collections.shuffle(shuffled, random);
        // demo logins get a fair share of classes so the member portal has something to show
        List<Eligible> candidates = new ArrayList<>();
        List<Eligible> others = new ArrayList<>();
        for (Eligible m : shuffled) {
            (m.userId() != null && random.nextDouble() < 0.15 ? candidates : others).add(m);
        }
        candidates.addAll(others);
        List<Eligible> seats = new ArrayList<>();
        List<Eligible> waitlist = new ArrayList<>();
        for (Eligible m : candidates) {
            if (seats.size() + waitlist.size() >= demand || waitlist.size() >= 5) {
                break;
            }
            String key = m.memberId() + ":" + month;
            if (m.credits() != null && creditsUsed.getOrDefault(key, 0) >= m.credits()) {
                continue;
            }
            // even unlimited members rarely take more than five classes a week
            String week = m.memberId() + ":w" + day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            if (creditsUsed.getOrDefault(week, 0) >= 5) {
                continue;
            }
            creditsUsed.merge(week, 1, Integer::sum);
            List<Instant> times = memberClassTimes.computeIfAbsent(m.memberId(), k -> new ArrayList<>());
            if (times.stream().anyMatch(t -> Duration.between(t, start).abs().toMinutes() < 75)) {
                continue;
            }
            times.add(start);
            if (seats.size() < capacity) {
                seats.add(m);
                creditsUsed.merge(key, 1, Integer::sum);
            } else {
                waitlist.add(m);
            }
        }
        Instant latestBooking = past || start.isBefore(now) ? start.minus(Duration.ofMinutes(30)) : now;
        if (!past) {
            seats.forEach(m -> addBooking(sessionId, m, "BOOKED", bookedAt(opens, latestBooking), null, null, null));
            waitlist.forEach(m -> addBooking(sessionId, m, "WAITLISTED", bookedAt(opens, latestBooking), null, null, null));
            return;
        }
        int nextWaitlisted = 0;
        List<Eligible> attendees = new ArrayList<>(seats);
        for (int i = 0; i < attendees.size(); i++) {
            Eligible m = attendees.get(i);
            Instant bookedAt = bookedAt(opens, latestBooking);
            Instant promotedAt = i >= seats.size() ? bookedAt.plus(Duration.ofMinutes(5 + random.nextInt(600))) : null;
            if (promotedAt != null && promotedAt.isAfter(start)) {
                promotedAt = start.minus(Duration.ofMinutes(10));
            }
            double roll = random.nextDouble();
            if (roll < 0.06 || (roll < 0.10 && promotedAt == null)) {
                boolean late = roll >= 0.06;
                Instant cancelledAt = late ? start.minus(Duration.ofMinutes(20 + random.nextInt(90)))
                        : start.minus(Duration.ofHours(3 + random.nextInt(40)));
                if (cancelledAt.isBefore(bookedAt)) {
                    cancelledAt = bookedAt.plus(Duration.ofMinutes(1));
                }
                if (!late) {
                    creditsUsed.merge(m.memberId() + ":" + YearMonth.from(day), -1, Integer::sum);
                }
                addBooking(sessionId, m, late ? "LATE_CANCELLED" : "CANCELLED", bookedAt, promotedAt, cancelledAt, null);
                if (nextWaitlisted < waitlist.size()) {
                    attendees.add(waitlist.get(nextWaitlisted++));   // freed seat goes to the waitlist, FIFO
                }
                continue;
            }
            boolean noShow = roll > 0.90 && noShows.getOrDefault(m.memberId(), 0) < 2;
            if (noShow) {
                noShows.merge(m.memberId(), 1, Integer::sum);
            }
            addBooking(sessionId, m, noShow ? "NO_SHOW" : "ATTENDED", bookedAt, promotedAt, null,
                    noShow ? null : start.plus(Duration.ofMinutes(random.nextInt(5))));
        }
        for (int i = nextWaitlisted; i < waitlist.size(); i++) {
            addBooking(sessionId, waitlist.get(i), "CANCELLED", bookedAt(opens, latestBooking), null,
                    end.plus(Duration.ofMinutes(30)), null);
        }
    }

    private Instant bookedAt(Instant opens, Instant latest) {
        long span = Math.max(1, Duration.between(opens, latest).toMinutes());
        return opens.plus(Duration.ofMinutes((long) (span * Math.pow(random.nextDouble(), 0.6))));
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void addBooking(long sessionId, Eligible m, String status, Instant bookedAt, Instant promotedAt, Instant cancelledAt,
                            Instant attendedAt) {
        Instant updated = cancelledAt != null ? cancelledAt : attendedAt != null ? attendedAt
                : promotedAt != null ? promotedAt : bookedAt;
        bookingRows.add(new Object[] {sessionId, m.memberId(), status, Timestamp.from(bookedAt), ts(promotedAt), ts(cancelledAt),
            ts(attendedAt), m.userId(), Timestamp.from(bookedAt), Timestamp.from(updated)});
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
