package com.gymops.growth.campaign;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @Query("select c.id from Campaign c where c.status = com.gymops.growth.campaign.CampaignStatus.ACTIVE "
            + "and c.nextRunAt <= :now order by c.nextRunAt")
    List<Long> findDueIds(@Param("now") Instant now);
}
