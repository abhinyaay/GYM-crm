package com.gymops.payment;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * Demo saved methods, autopay and store credit on top of the core demo data: about a third of members
 * with a current membership get a simulated card / UPI token, half of those with an autopay mandate
 * switched on (a few with tokens whose debits are declined, to show dunning), and some members get
 * goodwill store credit. Runs only with {@code app.bootstrap.demo-data=true} and only once.
 */
@Slf4j
@Order(5)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class PaymentsDemoSeeder implements ApplicationRunner {

    private static final String[] BRANDS = {"Visa", "Mastercard", "RuPay"};
    private static final String[] HANDLES = {"okaxis", "okhdfcbank", "ybl", "paytm", "okicici"};

    private final JdbcTemplate jdbc;
    private final PaymentProperties props;
    private final Clock clock;
    private final Random random = new Random(7);

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = jdbc.queryForObject("select count(*) from saved_payment_methods", Long.class);
        List<Map<String, Object>> subs = jdbc.queryForList("""
                select s.id as sub_id, s.member_id, s.end_date, s.price, m.first_name
                from subscriptions s join members m on m.id = s.member_id
                where s.status = 'ACTIVE' and m.deleted_at is null and m.status = 'ACTIVE'
                order by s.id
                """);
        if (existing == null || existing > 0 || subs.isEmpty()) {
            log.info("Payments demo seeding skipped (methods present or no active memberships)");
            return;
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        int methods = 0;
        int autopay = 0;
        for (Map<String, Object> sub : subs) {
            if (random.nextDouble() > 0.35) {
                continue;
            }
            long memberId = ((Number) sub.get("member_id")).longValue();
            boolean card = random.nextBoolean();
            boolean mandate = random.nextDouble() < 0.55;
            boolean failing = mandate && random.nextDouble() < 0.12;
            String token = (failing ? "simtok_fail_demo_" : "simtok_demo_") + memberId;
            String brand = BRANDS[random.nextInt(BRANDS.length)];
            String last4 = String.format("%04d", random.nextInt(10_000));
            String vpa = ((String) sub.get("first_name")).toLowerCase().substring(0, 2) + "***@" + HANDLES[random.nextInt(HANDLES.length)];
            Instant created = now.minus(30L + random.nextInt(200), ChronoUnit.DAYS);
            Long methodId = jdbc.queryForObject("""
                    insert into saved_payment_methods (member_id, provider, type, provider_customer_id, provider_token, brand,
                        last4, exp_month, exp_year, vpa_masked, label, is_default, status, mandate_status, mandate_max_amount,
                        mandate_expires_at, created_at, updated_at)
                    values (?, 'SIMULATED', ?, ?, ?, ?, ?, ?, ?, ?, ?, true, 'ACTIVE', ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, memberId, card ? "CARD" : "UPI", "sim_cust_demo_" + memberId, token,
                    card ? brand : null, card ? last4 : null, card ? 1 + random.nextInt(12) : null,
                    card ? today.getYear() + 1 + random.nextInt(4) : null, card ? null : vpa,
                    card ? brand + " •••• " + last4 : "UPI " + vpa,
                    mandate ? "ACTIVE" : "NONE", mandate ? props.mandateMaxAmount() : null,
                    mandate ? today.plusYears(5) : null, Timestamp.from(created), Timestamp.from(created));
            methods++;
            if (mandate && ((BigDecimal) sub.get("price")).signum() > 0) {
                jdbc.update("update subscriptions set auto_renew = true, autopay_method_id = ? where id = ?", methodId,
                        sub.get("sub_id"));
                autopay++;
            }
        }
        int credits = 0;
        for (Map<String, Object> sub : subs) {
            if (random.nextDouble() > 0.06) {
                continue;
            }
            BigDecimal amount = BigDecimal.valueOf(250L * (1 + random.nextInt(8))).setScale(2);
            jdbc.update("""
                    insert into member_credit_ledger (member_id, entry_type, amount, balance_after, reason, created_at)
                    values (?, 'ISSUED', ?, ?, ?, ?)
                    """, sub.get("member_id"), amount, amount,
                    random.nextBoolean() ? "Goodwill: AC outage in the cardio zone" : "Referral reward",
                    Timestamp.from(now.minus(random.nextInt(60), ChronoUnit.DAYS)));
            credits++;
        }
        log.info("Payments demo data seeded: {} saved methods, {} on autopay, {} store-credit grants", methods, autopay, credits);
    }
}
