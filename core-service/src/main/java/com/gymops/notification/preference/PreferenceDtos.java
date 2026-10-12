package com.gymops.notification.preference;

import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class PreferenceDtos {

    private PreferenceDtos() {
    }

    /**
     * @param locked         cannot be switched off (legal / security notices)
     * @param defaultEnabled value used while the member has not chosen
     */
    public record ChannelPreference(NotificationChannel channel, boolean enabled, boolean locked, boolean defaultEnabled) {
    }

    public record CategoryPreferences(NotificationCategory category, String label, String description, boolean promotional,
                                      String lockedReason, List<ChannelPreference> channels) {
    }

    /**
     * @param smsNumber    the member's phone in E.164 (null when it cannot receive SMS)
     * @param quietHours   e.g. "21:00-09:00 Asia/Kolkata" (promotional SMS is held back during these hours)
     */
    public record PreferencesResponse(Long memberId, String email, String smsNumber, boolean smsReachable, String quietHours,
                                      List<CategoryPreferences> categories) {
    }

    public record PreferenceChange(@NotNull NotificationCategory category, @NotNull NotificationChannel channel,
                                   @NotNull Boolean enabled) {
    }

    public record UpdatePreferencesRequest(@NotEmpty @Size(max = 32) List<@Valid @NotNull PreferenceChange> preferences) {
    }

    public record UnsubscribeRequest(@NotBlank @Size(max = 512) String token, boolean resubscribe) {
    }

    /** @param email masked address the link belongs to (lets the reader recognise the account; no other data) */
    public record UnsubscribeResponse(NotificationCategory category, String categoryLabel, NotificationChannel channel,
                                      boolean subscribed, String email) {
    }
}
