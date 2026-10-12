package com.gymops.notification.preference;

import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByMemberId(Long memberId);

    Optional<NotificationPreference> findByMemberIdAndCategoryAndChannel(Long memberId, NotificationCategory category,
                                                                          NotificationChannel channel);
}
