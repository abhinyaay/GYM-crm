package com.gymops.notification.preference;

import com.gymops.notification.preference.PreferenceDtos.UnsubscribeRequest;
import com.gymops.notification.preference.PreferenceDtos.UnsubscribeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (no login, rate limited under {@code /api/v1/public/**}) unsubscribe endpoint. The web page posts JSON;
 * mail clients performing an RFC 8058 one-click unsubscribe POST {@code List-Unsubscribe=One-Click} as a form to
 * the URL from the {@code List-Unsubscribe} header, which carries the token in the query string. GET never
 * unsubscribes (link scanners prefetch URLs).
 */
@Tag(name = "Notifications")
@RestController
@RequestMapping("/api/v1/public/notifications")
@RequiredArgsConstructor
public class PublicUnsubscribeController {

    private final NotificationPreferenceService service;

    @Operation(summary = "Unsubscribe (or resubscribe) via a signed e-mail link")
    @PostMapping(path = "/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UnsubscribeResponse unsubscribe(@Valid @RequestBody UnsubscribeRequest request) {
        return service.unsubscribe(request.token(), request.resubscribe());
    }

    @Operation(summary = "RFC 8058 one-click unsubscribe (token in the query string, form body ignored)")
    @PostMapping(path = "/unsubscribe")
    public UnsubscribeResponse oneClick(@RequestParam("token") String token) {
        return service.unsubscribe(token, false);
    }
}
