package io.github.itsmeck24.fineprint;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fineprint.collector")
public record CollectorProperties(
        boolean enabled,
        String userAgent,
        Duration minGap,
        Duration pollEvery,
        Duration detailRefresh,
        String titleInclude,
        String titleExclude,
        List<String> boards) {
}
