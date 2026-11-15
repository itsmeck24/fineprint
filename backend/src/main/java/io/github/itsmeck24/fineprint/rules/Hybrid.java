package io.github.itsmeck24.fineprint.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// the model picks the sentence, code reads the dates out of that sentence, and the rules cover the rest:
// - form and title criteria always come from the rules
// - a text field the model answered (with a quote that checks out) replaces the rules' version of that field
// - if the rules can read a date range from the model's quote, that range wins over the model's own dates
public final class Hybrid {

    public static final String NAME = "hybrid";

    private Hybrid() {
    }

    public static List<Criterion> combine(List<Criterion> rules, List<Criterion> llm) {
        List<Criterion> out = new ArrayList<>();
        for (Criterion c : rules) {
            if (!c.source().equals("text") || llm.stream().noneMatch(m -> m.kind().equals(c.kind()) && m.source().equals("text"))) {
                out.add(c);
            }
        }
        for (Criterion m : llm) {
            if (!m.source().equals("text")) {
                continue;
            }
            if (m.kind().equals(Criterion.GRAD)) {
                Optional<DateRanges.Range> fromQuote = DateRanges.parse(m.quote());
                out.add(new Criterion(m.kind(), m.source(), m.quote(), fromQuote.orElse(m.range()), m.value()));
            } else {
                out.add(m);
            }
        }
        return out;
    }
}
