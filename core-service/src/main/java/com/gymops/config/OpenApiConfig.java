package com.gymops.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI gymOpsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("GymOps Core API")
                        .version("v1")
                        .description("Transactional API for memberships, billing, attendance, trainers, "
                                + "workouts, complaints and notifications. Authenticate via POST /api/v1/auth/login "
                                + "and use the access token as a Bearer token."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
