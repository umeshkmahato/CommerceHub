package com.commercehub.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI paymentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ShopSphere Payment Service")
                .version("v1")
                .description("Simulated payment gateway, ledger, refunds."));
    }
}
