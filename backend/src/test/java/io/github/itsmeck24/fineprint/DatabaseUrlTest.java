package io.github.itsmeck24.fineprint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @AfterEach
    void clear() {
        System.clearProperty("spring.datasource.url");
        System.clearProperty("spring.datasource.username");
        System.clearProperty("spring.datasource.password");
    }

    @Test
    void hostStyleUrlBecomesJdbcSettings() {
        FinePrintApplication.useDatabaseUrl("postgresql://fp_user:s3cret@dpg-abc-a:5432/fineprint");
        assertThat(System.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-abc-a:5432/fineprint");
        assertThat(System.getProperty("spring.datasource.username")).isEqualTo("fp_user");
        assertThat(System.getProperty("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    void missingPortDefaultsAndBlankIsIgnored() {
        FinePrintApplication.useDatabaseUrl("postgres://u:p@db.internal/fp");
        assertThat(System.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db.internal:5432/fp");
        clear();
        FinePrintApplication.useDatabaseUrl("");
        assertThat(System.getProperty("spring.datasource.url")).isNull();
    }
}
