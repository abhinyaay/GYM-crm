package com.gymops.settings;

import com.gymops.settings.FeatureService.FeatureUpdate;
import com.gymops.settings.FeatureService.Features;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Settings")
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final FeatureService features;

    @Operation(summary = "Optional modules that are switched on (any signed-in user; drives the menus)")
    @GetMapping("/features")
    public Features features() {
        return features.features();
    }

    @Operation(summary = "Switch optional modules on or off (gate access control, fingerprint / face)")
    @PutMapping("/features")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','GYM_ADMIN')")
    public Features update(@RequestBody FeatureUpdate request) {
        return features.update(request);
    }
}
