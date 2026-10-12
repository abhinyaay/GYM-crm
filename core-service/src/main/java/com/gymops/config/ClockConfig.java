package com.gymops.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable clock in the business timezone. All "today" / "now" decisions (expiry,
 * grace periods, check-in windows) go through it, which makes date logic deterministic in tests.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.businessTimezone()));
    }
}
