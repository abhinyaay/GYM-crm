package com.gymops.pricing;

import com.gymops.pricing.dto.PricingDtos.PriceChangeRequest;
import com.gymops.pricing.gst.Gstin;
import com.gymops.pricing.gst.IndianStates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Demo GST registrations (a fictitious, checksum-valid GSTIN per branch) and one scheduled price change
 * with existing-member protection, so the pricing screens and tax invoices have something to show.
 * Runs only with {@code app.bootstrap.demo-data=true} and only while no price history exists.
 */
@Slf4j
@Order(8)
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.bootstrap.demo-data", havingValue = "true")
public class PricingDemoSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final PriceChangeService priceChanges;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long history = jdbc.queryForObject("select count(*) from plan_price_changes", Long.class);
        if (history != null && history > 0) {
            log.info("Pricing demo seeding skipped (price history present)");
            return;
        }
        int registered = 0;
        for (Map<String, Object> b : jdbc.queryForList(
                "select id, state from gym_branches where gstin is null and deleted_at is null order by id")) {
            long id = ((Number) b.get("id")).longValue();
            String state = IndianStates.codeFor((String) b.get("state")).orElse("27");
            String first14 = state + "AABCG" + String.format("%04d", 1000 + id % 9000) + "F1Z";
            jdbc.update("update gym_branches set gstin = ?, gst_state_code = ? where id = ?",
                    first14 + Gstin.checkChar(first14), state, id);
            registered++;
        }
        List<Map<String, Object>> plans = jdbc.queryForList("""
                select id, price from membership_plans where status = 'ACTIVE' and price > 0 order by price desc limit 1
                """);
        if (!plans.isEmpty()) {
            long planId = ((Number) plans.get(0).get("id")).longValue();
            BigDecimal price = (BigDecimal) plans.get(0).get("price");
            BigDecimal raised = price.multiply(new BigDecimal("1.08")).setScale(-2, RoundingMode.HALF_UP).setScale(2);
            priceChanges.schedule(planId, new PriceChangeRequest(raised, null, LocalDate.now(clock).plusDays(21),
                    ExistingMemberPolicy.PROTECT_EXISTING, 3, null, "Annual revision: equipment upgrade and trainer costs"));
        }
        log.info("Pricing demo data: {} branch GST registrations, {} scheduled price change(s)", registered, plans.size());
    }
}
