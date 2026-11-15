package io.github.itsmeck24.fineprint.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// keyword and date rules. every criterion carries the exact sentence, title, or form question it came from.
public final class Rules {

    public static final String NAME = "rules";

    private Rules() {
    }

    static final Pattern GRAD = Pattern.compile(
            "(?i)\\b(graduat\\w*|conferr\\w*|conferral|grad (year|date)|class of|complete (your|their|a|the) degree)\\b");
    static final Pattern NOT_A_RULE = Pattern.compile(
            "(?i)\\bredact|\\bdate of birth|\\bomit\\b|compensation|salary|pay range|base pay|hourly rate");
    static final Pattern TITLE_GRAD = Pattern.compile("(?i)\\bgrad(uate|uates|uation|s)?\\b");

    static final Pattern DEGREE_CONTEXT = Pattern.compile(
            "(?i)\\bdegree|pursuing|enrolled|students?\\b|program\\b|working towards|\\bBS/MS|\\bMS/PhD");
    static final Pattern BACHELOR = Pattern.compile("(?i)\\bbachelor'?s?|\\bB\\.?S\\.?\\b|\\bB\\.?A\\.?\\b|\\bundergrad(uate)?");
    static final Pattern MASTER = Pattern.compile("(?i)\\bmaster'?s?\\b|\\bM\\.?S\\.?\\b|\\bgraduate (degree|student|program)");
    static final Pattern PHD = Pattern.compile("(?i)\\bph\\.?\\s?d\\b|\\bdoctora(l|te)|\\bgraduate (degree|student|program)");
    static final Pattern NO_GRAD_DEGREES = Pattern.compile(
            "(?i)\\bno\\s+(masters?|ph\\.?d|graduate)|undergrad(uate)?s?( students)? only|only (for |open to )?undergrad");
    static final Pattern TITLE_DEGREE = Pattern.compile("(?i)\\b(masters?|ph\\.?d|undergrad(uate)?)\\b");

    static final Pattern RETURN = Pattern.compile(
            "(?i)\\breturn(ing)? to (school|university|campus|your (studies|program|degree))"
                    + "|\\bcontinu(e|ing) (your )?(education|studies)"
                    + "|\\benrolled\\b.{0,80}\\b(throughout|during|after|following) the internship"
                    + "|\\bat least one (semester|quarter|term) (remaining|left)");
    static final Pattern FORM_RETURN = Pattern.compile("(?i)return to (the|your) (program|school|university|studies)");

    static final Pattern EEO = Pattern.compile(
            "(?i)without regard to|regardless of|equal (employment )?opportunity|discriminat|protected (veteran|status|characteristic)");
    static final Pattern CITIZEN = Pattern.compile(
            "(?i)(must|required to) be an? (u\\.?s\\.?|us|united states) citizen|(u\\.?s\\.?|us) citizenship (is )?required"
                    + "|requires? (u\\.?s\\.? )?citizenship|only (u\\.?s\\.? )?citizens|\\bu\\.s\\. persons?\\b.{0,40}\\b(required|only)");
    static final Pattern CLEARANCE = Pattern.compile("(?i)security clearance|\\bclearance\\b.{0,40}\\b(required|eligib|obtain)|\\bTS/SCI\\b");
    static final Pattern HEDGED = Pattern.compile(
            "(?i)\\bmay not\\b.{0,100}\\b(sponsor\\w*|visa|h-?1b)|\\bsponsor\\w*.{0,60}\\b(not guaranteed|case[- ]by[- ]case|may be (available|possible)|depend)");
    static final Pattern NO_SPONSOR = Pattern.compile(
            "(?i)\\b(not|unable to|cannot|can't|won't|will not|do not|does not|don't|no)\\b.{0,40}\\bsponsor"
                    + "|without (the need for |requiring |needing )?(current or future )?(visa |employer |immigration )?sponsorship"
                    + "|must not (now or in the future )?require (visa |immigration )?sponsorship");
    static final Pattern YES_SPONSOR = Pattern.compile(
            "(?i)\\b(we|will|do|can|able to|happy to)\\b.{0,30}\\bsponsor|\\bsponsorship (is |will be )?(available|provided|offered)"
                    + "|\\bvisa sponsorship\\b.{0,20}\\b(available|provided)");
    static final Pattern EXPORT = Pattern.compile("(?i)export control|export administration regulations|\\bITAR\\b|\\bEAR\\b");
    static final Pattern FORM_SPONSOR = Pattern.compile("(?i)sponsor");
    static final Pattern FORM_AUTH = Pattern.compile("(?i)authori[sz]ed to work|authori[sz]ation|permission to work|right to work|citizen");
    static final Pattern FORM_GRAD = Pattern.compile("(?i)graduat|complete your (degree|program)");
    static final Pattern FORM_DEGREE = Pattern.compile("(?i)\\bdegree\\b");
    static final Pattern CATCH_ALL = Pattern.compile(
            "(?i)\\b(other|n/?a|not applicable|before|earlier than|prior to|after|later than|or later|or earlier|already graduated)\\b");

    public static List<Criterion> extract(String title, String bodyText, String formJson, JsonMapper json) {
        List<Criterion> out = new ArrayList<>();
        fromTitle(title == null ? "" : title, out);
        for (String s : sentences(bodyText)) {
            fromSentence(s, out);
        }
        if (formJson != null) {
            fromForm(json.readTree(formJson), out);
        }
        return out;
    }

