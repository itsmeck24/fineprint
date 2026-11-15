package io.github.itsmeck24.fineprint;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

// one postgres container for the whole test run. tests use their own board tokens and users so they do not collide.
public abstract class DbTest {

    @ServiceConnection
    protected static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    static {
        postgres.start();
    }
}
