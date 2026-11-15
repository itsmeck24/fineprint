package io.github.itsmeck24.fineprint.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class RulesTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private List<Criterion> of(List<Criterion> all, String kind) {
        return all.stream().filter(c -> c.kind().equals(kind)).toList();
    }

    @Test
    void graduationWindowFromTextKeepsTheExactSentence() {
        String body = "About the team\nYou will graduate in fall 2027 or spring 2028 with a degree in CS. We move fast.";
        List<Criterion> grad = of(Rules.extract("SWE Intern", body, null, json), Criterion.GRAD);
        assertThat(grad).hasSize(1);
        assertThat(grad.get(0).quote()).isEqualTo("You will graduate in fall 2027 or spring 2028 with a degree in CS.");
        assertThat(body).contains(grad.get(0).quote());
        assertThat(grad.get(0).range().start()).isEqualTo(YearMonth.of(2027, 9));
    }

    @Test
    void resumePrivacyNoteIsNotAGraduationRule() {
        String body = "You may redact age, date of birth, and dates of attendance/graduation from your resume in 2027.";
        assertThat(of(Rules.extract("SWE", body, null, json), Criterion.GRAD)).isEmpty();
    }

    @Test
    void graduationFromTitleOnlyWhenTheTitleTalksAboutGraduating() {
        assertThat(of(Rules.extract("Backend Software Engineer - University Graduate 2027", "", null, json),
                Criterion.GRAD)).hasSize(1);
        assertThat(of(Rules.extract("Software Engineering Intern (2027)", "", null, json), Criterion.GRAD)).isEmpty();
    }

    @Test
    void degreeExclusionAndLevels() {
        List<Criterion> d = of(Rules.extract("Intern", "Undergraduate students only. No Masters/PhD", null, json),
                Criterion.DEGREE);
        assertThat(d).extracting(Criterion::value).contains("excluded=master,phd");

        List<Criterion> d2 = of(Rules.extract("Intern", "Currently working towards a B.S., M.S., or Ph.D. degree", null, json),
                Criterion.DEGREE);
        assertThat(d2).extracting(Criterion::value).containsExactly("allowed=bachelor,master,phd");
    }

    @Test
    void paySentenceIsNotADegreeRule() {
        assertThat(of(Rules.extract("Intern", "Compensation will vary by level of education (Master's vs. Ph.D.).",
                null, json), Criterion.DEGREE)).isEmpty();
    }

    @Test
    void sponsorshipStances() {
        assertThat(Rules.authStance("We do sponsor and take over sponsorship of employment visas for this role."))
                .contains("sponsors");
        assertThat(Rules.authStance("We are unable to sponsor visas for this position.")).contains("no_sponsorship");
        assertThat(Rules.authStance("Candidates must be authorized to work without the need for visa sponsorship."))
                .contains("no_sponsorship");
        assertThat(Rules.authStance("The Company may not be able to employ candidates who need certain U.S. visa "
                + "categories, or support future H-1B sponsorship at this time.")).contains("hedged");
        assertThat(Rules.authStance("Active US Security clearance, or eligibility to obtain one")).contains("clearance_required");
        assertThat(Rules.authStance("Applicants must be a U.S. citizen.")).contains("citizenship_required");
        assertThat(Rules.authStance("This role may require access to information under U.S. export control laws."))
                .contains("export_control");
    }

    @Test
    void equalOpportunityTextIsIgnored() {
        assertThat(Rules.authStance("We hire without regard to race, citizenship, or immigration status.")).isEmpty();
    }

    @Test
    void wordsThatOnlyContainVisaDoNotCount() {
        assertThat(Rules.authStance("Nous vous conseillons d'envisager de la signaler.")).isEmpty();
    }

    @Test
    void returnToSchool() {
        assertThat(of(Rules.extract("Intern", "You must be returning to school after the internship.", null, json),
                Criterion.RETURN)).hasSize(1);
        assertThat(of(Rules.extract("Intern", "Enrolled at an accredited university throughout the internship.", null, json),
                Criterion.RETURN)).hasSize(1);
    }

    @Test
    void formQuestionsAreScreeningNotRules() {
        String form = """
                [{"label":"When do you expect to graduate?","required":true,
                  "options":["Sept - Dec 2027","Jan - April 2028","May - Aug 2028","Other"]},
                 {"label":"Will you now or in the future require sponsorship?","required":true,"options":["Yes","No"]},
                 {"label":"Are you legally authorized to work in the US?","required":true,"options":["Yes","No"]},
                 {"label":"Will you return to the program upon completion of internship?","required":true,"options":["Yes","No"]},
                 {"label":"Resume","required":true,"options":[]}]""";
        List<Criterion> all = Rules.extract("Intern", "", form, json);
        Criterion grad = of(all, Criterion.GRAD).get(0);
        assertThat(grad.source()).isEqualTo("form");
        assertThat(grad.range().start()).isEqualTo(YearMonth.of(2027, 9));
        assertThat(grad.range().end()).isEqualTo(YearMonth.of(2028, 8));
        assertThat(of(all, Criterion.AUTH)).extracting(Criterion::value)
                .containsExactly("asks_sponsorship", "asks_authorization");
        assertThat(of(all, Criterion.RETURN)).hasSize(1);
    }

    @Test
    void transcriptUploadsAreNotGraduationQuestions() {
        String form = "[{\"label\":\"Undergraduate Transcript\",\"required\":true,\"options\":[]},"
                + "{\"label\":\"Graduate Transcript\",\"required\":false,\"options\":[]}]";
        assertThat(Rules.extract("Intern", "", form, json)).isEmpty();
    }

    @Test
    void catchAllOptionsDoNotWidenTheFormWindow() {
        assertThat(Rules.formRange(List.of("December 2027 - August 2028", "Before December 2027", "September 2028 or later")))
                .get().satisfies(r -> {
                    assertThat(r.start()).isEqualTo(YearMonth.of(2027, 12));
                    assertThat(r.end()).isEqualTo(YearMonth.of(2028, 8));
                });
    }

    @Test
    void sentenceSplitKeepsAbbreviations() {
        assertThat(Rules.sentences("Must be a U.S. Citizen. Second one.")).containsExactly("Must be a U.S. Citizen.", "Second one.");
        assertThat(Rules.sentences("Pay depends on Master's vs. Ph.D. level.")).hasSize(1);
    }
}
