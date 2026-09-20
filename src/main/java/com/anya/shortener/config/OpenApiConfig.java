package com.anya.shortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI apiDocs() {
        return new OpenAPI().info(new Info()
                .title("URL Shortener API")
                .version("1.0.0")
                .description("""
                        Short links with click analytics, backed by MongoDB and cached in Redis.

                        Redirects resolve through a Redis cache-aside read with negative caching;
                        click counts are buffered in Redis and folded into MongoDB in batches so
                        the redirect path stays free of database writes.
                        """)
                .license(new License().name("MIT")));
    }
}
