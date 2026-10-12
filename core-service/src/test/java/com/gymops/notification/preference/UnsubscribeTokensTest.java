package com.gymops.notification.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymops.notification.NotificationCategory;
import com.gymops.notification.NotificationChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class UnsubscribeTokensTest {

    private final UnsubscribeTokens tokens = new UnsubscribeTokens("unit-test-key-0123456789abcdef0123".getBytes(StandardCharsets.UTF_8));

    @Test
    void roundTripsWithoutAnyLookup() {
        String token = tokens.create(42L, NotificationCategory.MARKETING, NotificationChannel.EMAIL);
        assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(tokens.verify(token)).contains(
                new UnsubscribeTokens.Subject(42L, NotificationCategory.MARKETING, NotificationChannel.EMAIL));
    }

    @Test
    void rejectsTamperedForgedAndMalformedTokens() {
        String token = tokens.create(42L, NotificationCategory.MARKETING, NotificationChannel.EMAIL);
        String otherMember = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("43:MARKETING:EMAIL".getBytes(StandardCharsets.UTF_8)) + token.substring(token.indexOf('.'));
        assertThat(tokens.verify(otherMember)).as("payload swapped").isEmpty();
        assertThat(tokens.verify(token.substring(0, token.length() - 2) + "AA")).as("signature altered").isEmpty();
        UnsubscribeTokens otherKey = new UnsubscribeTokens("another-key-0123456789abcdef012345".getBytes(StandardCharsets.UTF_8));
        assertThat(otherKey.verify(token)).as("different key").isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify("no-dot")).isEmpty();
        assertThat(tokens.verify("a.b.c")).isEmpty();
        assertThat(tokens.verify("!!!.???")).isEmpty();
        assertThat(tokens.verify("x".repeat(600) + ".y")).isEmpty();
    }
}
