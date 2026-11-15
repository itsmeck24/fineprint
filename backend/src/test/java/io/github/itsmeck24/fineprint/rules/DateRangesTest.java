package io.github.itsmeck24.fineprint.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// wording shaped like the gate postings, written by hand
class DateRangesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "You will graduate in fall 2027 or spring 2028 with a degree | 2027-09 | 2028-05",
            "graduation date between December 2027 and Summer 2028 (required) | 2027-12 | 2028-08",
            "graduating between Fall 2027 & Summer 2028 | 2027-09 | 2028-08",
            "BA/BS Graduation Year: Spring/Summer 2028. | 2028-01 | 2028-08",
            "BA/BS Graduation Year: Winter 2027 or Spring/Summer 2028. | 2027-12 | 2028-08",
            "Must be planning on graduating in 2027. | 2027-01 | 2027-12",
            "Must be graduating in 2027 or 2028. | 2027-01 | 2028-12",
            "Sept - Dec 2027 | 2027-09 | 2027-12",
            "December 2027 - August 2028 | 2027-12 | 2028-08",
            "Expected to graduate between Fall 2027 and Summer 2028 . | 2027-09 | 2028-08",
    })
    void boundedWindows(String text, String start, String end) {
        DateRanges.Range r = DateRanges.parse(text).orElseThrow();
        assertThat(r.start()).isEqualTo(YearMonth.parse(start));
        assertThat(r.end()).isEqualTo(YearMonth.parse(end));
    }

    @Test
    void byMonthIsOpenAtTheStart() {
        DateRanges.Range r = DateRanges.parse("pursuing a degree and graduating by June 2028").orElseThrow();
        assertThat(r.start()).isNull();
        assertThat(r.end()).isEqualTo(YearMonth.of(2028, 6));
        assertThat(r.contains(YearMonth.of(2027, 5))).isTrue();
    }

    @Test
    void orLaterIsOpenAtTheEnd() {
        DateRanges.Range r = DateRanges.parse("Summer 2028 or later").orElseThrow();
        assertThat(r.start()).isEqualTo(YearMonth.of(2028, 6));
        assertThat(r.end()).isNull();
    }

    @Test
    void afterStartsTheMonthAfter() {
        DateRanges.Range r = DateRanges.parse("graduating after December 2027").orElseThrow();
        assertThat(r.start()).isEqualTo(YearMonth.of(2028, 1));
    }

    @Test
    void startDatesAndInternshipYearsAreNotGraduationDates() {
        assertThat(DateRanges.parse("Product Design New Grad (2027 Start)")).isEmpty();
        assertThat(DateRanges.parse("apply for our 2027 internship")).isEmpty();
    }

    @Test
    void typoYearIsIgnored() {
        assertThat(DateRanges.parse("June 20230")).isEmpty();
    }

    @Test
    void internshipTermNeedsASeasonOrMonth() {
        assertThat(DateRanges.term("Data Science Intern [Summer 2027]").orElseThrow().end()).isEqualTo(YearMonth.of(2027, 8));
        assertThat(DateRanges.term("Software Engineering Intern (2027)")).isEmpty();
    }
}
