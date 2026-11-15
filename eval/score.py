# Scores the predictions written by EvalRunnerTest against eval/labels/<split>.jsonl.
#   python3 eval/score.py test            scores every predictions file for that split, writes results/eval-<split>-*.json
#
# How labels were written: from the posting title, every sentence of the description that mentions education,
# dates, or work authorization, and the application form questions (eval/export.py --show). The test labels were
# committed before any extractor was scored on the test split. Conventions:
# - grad_window: only a stated graduation or degree-completion date. Internship dates and start dates are not
#   graduation windows. Seasons: spring 01-05, summer 06-08, fall 09-12, winter 12 to 01, a bare year 01-12.
#   A preference ("ideally graduating ...") is kept with soft=true. When the text gives two windows, the second is
#   grad_window_alt and the card should show the stricter one and flag that the sources disagree.
# - degree: who the posting lets in, for bachelor, master and phd separately. A required bachelor's degree also
#   lets in master and phd holders. A level named in the title limits the role to that level.
# - return_to_school: only when the posting says the candidate must be enrolled again after the internship.
# - work_auth: the stance the text takes, or unknown when it says nothing. authorization_required (must already
#   be allowed to work, nothing said about sponsorship) was added as a category while labeling. The rules have no
#   such category; the model prompt does.
# - form: whether the application form asks for a graduation date, sponsorship, work authorization, or return.
import json
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PROFILE = "2027-05"
VARIANTS = ["title_only", "rules_text_only", "rules", "llm", "hybrid"]
AUTH_ORDER = ["citizenship_required", "clearance_required", "no_sponsorship", "hedged", "sponsors",
              "authorization_required", "export_control"]


def ym(s):
    return None if s is None else tuple(int(x) for x in s.split("-"))


def contains(start, end, month):
    m = ym(month)
    return (start is None or ym(start) <= m) and (end is None or m <= ym(end))


def hull(windows):
    starts = [w[0] for w in windows]
    ends = [w[1] for w in windows]
    s = None if any(x is None for x in starts) else min(starts, key=ym)
    e = None if any(x is None for x in ends) else max(ends, key=ym)
    return (s, e)


def truth_window(lab):
    ws = [lab[k] for k in ("grad_window", "grad_window_alt") if lab.get(k)]
    return hull([(w["start"], w["end"]) for w in ws]) if ws else None


def pred_window(criteria):
    ws = [(c["start"], c["end"]) for c in criteria
          if c["kind"] == "grad_window" and c["source"] != "form" and c["has_range"]]
    return hull(ws) if ws else None


def pred_degree(criteria):
    ds = [c for c in criteria if c["kind"] == "degree" and c["source"] != "form"]
    if not ds:
        return None
    out = {}
    for level in ("bachelor", "master", "phd"):
        ok = True
        for c in ds:
            kind, levels = c["value"].split("=", 1)
            levels = levels.split(",")
            ok &= (level in levels) if kind == "allowed" else (level not in levels)
        out[level] = ok
    return out


def pred_stance(criteria):
    vals = [c["value"] for c in criteria if c["kind"] == "work_auth" and c["source"] != "form"]
    vals = [v for v in vals if v in AUTH_ORDER]
    return min(vals, key=AUTH_ORDER.index) if vals else "unknown"


def same_sentence(a, b):
    if not a or not b:
        return False
    a, b = " ".join(a.split()).lower(), " ".join(b.split()).lower()
    return a in b or b in a


