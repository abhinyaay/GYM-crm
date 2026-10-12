package com.gymops;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GymOpsApplication {

    public static void main(String[] args) {
        // JDBC date/time conversion is pinned to UTC (hibernate.jdbc.time_zone); a non-UTC JVM default
        // would shift LocalTime / LocalDate columns. Business dates come from the business-timezone Clock.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(GymOpsApplication.class, args);
    }
}
