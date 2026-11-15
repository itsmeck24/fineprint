package io.github.itsmeck24.fineprint.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// compares the rules of two versions. a change in wording that leaves every rule the same is not a change.
public final class RuleDiff {

    private RuleDiff() {
    }

    public record Diff(List<Criterion> added, List<Criterion> removed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    public static Diff between(List<Criterion> before, List<Criterion> after) {
        Map<String, Criterion> b = byKey(before);
        Map<String, Criterion> a = byKey(after);
        List<Criterion> added = new ArrayList<>();
        List<Criterion> removed = new ArrayList<>();
        for (Map.Entry<String, Criterion> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                added.add(e.getValue());
            }
        }
        for (Map.Entry<String, Criterion> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }
        return new Diff(added, removed);
    }

    private static Map<String, Criterion> byKey(List<Criterion> list) {
        Map<String, Criterion> out = new LinkedHashMap<>();
        for (Criterion c : list) {
            out.putIfAbsent(c.key(), c);
        }
        return out;
    }
}
