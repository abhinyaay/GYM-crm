package com.gymops.pricing;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceChangeNoticeRepository extends JpaRepository<PriceChangeNotice, Long> {

    /** Notices that announce a price at least as high as {@code price} (a notice for more covers less). */
    @Query("""
            select n from PriceChangeNotice n
            where n.memberId = :memberId and n.planId = :planId and n.newPrice >= :price
            order by n.notifiedAt asc
            """)
    List<PriceChangeNotice> findCovering(@Param("memberId") Long memberId, @Param("planId") Long planId,
                                         @Param("price") BigDecimal price);

    boolean existsByMemberIdAndPlanIdAndNewPrice(Long memberId, Long planId, BigDecimal newPrice);

    List<PriceChangeNotice> findByPriceChangeId(Long priceChangeId);

    @Modifying
    @Query("delete from PriceChangeNotice n where n.priceChangeId = :priceChangeId")
    int deleteByPriceChange(@Param("priceChangeId") Long priceChangeId);
}
