# Builds web/site/public/results.json from the files in results/, so every number on the site comes from a saved run.
#   python3 eval/site_data.py
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "results"
OUT = ROOT / "web" / "site" / "public" / "results.json"

def load(name):
    return json.loads((RESULTS / name).read_text())


def without_commit(r):
    # the page shows results, not build history; the commit stays in the results file itself
    return {k: v for k, v in r.items() if k != "commit"}


def eval_summary(path):
    r = load(path.name)
    run = r["runs"].get("test-t0.0-s1") or next(iter(r["runs"].values()))
    variants = {}
    for v, s in run["variants"].items():
        c = s["card_may2027"]
        variants[v] = {
            "grad_f1": s["grad_window_exact"]["f1"],
            "degree_f1": s["degree_exact"]["f1"],
            "return_f1": s["return_to_school"]["f1"],
            "stance_stated_f1": s["work_auth_stated"]["f1"],
            "card_accuracy": c["accuracy"],
            "false_matches": c["false_match"],
            "truth_conflicts": c["truth_conflicts"],
            "false_conflicts": c["false_conflict"],
        }
    return {
        "file": path.name,
        "note": r.get("note"),
        "postings": r["postings"],
        "companies": len(r["boards"]),
        "variants": variants,
        "form_value": run["form_value"],
        "llm_median_seconds": run["llm_latency_seconds"]["median"],
        "spread": r.get("spread_over_runs", {}),
        "runs": len(r["runs"]),
    }


def main():
    tests = sorted(RESULTS.glob("eval-test-*.json"))
    heldout = [p for p in tests if load(p.name).get("note") is None]
    postfix = [p for p in tests if load(p.name).get("note")]
    dev = [p for p in sorted(RESULTS.glob("eval-dev-*.json")) if load(p.name).get("note")]
    crash = []
    for p in sorted(RESULTS.glob("crash-*.json")):
        c = load(p.name)
        crash.append({
            "file": p.name,
            "kills": c["kills"],
            "kills_with_work_in_flight": c["kills_with_work_in_flight"],
            "expected_changes": c["expected_change_events"],
            "recorded_changes": c["recorded_change_events"],
            "notifications": c["expected_notifications"],
            "emails_sent": c["notification_status"]["sent"],
            "resent_after_kill": c["provider_keys_requested_more_than_once"],
            "duplicate_emails": c["duplicate_deliveries"],
        })
    changes = [without_commit(load(p.name)) | {"file": p.name} for p in sorted(RESULTS.glob("change-detection-*.json"))]
    throughput = [without_commit(load(p.name)) | {"file": p.name} for p in sorted(RESULTS.glob("throughput-*.json"))]
    out = {
        "heldout": eval_summary(heldout[-1]) if heldout else None,
        "after_fixes": eval_summary(postfix[-1]) if postfix else None,
        "dev_after_fixes": eval_summary(dev[-1]) if dev else None,
        "crash_runs": crash,
        "change_detection": changes[-1] if changes else None,
        "throughput": throughput[-1] if throughput else None,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=1) + "\n")
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
