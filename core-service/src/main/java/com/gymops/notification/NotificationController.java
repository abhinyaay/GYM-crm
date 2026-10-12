package com.gymops.notification;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.notification.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @Operation(summary = "The caller's in-app inbox")
    @GetMapping
    public PageResponse<NotificationResponse> inbox(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return service.inbox(unreadOnly, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", service.unreadCount());
    }

    @PatchMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable Long id) {
        return service.markRead(id);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", service.markAllRead());
    }

    @Operation(summary = "Outbox monitoring: notifications by delivery status (e.g. FAILED)")
    @GetMapping("/admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PageResponse<NotificationResponse> byStatus(@RequestParam NotificationStatus status,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return service.byStatus(status, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public NotificationResponse retry(@PathVariable Long id) {
        return service.retry(id);
    }
}