def prf(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else None
    r = tp / (tp + fn) if tp + fn else None
    f = 2 * p * r / (p + r) if p and r else None
    return {"precision": rnd(p), "recall": rnd(r), "f1": rnd(f), "tp": tp, "fp": fp, "fn": fn}


def rnd(x):
    return None if x is None else round(x, 3)


def truth_card(lab, degree):
    w = truth_window(lab)
    if w:
        grad = "matches" if contains(w[0], w[1], PROFILE) else "conflicts"
    else:
        grad = "screened" if lab["form"]["asks_grad"] else "unknown"
    d = lab["degree"]
    deg = ("matches" if d[degree] else "conflicts") if d else "unknown"
    return {"grad_window": grad, "degree": deg}


def score_variant(labels, preds, name):
    g = {"found": [0, 0, 0], "exact": [0, 0, 0], "quote": 0}
    d = {"found": [0, 0, 0], "exact": [0, 0, 0], "quote": 0}
    rt = [0, 0, 0]
    auth_correct = 0
    auth_stated = [0, 0, 0]
    card = {"false_match": 0, "false_conflict": 0, "missed_conflict": 0, "correct": 0, "total": 0,
            "truth_conflicts": 0, "truth_matches": 0}
    unknown_kept = [0, 0]
    form_flags = {k: [0, 0, 0] for k in ("asks_grad", "asks_sponsorship", "asks_authorization", "asks_return")}
    disagree = 0
    for lab in labels:
        v = preds[lab["key"]]["variants"][name]
        cs = v["criteria"]
        # graduation window
        tw, pw = truth_window(lab), pred_window(cs)
        tally(g["found"], tw is not None, pw is not None, True)
        tally(g["exact"], tw is not None, pw is not None, tw == pw)
        if tw and pw and tw == pw:
            q = [c["quote"] for c in cs if c["kind"] == "grad_window" and c["source"] != "form"]
            g["quote"] += any(same_sentence(x, lab["grad_window"]["quote"]) or
                              (lab.get("grad_window_alt") and same_sentence(x, lab["grad_window_alt"]["quote"])) for x in q)
        # degree
        td = None if lab["degree"] is None else {k: lab["degree"][k] for k in ("bachelor", "master", "phd")}
        pd = pred_degree(cs)
        tally(d["found"], td is not None, pd is not None, True)
        tally(d["exact"], td is not None, pd is not None, td == pd)
        if td and pd and td == pd:
            q = [c["quote"] for c in cs if c["kind"] == "degree" and c["source"] != "form"]
            d["quote"] += any(same_sentence(x, lab["degree"]["quote"]) for x in q)
        # return to school
        tally(rt, lab["return_to_school"] is not None,
              any(c["kind"] == "return_to_school" and c["source"] != "form" for c in cs), True)
        # work authorization stance
        ts, ps = lab["work_auth"]["stance"], pred_stance(cs)
        auth_correct += ts == ps
        tally(auth_stated, ts != "unknown", ps != "unknown", ts == ps)
        # form questions
        found = {"asks_grad": any(c["kind"] == "grad_window" and c["source"] == "form" for c in cs),
                 "asks_sponsorship": any(c["value"] == "asks_sponsorship" for c in cs),
                 "asks_authorization": any(c["value"] == "asks_authorization" for c in cs),
                 "asks_return": any(c["kind"] == "return_to_school" and c["source"] == "form" for c in cs)}
        for k in form_flags:
            tally(form_flags[k], lab["form"][k], found[k], True)
        # card labels for a may 2027 graduate at each degree level
        for degree in ("bachelor", "master", "phd"):
            t = truth_card(lab, degree)
            p = v["cards"][degree]
            for kind in ("grad_window", "degree"):
                tl, pl = t[kind], p[kind]["label"]
                card["total"] += 1
                card["truth_conflicts"] += tl == "conflicts"
                card["truth_matches"] += tl == "matches"
                card["false_match"] += tl == "conflicts" and pl == "matches"
                card["missed_conflict"] += tl == "conflicts" and pl != "conflicts"
                card["false_conflict"] += tl != "conflicts" and pl == "conflicts"
                card["correct"] += (tl == pl) or (tl in ("unknown", "screened") and pl in ("unknown", "screened"))
                if tl in ("unknown", "screened"):
                    unknown_kept[0] += 1
                    unknown_kept[1] += pl in ("unknown", "screened")
        disagree += any(r["disagree"] for r in v["cards"]["master"].values())
    n = len(labels)
    return {
        "grad_window_found": prf(*g["found"]),
        "grad_window_exact": prf(*g["exact"]),
        "grad_window_quote_is_labeled_sentence": rnd(g["quote"] / g["exact"][0]) if g["exact"][0] else None,
        "degree_found": prf(*d["found"]),
        "degree_exact": prf(*d["exact"]),
        "degree_quote_is_labeled_sentence": rnd(d["quote"] / d["exact"][0]) if d["exact"][0] else None,
        "return_to_school": prf(*rt),
        "work_auth_stance_accuracy": rnd(auth_correct / n),
        "work_auth_stated": prf(*auth_stated),
        "form_questions": {k: prf(*v) for k, v in form_flags.items()},
        "card_may2027": {**card, "false_match_rate_on_conflicts": rnd(card["false_match"] / card["truth_conflicts"])
                         if card["truth_conflicts"] else None, "accuracy": rnd(card["correct"] / card["total"])},
        "no_rule_stays_unknown": rnd(unknown_kept[1] / unknown_kept[0]) if unknown_kept[0] else None,
        "postings_with_disagreement_flag": disagree,
    }


def tally(t, truth, pred, correct):
    # t = [tp, fp, fn]. a prediction with the wrong value counts as both a false positive and a false negative
    if truth and pred and correct:
        t[0] += 1
    else:
        if pred:
            t[1] += 1
        if truth:
            t[2] += 1


def form_value(labels, preds):
    # how many cards go from "unknown" with text only to something informed once the form is read too
    out = {}
    for kind in ("grad_window", "work_auth"):
        before = after = 0
        for lab in labels:
            vt = preds[lab["key"]]["variants"]["rules_text_only"]["cards"]["master"]
            vf = preds[lab["key"]]["variants"]["rules"]["cards"]["master"]
            if vt[kind]["label"] == "unknown":
                before += 1
                after += vf[kind]["label"] != "unknown"
        out[kind] = {"unknown_with_text_only": before, "informed_after_reading_form": after}
    return out


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT.parent, capture_output=True, text=True).stdout.strip()


