package io.github.itsmeck24.fineprint.rules;

// one rule found in a posting. quote is copied exactly from the title, text, or form.
public record Criterion(String kind, String source, String quote, DateRanges.Range range, String value) {

    public static final String GRAD = "grad_window";
    public static final String DEGREE = "degree";
    public static final String RETURN = "return_to_school";
    public static final String AUTH = "work_auth";

    // what counts as "the same rule" when comparing two versions
    public String key() {
        return kind + "|" + source + "|" + (range == null ? "" : range) + "|" + (value == null ? "" : value);
    }
}
