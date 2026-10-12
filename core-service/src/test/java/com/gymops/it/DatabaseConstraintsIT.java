package com.gymops.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gymops.common.exception.DatabaseConstraints;
import com.gymops.common.exception.ErrorCode;
import com.gymops.payment.DiscountRepository;
import com.gymops.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repository-level tests of invariants enforced by PostgreSQL itself, bypassing application code
 * to prove they hold even if a service bug or a manual SQL fix tried to break them.
 */
class DatabaseConstraintsIT extends IntegrationTestBase {

    @Autowired DiscountRepository discountRepository;
    @Autowired TransactionTemplate tx;

    private long[] memberAndPlan() {
        String admin = admin();
        long branchId = createBranch(admin);
        String desk = login(createStaff(admin, "RECEPTIONIST", branchId), STAFF_PASSWORD);
        long memberId = createMember(desk, branchId);
        long planId = createPlan(admin, "MONTHLY", "1000.00", "PRORATED", 0, java.util.Map.of());
        return new long[]{memberId, planId, branchId};
    }

    private void insertSubscription(long[] ids, String status, LocalDate start, LocalDate end) {
        jdbc.update("""
                insert into subscriptions (member_id, plan_id, branch_id, status, change_type, start_date, end_date, price)
                values (?, ?, ?, ?, 'NEW', ?, ?, 1000)
                """, ids[0], ids[1], ids[2], status, start, end);
    }

    @Test
    void exclusionConstraintRejectsOverlappingCommittedSubscriptions() {
        long[] ids = memberAndPlan();
        LocalDate d = LocalDate.of(2030, 1, 1);
        insertSubscription(ids, "ACTIVE", d, d.plusDays(29));
        assertThatThrownBy(() -> insertSubscription(ids, "SCHEDULED", d.plusDays(29), d.plusDays(58)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(DatabaseConstraints.lookup(e))
                        .hasValueSatisfying(m -> assertThat(m.code()).isEqualTo(ErrorCode.SUBSCRIPTION_OVERLAP)));
        // adjacent term (no overlap) and terminal statuses are allowed
        insertSubscription(ids, "SCHEDULED", d.plusDays(30), d.plusDays(59));
        insertSubscription(ids, "CANCELLED", d.plusDays(5), d.plusDays(10));
    }

    @Test
    void partialUniqueIndexAllowsOnlyOneOpenVisit() {
        long[] ids = memberAndPlan();
        insertSubscription(ids, "ACTIVE", LocalDate.of(2031, 1, 1), LocalDate.of(2031, 1, 30));
        Long subId = jdbc.queryForObject("select id from subscriptions where member_id = ?", Long.class, ids[0]);
        String insertVisit = "insert into attendance (member_id, branch_id, subscription_id, check_in_time, check_in_method) "
                + "values (?, ?, ?, now(), 'FRONT_DESK')";
        jdbc.update(insertVisit, ids[0], ids[2], subId);
        assertThatThrownBy(() -> jdbc.update(insertVisit, ids[0], ids[2], subId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_attendance_one_open_visit");
        jdbc.update("update attendance set check_out_time = now() where member_id = ?", ids[0]);
        jdbc.update(insertVisit, ids[0], ids[2], subId);   // a new visit after checking out is fine
    }

    @Test
    void discountRedemptionCannotExceedLimitUnderConcurrency() throws Exception {
        String code = unique("LIM").replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        Long discountId = jdbc.queryForObject("""
                insert into discounts (code, discount_type, value, valid_from, max_redemptions)
                values (?, 'PERCENTAGE', 10, current_date, 5) returning id
                """, Long.class, code);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(pool.submit(() -> tx.execute(s -> discountRepository.tryRedeem(discountId))));
        }
        int redeemed = 0;
        for (Future<Integer> f : results) {
            redeemed += f.get();
        }
        pool.shutdown();
        assertThat(redeemed).isEqualTo(5);
        assertThat(jdbc.queryForObject("select times_redeemed from discounts where id = ?", Integer.class, discountId))
                .isEqualTo(5);
    }
}