    static void fromTitle(String title, List<Criterion> out) {
        if (TITLE_GRAD.matcher(title).find()) {
            DateRanges.parse(title).ifPresent(r -> out.add(new Criterion(Criterion.GRAD, "title", title, r, null)));
        }
        if (TITLE_DEGREE.matcher(title).find()) {
            Set<String> levels = levels(title);
            if (!levels.isEmpty()) {
                out.add(new Criterion(Criterion.DEGREE, "title", title, null, "allowed=" + String.join(",", levels)));
            }
        }
    }

    static void fromSentence(String s, List<Criterion> out) {
        boolean notARule = NOT_A_RULE.matcher(s).find();
        if (!notARule && GRAD.matcher(s).find()) {
            DateRanges.parse(s).ifPresent(r -> out.add(new Criterion(Criterion.GRAD, "text", s, r, null)));
        }
        if (!notARule && DEGREE_CONTEXT.matcher(s).find()) {
            if (NO_GRAD_DEGREES.matcher(s).find()) {
                out.add(new Criterion(Criterion.DEGREE, "text", s, null, "excluded=master,phd"));
            } else {
                Set<String> levels = levels(s);
                if (!levels.isEmpty()) {
                    out.add(new Criterion(Criterion.DEGREE, "text", s, null, "allowed=" + String.join(",", levels)));
                }
            }
        }
        if (RETURN.matcher(s).find()) {
            out.add(new Criterion(Criterion.RETURN, "text", s, null, "required"));
        }
        authStance(s).ifPresent(v -> out.add(new Criterion(Criterion.AUTH, "text", s, null, v)));
    }

    // order matters: "we do not sponsor" also contains "do ... sponsor"
    static Optional<String> authStance(String s) {
        if (EEO.matcher(s).find()) {
            return Optional.empty();
        }
        if (CITIZEN.matcher(s).find()) {
            return Optional.of("citizenship_required");
        }
        if (CLEARANCE.matcher(s).find()) {
            return Optional.of("clearance_required");
        }
        if (HEDGED.matcher(s).find()) {
            return Optional.of("hedged");
        }
        if (NO_SPONSOR.matcher(s).find()) {
            return Optional.of("no_sponsorship");
        }
        if (YES_SPONSOR.matcher(s).find()) {
            return Optional.of("sponsors");
        }
        if (EXPORT.matcher(s).find()) {
            return Optional.of("export_control");
        }
        return Optional.empty();
    }

    static Set<String> levels(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (BACHELOR.matcher(s).find()) {
            out.add("bachelor");
        }
        if (MASTER.matcher(s).find()) {
            out.add("master");
        }
        if (PHD.matcher(s).find()) {
            out.add("phd");
        }
        return out;
    }

    static void fromForm(JsonNode form, List<Criterion> out) {
        for (JsonNode q : form) {
            String label = q.path("label").asString("");
            List<String> options = new ArrayList<>();
            for (JsonNode o : q.path("options")) {
                options.add(o.asString(""));
            }
            String quote = options.isEmpty() ? label : label + " [" + String.join(" | ", options) + "]";
            // upload fields like "Graduate Transcript" are not questions about dates
            boolean upload = label.toLowerCase().contains("transcript") || label.toLowerCase().contains("resume");
            if (upload) {
                continue;
            }
            if (FORM_GRAD.matcher(label).find()) {
                out.add(new Criterion(Criterion.GRAD, "form", quote, formRange(options).orElse(null), "asks"));
            } else if (FORM_RETURN.matcher(label).find()) {
                out.add(new Criterion(Criterion.RETURN, "form", quote, null, "asks"));
            } else if (FORM_SPONSOR.matcher(label).find()) {
                out.add(new Criterion(Criterion.AUTH, "form", quote, null, "asks_sponsorship"));
            } else if (FORM_AUTH.matcher(label).find()) {
                out.add(new Criterion(Criterion.AUTH, "form", quote, null, "asks_authorization"));
            } else if (FORM_DEGREE.matcher(label).find()) {
                out.add(new Criterion(Criterion.DEGREE, "form", quote, null, "asks"));
            }
        }
    }

    // the span covered by the specific options, ignoring catch-alls like "Other" or "Before December 2027"
    static Optional<DateRanges.Range> formRange(List<String> options) {
        List<DateRanges.Range> bounded = new ArrayList<>();
        for (String o : options) {
            if (CATCH_ALL.matcher(o).find() && !o.toLowerCase().contains("between")) {
                continue;
            }
            DateRanges.parse(o).filter(r -> r.start() != null && r.end() != null).ifPresent(bounded::add);
        }
        return bounded.isEmpty() ? Optional.empty() : Optional.of(DateRanges.Range.hull(bounded));
    }

    private static final Pattern SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z(\"'])");

    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            // keep abbreviations from ending a sentence
            String guarded = line.replaceAll("\\b(U)\\.(S)\\.", "$1\u0001$2\u0001")
                    .replaceAll("\\b(e)\\.(g)\\.", "$1\u0001$2\u0001")
                    .replaceAll("\\b(i)\\.(e)\\.", "$1\u0001$2\u0001")
                    .replaceAll("\\b(vs|etc|Ph\\.D|approx)\\.", "$1\u0001");
            for (String part : SPLIT.split(guarded)) {
                String s = part.replace('\u0001', '.').strip();
                if (s.length() > 3) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
