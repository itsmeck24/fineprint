package io.github.itsmeck24.fineprint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostingTextTest {

    @Test
    void blockTagsBecomeLines() {
        String html = "<p>About us</p><ul><li>Graduating between Fall 2027 &amp; Summer 2028</li>"
                + "<li>BS/MS in CS</li></ul>Line one<br>Line&nbsp;two";
        assertThat(PostingText.htmlToText(html)).isEqualTo("""
                About us
                Graduating between Fall 2027 & Summer 2028
                BS/MS in CS
                Line one
                Line two""");
    }

    @Test
    void greenhouseContentIsDecodedOnceBeforeParsing() {
        // this is how the api sends it: the html itself is entity-encoded
        String content = "&lt;p&gt;We do sponsor visas &amp;amp; transfers.&lt;/p&gt;&lt;p&gt;Second&lt;/p&gt;";
        assertThat(PostingText.greenhouseContentToText(content)).isEqualTo("We do sponsor visas & transfers.\nSecond");
    }

    @Test
    void emptyInputGivesEmptyText() {
        assertThat(PostingText.htmlToText(null)).isEmpty();
        assertThat(PostingText.greenhouseContentToText(null)).isEmpty();
    }

    @Test
    void hashChangesWhenOnlyTheFormChanges() {
        String a = PostingText.hash("SWE Intern", "SF", "body", "[{\"label\":\"Grad date\",\"options\":[\"Fall 2027\"]}]");
        String b = PostingText.hash("SWE Intern", "SF", "body", "[{\"label\":\"Grad date\",\"options\":[\"Spring 2028\"]}]");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(PostingText.hash("SWE Intern", "SF", "body",
                "[{\"label\":\"Grad date\",\"options\":[\"Fall 2027\"]}]"));
    }

    @Test
    void fieldBoundariesMatter() {
        assertThat(PostingText.hash("ab", "c", "", null)).isNotEqualTo(PostingText.hash("a", "bc", "", null));
    }
}
