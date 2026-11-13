package com.commercehub.notification.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI notificationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShopSphere Notification Service")
                .version("v1")
                .description("Event-driven simulated email/SMS notifications."));
    }
}
