package io.github.itsmeck24.fineprint;

import java.net.URI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class FinePrintApplication {

    public static void main(String[] args) {
        useDatabaseUrl(System.getenv("DATABASE_URL"));
        SpringApplication.run(FinePrintApplication.class, args);
    }

    // hosts like render hand out postgresql://user:pass@host:port/db. spring wants a jdbc url and separate credentials.
    // an explicit SPRING_DATASOURCE_URL still wins.
    static void useDatabaseUrl(String url) {
        if (url == null || url.isBlank() || System.getenv("SPRING_DATASOURCE_URL") != null) {
            return;
        }
        URI u = URI.create(url);
        String[] userInfo = u.getUserInfo() == null ? new String[0] : u.getUserInfo().split(":", 2);
        int port = u.getPort() == -1 ? 5432 : u.getPort();
        System.setProperty("spring.datasource.url", "jdbc:postgresql://" + u.getHost() + ":" + port + u.getPath());
        if (userInfo.length > 0) {
            System.setProperty("spring.datasource.username", userInfo[0]);
        }
        if (userInfo.length > 1) {
            System.setProperty("spring.datasource.password", userInfo[1]);
        }
    }
}
