package com.gymops.notification.preference;

import com.gymops.notification.preference.PreferenceDtos.PreferencesResponse;
import com.gymops.notification.preference.PreferenceDtos.UpdatePreferencesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-member e-mail / SMS choices. The member themselves, or staff with access to the member's branch. */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/members/{memberId}/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;

    @Operation(summary = "Effective notification preferences per category x channel (locked ones flagged)")
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','TRAINER','MEMBER')")
    public PreferencesResponse get(@PathVariable Long memberId) {
        return service.get(memberId);
    }

    @Operation(summary = "Change one or more preferences (locked channels cannot be switched off)")
    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN','MANAGER','RECEPTIONIST','MEMBER')")
    public PreferencesResponse update(@PathVariable Long memberId, @Valid @RequestBody UpdatePreferencesRequest request) {
        return service.update(memberId, request.preferences());
    }
}