def main():
    split = sys.argv[1] if len(sys.argv) > 1 else "dev"
    labels = [json.loads(l) for l in open(ROOT / "labels" / f"{split}.jsonl") if l.strip()]
    files = sorted((ROOT / "data" / "predictions").glob(f"{split}-*.jsonl"))
    runs = {}
    for f in files:
        preds = {json.loads(l)["key"]: json.loads(l) for l in open(f)}
        millis = [p["llm_millis"] for p in preds.values()]
        dropped = sum(p["llm_dropped_quotes"] for p in preds.values())
        runs[f.stem] = {
            "variants": {v: score_variant(labels, preds, v) for v in VARIANTS},
            "form_value": form_value(labels, preds),
            "llm_latency_seconds": {"median": round(statistics.median(millis) / 1000, 1),
                                    "p90": round(sorted(millis)[int(0.9 * (len(millis) - 1))] / 1000, 1)},
            "llm_quotes_rejected_not_in_posting_or_not_supporting_field": dropped,
        }
    spread = {}
    if len(runs) > 1:
        for v in ("llm", "hybrid"):
            for metric in ("grad_window_exact", "degree_exact"):
                f1s = [r["variants"][v][metric]["f1"] for r in runs.values() if r["variants"][v][metric]["f1"] is not None]
                if len(f1s) > 1:
                    spread[f"{v}.{metric}.f1"] = {"mean": round(statistics.mean(f1s), 3), "sd": round(statistics.stdev(f1s), 3), "runs": len(f1s)}
            accs = [r["variants"][v]["work_auth_stance_accuracy"] for r in runs.values()]
            if len(accs) > 1:
                spread[f"{v}.work_auth_stance_accuracy"] = {"mean": round(statistics.mean(accs), 3), "sd": round(statistics.stdev(accs), 3), "runs": len(accs)}
    result = {
        "command": f"python3 eval/score.py {split}",
        "predictions": [f.name for f in files],
        "split": split,
        "postings": len(labels),
        "boards": sorted({l["key"].rsplit(":", 1)[0] for l in labels}),
        "profile": "graduation 2027-05, scored as bachelor, master and phd",
        "commit": git("rev-parse", "--short", "HEAD"),
        "labels_last_changed_in": git("log", "-1", "--format=%h", "--", f"eval/labels/{split}.jsonl"),
        "scored_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "runs": runs,
        "spread_over_runs": spread,
    }
    out = ROOT.parent / "results"
    out.mkdir(exist_ok=True)
    path = out / f"eval-{split}-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(result, indent=2) + "\n")
    print(path.name)
    for run, r in runs.items():
        print(f"\n{run}  (llm median {r['llm_latency_seconds']['median']} s, rejected quotes {r["llm_quotes_rejected_not_in_posting_or_not_supporting_field"]})")
        print(f"{'variant':16} {'grad F1':>8} {'deg F1':>7} {'ret F1':>7} {'auth acc':>9} {'false match':>12} {'false confl':>12} {'card acc':>9}")
        for v in VARIANTS:
            s = r["variants"][v]
            c = s["card_may2027"]
            print(f"{v:16} {fmt(s['grad_window_exact']['f1']):>8} {fmt(s['degree_exact']['f1']):>7} {fmt(s['return_to_school']['f1']):>7} "
                  f"{fmt(s['work_auth_stance_accuracy']):>9} {c['false_match']:>5}/{c['truth_conflicts']:<6} {c['false_conflict']:>12} {fmt(c['accuracy']):>9}")
        print("form value:", r["form_value"])
    if spread:
        print("\nspread:", spread)
    return path


def fmt(x):
    return "-" if x is None else f"{x:.2f}"


if __name__ == "__main__":
    main()
