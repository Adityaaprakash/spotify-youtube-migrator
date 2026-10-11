package com.spotifyyoutube.migrator.youtube.infrastructure;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class YouTubeClientConfig {

    @Bean
    public RestClientCustomizer youTubeRestClientCustomizer(YouTubeProperties properties) {
        return restClientBuilder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
            factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
            restClientBuilder.requestFactory(factory);
        };
    }
}
