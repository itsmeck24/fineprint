package io.github.itsmeck24.fineprint;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fineprint")
public record FinePrintProperties(Collector collector, Worker worker, Sources sources, Jwt jwt, Mail mail,
                                  List<String> corsOrigins) {

    public record Collector(boolean enabled, String userAgent, Duration minGap, Duration pollEvery,
                            Duration detailRefresh, Duration closeAfter, String titleInclude, String titleExclude, List<String> boards) {
    }

    public record Worker(boolean enabled, int threads, Duration lease, Duration idleSleep) {
    }

    public record Sources(String greenhouseBase, String leverBase) {
    }

    public record Jwt(String secret, Duration ttl) {
    }

    // provider is log, smtp or resend. unknown-after is how old a half-sent message may be before a retry
    // could no longer be deduplicated by the provider (resend keeps idempotency keys for 24 hours).
    public record Mail(String provider, String from, String smtpHost, int smtpPort, String resendBase,
                       String resendApiKey, Duration lease, Duration unknownAfter, String appUrl) {
    }
}
