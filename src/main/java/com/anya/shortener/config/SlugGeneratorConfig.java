package com.anya.shortener.config;

import com.anya.shortener.service.CounterSlugGenerator;
import com.anya.shortener.service.RandomSlugGenerator;
import com.anya.shortener.service.SlugGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Selects the slug strategy named by {@code app.slug.strategy}. */
@Configuration
public class SlugGeneratorConfig {

    private static final Logger log = LoggerFactory.getLogger(SlugGeneratorConfig.class);

    @Bean
    public SlugGenerator slugGenerator(StringRedisTemplate redis, AppProperties props) {
        String strategy = props.slug().strategy();
        SlugGenerator generator = switch (strategy.toLowerCase()) {
            case "counter" -> new CounterSlugGenerator(redis, props);
            case "random" -> new RandomSlugGenerator(props);
            default -> throw new IllegalArgumentException(
                    "Unknown app.slug.strategy '" + strategy + "'. Expected 'counter' or 'random'.");
        };
        log.info("Slug strategy: {} ({} chars, {} possible slugs)",
                generator.strategy(), props.slug().length(),
                com.anya.shortener.service.Base62Codec.capacity(props.slug().length()));
        return generator;
    }
}
