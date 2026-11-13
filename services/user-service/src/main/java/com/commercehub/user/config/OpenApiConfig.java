package com.commercehub.user.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata — /swagger-ui.html */
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI userOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShopSphere User Service")
                .version("v1")
                .description("Identity, JWT auth, profiles, addresses."));
    }
}
