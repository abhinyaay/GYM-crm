package com.gymops.access;

import com.gymops.notification.NotificationService;
import com.gymops.notification.NotificationType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Notifies the managers of a branch about gate alarms and devices going offline (in-app outbox rows). */
@Component
@RequiredArgsConstructor
public class AccessNotifier {

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyBranchManagers(Long branchId, NotificationType type, String title, String body) {
        List<Long> managers = jdbc.queryForList("""
                select distinct u.id from users u
                join staff_branch_assignments a on a.user_id = u.id
                join user_roles ur on ur.user_id = u.id
                join roles r on r.id = ur.role_id
                where a.branch_id = ? and r.name = 'MANAGER' and u.deleted_at is null and u.status = 'ACTIVE'
                """, Long.class, branchId);
        if (!managers.isEmpty()) {
            notifications.notifyUsers(managers, type, title, body);
        }
    }
}
