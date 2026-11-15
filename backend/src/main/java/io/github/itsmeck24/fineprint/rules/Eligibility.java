package io.github.itsmeck24.fineprint.rules;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// turns criteria into one label per rule for one user. labels are computed when read, never stored,
// so changing the profile changes the card without touching any posting data.
public final class Eligibility {

    private Eligibility() {
    }

    public record Profile(YearMonth gradMonth, String degreeLevel) {
    }

    public record Reason(String source, String quote, String note) {
    }

    public record Rule(String kind, String label, boolean sourcesDisagree, List<Reason> reasons) {
    }

    public static final String MATCHES = "matches";
    public static final String CONFLICTS = "conflicts";
    public static final String SCREENED = "screened";
    public static final String UNKNOWN = "unknown";

    // strongest stance first. work authorization is shown as what the posting says, not matched to the user,
    // because the app does not store anyone's visa status.
    static final List<String> AUTH_ORDER = List.of("citizenship_required", "clearance_required", "no_sponsorship",
            "hedged", "sponsors", "authorization_required", "export_control");

    public static List<Rule> card(Profile p, String title, List<Criterion> criteria) {
        return List.of(grad(p, criteria), degree(p, criteria), returnToSchool(p, title, criteria), auth(criteria));
    }

    static Rule grad(Profile p, List<Criterion> all) {
        List<Criterion> rules = of(all, Criterion.GRAD, false);
        List<Criterion> form = of(all, Criterion.GRAD, true);
        List<Reason> reasons = new ArrayList<>();
        int fits = 0;
        for (Criterion c : rules) {
            boolean in = c.range().contains(p.gradMonth());
            fits += in ? 1 : 0;
            reasons.add(new Reason(c.source(), c.quote(), (in ? "includes " : "excludes ") + p.gradMonth()
                    + " (window " + c.range() + ")"));
        }
        Optional<Boolean> formFits = Optional.empty();
        for (Criterion c : form) {
            if (c.range() == null) {
                reasons.add(new Reason("form", c.quote(), "the form asks for your graduation date"));
                continue;
            }
            boolean in = c.range().contains(p.gradMonth());
            formFits = Optional.of(in);
            reasons.add(new Reason("form", c.quote(), in ? "a listed option covers " + p.gradMonth()
                    : "only a catch-all option fits " + p.gradMonth()));
        }
        if (rules.isEmpty()) {
            return new Rule(Criterion.GRAD, form.isEmpty() ? UNKNOWN : SCREENED, false, reasons);
        }
        // mixed text means the stricter answer wins
        String label = fits == rules.size() ? MATCHES : CONFLICTS;
        boolean disagree = fits > 0 && fits < rules.size()
                || formFits.isPresent() && formFits.get() != label.equals(MATCHES);
        return new Rule(Criterion.GRAD, label, disagree, reasons);
    }

    static Rule degree(Profile p, List<Criterion> all) {
        List<Criterion> rules = of(all, Criterion.DEGREE, false);
        List<Reason> reasons = new ArrayList<>();
        int ok = 0;
        for (Criterion c : rules) {
            boolean fits = degreeFits(c.value(), p.degreeLevel());
            ok += fits ? 1 : 0;
            reasons.add(new Reason(c.source(), c.quote(), (fits ? "fits " : "does not fit ") + p.degreeLevel()));
        }
        for (Criterion c : of(all, Criterion.DEGREE, true)) {
            reasons.add(new Reason("form", c.quote(), "the form asks about your degree"));
        }
        if (rules.isEmpty()) {
            return new Rule(Criterion.DEGREE, reasons.isEmpty() ? UNKNOWN : SCREENED, false, reasons);
        }
        return new Rule(Criterion.DEGREE, ok == rules.size() ? MATCHES : CONFLICTS, ok > 0 && ok < rules.size(), reasons);
    }

    static boolean degreeFits(String value, String level) {
        String[] parts = value.split("=", 2);
        Set<String> levels = Set.of(parts[1].split(","));
        return parts[0].equals("allowed") ? levels.contains(level) : !levels.contains(level);
    }

    // a return-to-school rule only conflicts when we know when the internship ends
    static Rule returnToSchool(Profile p, String title, List<Criterion> all) {
        List<Criterion> rules = of(all, Criterion.RETURN, false);
        List<Reason> reasons = new ArrayList<>();
        Optional<DateRanges.Range> term = DateRanges.term(title);
        for (Criterion c : rules) {
            String note = term.map(t -> "internship ends " + t.end()).orElse("internship dates not found in the title");
            reasons.add(new Reason(c.source(), c.quote(), note));
        }
        for (Criterion c : of(all, Criterion.RETURN, true)) {
            reasons.add(new Reason("form", c.quote(), "the form asks whether you return to school"));
        }
        if (rules.isEmpty()) {
            return new Rule(Criterion.RETURN, reasons.isEmpty() ? UNKNOWN : SCREENED, false, reasons);
        }
        if (term.isEmpty()) {
            return new Rule(Criterion.RETURN, UNKNOWN, false, reasons);
        }
        return new Rule(Criterion.RETURN, p.gradMonth().isAfter(term.get().end()) ? MATCHES : CONFLICTS, false, reasons);
    }

    static Rule auth(List<Criterion> all) {
        List<Criterion> rules = of(all, Criterion.AUTH, false);
        List<Reason> reasons = new ArrayList<>();
        String best = null;
        boolean yes = false;
        boolean no = false;
        for (Criterion c : rules) {
            reasons.add(new Reason(c.source(), c.quote(), c.value()));
            // a value missing from the list ranks last, never first
            if (!AUTH_ORDER.contains(c.value())) {
                continue;
            }
            if (best == null || AUTH_ORDER.indexOf(c.value()) < AUTH_ORDER.indexOf(best)) {
                best = c.value();
            }
            yes |= c.value().equals("sponsors");
            no |= c.value().equals("no_sponsorship") || c.value().equals("citizenship_required");
        }
        for (Criterion c : of(all, Criterion.AUTH, true)) {
            reasons.add(new Reason("form", c.quote(), c.value().equals("asks_sponsorship")
                    ? "the form asks if you need sponsorship" : "the form asks about work authorization"));
        }
        // silence is never "no restriction"
        String label = best != null ? best : reasons.isEmpty() ? UNKNOWN : SCREENED;
        return new Rule(Criterion.AUTH, label, yes && no, reasons);
    }

    private static List<Criterion> of(List<Criterion> all, String kind, boolean form) {
        return all.stream().filter(c -> c.kind().equals(kind) && c.source().equals("form") == form).toList();
    }
}
