package io.github.itsmeck24.fineprint.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WatchControllerTest {

    @Test
    void boardParsing() {
        assertThat(WatchController.parseBoard("greenhouse:verkada")).get()
                .satisfies(b -> assertThat(b).containsExactly("greenhouse", "verkada"));
        assertThat(WatchController.parseBoard("https://job-boards.greenhouse.io/verkada")).get()
                .satisfies(b -> assertThat(b).containsExactly("greenhouse", "verkada"));
        assertThat(WatchController.parseBoard("https://jobs.lever.co/palantir/")).get()
                .satisfies(b -> assertThat(b).containsExactly("lever", "palantir"));
        assertThat(WatchController.parseBoard("workday:nvidia")).isEmpty();
        assertThat(WatchController.parseBoard("greenhouse:../etc")).isEmpty();
        assertThat(WatchController.parseBoard("https://example.com/verkada")).isEmpty();
    }
}
