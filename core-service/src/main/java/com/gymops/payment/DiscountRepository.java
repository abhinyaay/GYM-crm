package com.gymops.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiscountRepository extends JpaRepository<Discount, Long> {

    @Query("select d from Discount d where upper(d.code) = upper(:code)")
    Optional<Discount> findByCode(@Param("code") String code);

    /**
     * Atomic, race-free redemption: the conditional UPDATE only succeeds while redemptions remain,
     * so two concurrent checkouts can never over-redeem a limited code.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            update discounts set times_redeemed = times_redeemed + 1, updated_at = now()
            where id = :id and active and (max_redemptions is null or times_redeemed < max_redemptions)
            """, nativeQuery = true)
    int tryRedeem(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query(value = "update discounts set times_redeemed = greatest(times_redeemed - 1, 0), updated_at = now() "
            + "where id = :id", nativeQuery = true)
    int release(@Param("id") Long id);
}
