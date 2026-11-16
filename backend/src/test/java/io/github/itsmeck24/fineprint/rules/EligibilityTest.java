package io.github.itsmeck24.fineprint.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class EligibilityTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Eligibility.Profile may2027ms = new Eligibility.Profile(YearMonth.of(2027, 5), "master");

    private Eligibility.Rule rule(String title, String body, String form, String kind) {
        return Eligibility.card(may2027ms, title, Rules.extract(title, body, form, json)).stream()
                .filter(r -> r.kind().equals(kind)).findFirst().orElseThrow();
    }

    @Test
    void windowThatExcludesTheUserConflicts() {
        Eligibility.Rule r = rule("SWE Intern", "You will graduate in fall 2027 or spring 2028.", null, Criterion.GRAD);
        assertThat(r.label()).isEqualTo(Eligibility.CONFLICTS);
        assertThat(r.reasons().get(0).quote()).isEqualTo("You will graduate in fall 2027 or spring 2028.");
    }

    // the verkada case from the gate: text says fine, the form has no option for a may 2027 graduate
    @Test
    void textMatchesButFormDisagrees() {
        String form = "[{\"label\":\"Expected graduation\",\"required\":true,"
                + "\"options\":[\"Sept - Dec 2027\",\"Jan - April 2028\",\"Other\"]}]";
        Eligibility.Rule r = rule("Backend Intern 2027", "Pursuing a degree and graduating by June 2028", form, Criterion.GRAD);
        assertThat(r.label()).isEqualTo(Eligibility.MATCHES);
        assertThat(r.sourcesDisagree()).isTrue();
        assertThat(r.reasons()).extracting(Eligibility.Reason::note).anyMatch(n -> n.startsWith("no listed option covers May 2027"));
    }

    @Test
    void formOnlyIsScreenedAndNothingIsUnknown() {
        String form = "[{\"label\":\"When do you expect to complete your degree?\",\"required\":true,\"options\":[]}]";
        assertThat(rule("Intern", "No dates here.", form, Criterion.GRAD).label()).isEqualTo(Eligibility.SCREENED);
        assertThat(rule("Intern", "No dates here.", null, Criterion.GRAD).label()).isEqualTo(Eligibility.UNKNOWN);
    }

    @Test
    void silenceOnSponsorshipIsUnknownNeverNoRestriction() {
        assertThat(rule("Intern", "We build things.", null, Criterion.AUTH).label()).isEqualTo(Eligibility.UNKNOWN);
        String form = "[{\"label\":\"Will you require sponsorship?\",\"required\":true,\"options\":[\"Yes\",\"No\"]}]";
        assertThat(rule("Intern", "We build things.", form, Criterion.AUTH).label()).isEqualTo(Eligibility.SCREENED);
    }

    @Test
    void strongestSponsorshipStanceWins() {
        Eligibility.Rule r = rule("Intern", "We do sponsor visas for this role. Applicants must be a U.S. citizen.", null,
                Criterion.AUTH);
        assertThat(r.label()).isEqualTo("citizenship_required");
        assertThat(r.sourcesDisagree()).isTrue();
    }

    @Test
    void degreeRules() {
        assertThat(rule("Intern", "Undergraduate students only. No Masters/PhD", null, Criterion.DEGREE).label())
                .isEqualTo(Eligibility.CONFLICTS);
        assertThat(rule("Machine Learning Intern (Masters)", "", null, Criterion.DEGREE).label())
                .isEqualTo(Eligibility.MATCHES);
        assertThat(rule("Intern", "We like people.", null, Criterion.DEGREE).label()).isEqualTo(Eligibility.UNKNOWN);
    }

    @Test
    void returnToSchoolNeedsTheInternshipTerm() {
        String body = "Enrolled at an accredited university throughout the internship.";
        assertThat(rule("Data Science Intern [Summer 2027]", body, null, Criterion.RETURN).label())
                .isEqualTo(Eligibility.CONFLICTS);
        assertThat(rule("Data Science Intern", body, null, Criterion.RETURN).label()).isEqualTo(Eligibility.UNKNOWN);
        Eligibility.Profile later = new Eligibility.Profile(YearMonth.of(2028, 5), "master");
        List<Eligibility.Rule> card = Eligibility.card(later, "Data Science Intern [Summer 2027]",
                Rules.extract("Data Science Intern [Summer 2027]", body, null, json));
        assertThat(card.get(2).label()).isEqualTo(Eligibility.MATCHES);
    }
}
