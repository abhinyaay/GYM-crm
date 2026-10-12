package com.gymops.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long>, JpaSpecificationExecutor<Notification> {

    boolean existsByDedupKey(String dedupKey);

    /**
     * Claims a batch of due notifications. FOR UPDATE SKIP LOCKED lets several dispatcher instances
     * work the queue concurrently without picking the same rows and without blocking each other.
     */
    @Query(value = """
            select * from notifications
            where status = 'PENDING' and next_attempt_at <= :now
            order by next_attempt_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Notification> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Claims one PENDING row (immediate test sends); empty when it is not pending or another dispatcher holds it. */
    @Query(value = "select * from notifications where id = :id and status = 'PENDING' for update skip locked", nativeQuery = true)
    Optional<Notification> claimById(@Param("id") Long id);

    @Query("""
            select n from Notification n
            where n.recipientUserId = :userId and n.channel = 'IN_APP'
              and (:unreadOnly = false or n.readAt is null)
            order by n.createdAt desc
            """)
    Page<Notification> findInbox(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    @Query("select count(n) from Notification n where n.recipientUserId = :userId and n.channel = 'IN_APP' and n.readAt is null")
    long countUnread(@Param("userId") Long userId);

    @Modifying
    @Query("""
            update Notification n set n.readAt = :now
            where n.recipientUserId = :userId and n.channel = 'IN_APP' and n.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    Page<Notification> findByStatusOrderByCreatedAtDesc(NotificationStatus status, Pageable pageable);
}
