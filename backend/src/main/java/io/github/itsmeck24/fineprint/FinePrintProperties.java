package io.github.itsmeck24.fineprint;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fineprint")
public record FinePrintProperties(Collector collector, Worker worker, Sources sources, Jwt jwt,
                                  List<String> corsOrigins) {

    public record Collector(boolean enabled, String userAgent, Duration minGap, Duration pollEvery,
                            Duration detailRefresh, String titleInclude, String titleExclude, List<String> boards) {
    }

    public record Worker(boolean enabled, int threads, Duration lease, Duration idleSleep) {
    }

    public record Sources(String greenhouseBase, String leverBase) {
    }

    public record Jwt(String secret, Duration ttl) {
    }
}
