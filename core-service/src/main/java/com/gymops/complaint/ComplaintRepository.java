package com.gymops.complaint;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplaintRepository extends JpaRepository<Complaint, Long>, JpaSpecificationExecutor<Complaint> {

    @Query(value = "select nextval('complaint_ticket_seq')", nativeQuery = true)
    long nextTicketSequence();

    @Query("""
            select c.id from Complaint c
            where c.status in ('OPEN','IN_PROGRESS','REOPENED','ESCALATED') and c.slaDueAt < :now
              and c.escalationLevel < 3
            """)
    List<Long> findSlaBreachedIds(@Param("now") Instant now);
}
