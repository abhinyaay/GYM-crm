package com.gymops.notification.admin;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.notification.NotificationChannel;
import com.gymops.notification.NotificationStatus;
import com.gymops.notification.NotificationType;
import com.gymops.notification.admin.MessagingDtos.NotificationLogEntry;
import com.gymops.notification.admin.MessagingDtos.ProvidersResponse;
import com.gymops.notification.admin.MessagingDtos.TestMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Messaging centre for managers: delivery log, resend, provider status and test messages. */
@Tag(name = "Messaging")
@RestController
@RequestMapping("/api/v1/messaging")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER')")
@RequiredArgsConstructor
public class MessagingController {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");

    private final MessagingService service;

    @Operation(summary = "Search the notification log (branch-scoped for managers)")
    @GetMapping("/notifications")
    public PageResponse<NotificationLogEntry> search(
            @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(required = false) NotificationStatus status,
            @RequestParam(required = false) NotificationType type,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.search(new MessagingService.Filter(channel, status, type, memberId, from, to, q),
                PageRequests.of(page, size, null, Set.of(), NEWEST_FIRST));
    }

    @GetMapping("/notifications/{id}")
    public NotificationLogEntry get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Resend a FAILED notification (to the member's current address) and deliver it now")
    @PostMapping("/notifications/{id}/resend")
    public NotificationLogEntry resend(@PathVariable Long id) {
        return service.resend(id);
    }

    @Operation(summary = "Send a test e-mail / SMS to a member or (admins) any address, delivered immediately")
    @PostMapping("/test")
    public NotificationLogEntry sendTest(@Valid @RequestBody TestMessageRequest request) {
        return service.sendTest(request);
    }

    @Operation(summary = "Active e-mail / SMS providers and whether they are configured (never returns secrets)")
    @GetMapping("/providers")
    public ProvidersResponse providers() {
        return service.providers();
    }
}
