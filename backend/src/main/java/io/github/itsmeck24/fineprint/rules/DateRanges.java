package io.github.itsmeck24.fineprint.rules;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// turns graduation wording into month ranges. null start or end means open on that side.
public final class DateRanges {

    private DateRanges() {
    }

    public record Range(YearMonth start, YearMonth end) {
        public boolean contains(YearMonth m) {
            return (start == null || !m.isBefore(start)) && (end == null || !m.isAfter(end));
        }

        static Range hull(List<Range> ranges) {
            YearMonth s = null;
            YearMonth e = null;
            boolean openStart = false;
            boolean openEnd = false;
            for (Range r : ranges) {
                if (r.start == null) {
                    openStart = true;
                } else if (s == null || r.start.isBefore(s)) {
                    s = r.start;
                }
                if (r.end == null) {
                    openEnd = true;
                } else if (e == null || r.end.isAfter(e)) {
                    e = r.end;
                }
            }
            return new Range(openStart ? null : s, openEnd ? null : e);
        }

        @Override
        public String toString() {
            return (start == null ? "..." : start) + " to " + (end == null ? "..." : end);
        }
    }

    private static final String WORD = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
            + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|spring|summer|fall|autumn|winter";

    // "Fall 2027", "Sept - Dec 2027", "Spring/Summer 2028", "2027". not "2027 start" and not "2027 intern..."
    private static final Pattern MENTION = Pattern.compile(
            "(?i)\\b(?:(" + WORD + ")\\.?(?:\\s*(-|–|to|through|/)\\s*(" + WORD + ")\\.?)?\\s*,?\\s+)?(20\\d\\d)\\b"
                    + "(?!\\s*(?:start|intern|summer|program))");

    private static final Pattern BY_BEFORE = Pattern.compile(
            "(?i)\\b(by|before|no later than|prior to|on or before)\\s*(the end of\\s*)?$");
    private static final Pattern AFTER = Pattern.compile("(?i)\\b(after|later than)\\s*$");
    private static final Pattern OR_LATER = Pattern.compile("(?i)^\\s*(or|and)\\s+(later|after)\\b");

    record Mention(Range range, int start, int end) {
    }

    static List<Mention> mentions(String text) {
        List<Mention> out = new ArrayList<>();
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            int year = Integer.parseInt(m.group(4));
            Range first = m.group(1) == null ? new Range(YearMonth.of(year, 1), YearMonth.of(year, 12))
                    : word(m.group(1), year);
            Range r = first;
            if (m.group(3) != null) {
                Range second = word(m.group(3), year);
                r = m.group(2).equals("/") ? Range.hull(List.of(first, second)) : new Range(first.start, second.end);
            }
            out.add(new Mention(r, m.start(), m.end()));
        }
        return out;
    }

    // one sentence or one form option to a single range
    public static Optional<Range> parse(String text) {
        List<Mention> ms = mentions(text);
        if (ms.isEmpty()) {
            return Optional.empty();
        }
        Mention first = ms.get(0);
        Mention last = ms.get(ms.size() - 1);
        String before = text.substring(0, first.start());
        String after = text.substring(last.end());
        if (BY_BEFORE.matcher(before).find()) {
            return Optional.of(new Range(null, Range.hull(ranges(ms)).end()));
        }
        if (AFTER.matcher(before).find()) {
            return Optional.of(new Range(last.range().end().plusMonths(1), null));
        }
        if (OR_LATER.matcher(after).find()) {
            return Optional.of(new Range(first.range().start(), null));
        }
        return Optional.of(Range.hull(ranges(ms)));
    }

    // internship term from a title like "[Summer 2027]". needs a season or month, a bare year is too vague
    private static final Pattern TERM = Pattern.compile("(?i)\\b(" + WORD + ")\\.?\\s*,?\\s+(20\\d\\d)\\b");

    private static final Pattern TERM_YEAR_FIRST = Pattern.compile("(?i)\\b(20\\d\\d)\\s+(" + WORD + ")\\b");

    public static Optional<Range> term(String title) {
        String t = title == null ? "" : title;
        Matcher m = TERM.matcher(t);
        if (m.find()) {
            return Optional.of(word(m.group(1), Integer.parseInt(m.group(2))));
        }
        // "2027 Summer Intern"
        Matcher y = TERM_YEAR_FIRST.matcher(t);
        if (y.find()) {
            return Optional.of(word(y.group(2), Integer.parseInt(y.group(1))));
        }
        return Optional.empty();
    }

    private static List<Range> ranges(List<Mention> ms) {
        return ms.stream().map(Mention::range).toList();
    }

    static Range word(String w, int year) {
        String k = w.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "spring" -> new Range(YearMonth.of(year, 1), YearMonth.of(year, 5));
            case "summer" -> new Range(YearMonth.of(year, 6), YearMonth.of(year, 8));
            case "fall", "autumn" -> new Range(YearMonth.of(year, 9), YearMonth.of(year, 12));
            // "Winter 2027" grads finish in December 2027, the term runs into January
            case "winter" -> new Range(YearMonth.of(year, 12), YearMonth.of(year + 1, 1));
            default -> {
                YearMonth m = YearMonth.of(year, month(k));
                yield new Range(m, m);
            }
        };
    }

    private static int month(String k) {
        String p = k.substring(0, 3);
        return switch (p) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            case "dec" -> 12;
            default -> throw new IllegalArgumentException(k);
        };
    }
}
