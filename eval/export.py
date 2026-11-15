# Export the latest version of each early-career tech posting on the split boards, for labeling and scoring.
# The text stays in eval/data/ (git-ignored). Labels only keep short quotes and a content hash.
#   python3 eval/export.py            writes eval/data/dev.jsonl and eval/data/test.jsonl
#   python3 eval/export.py --show test [start] [count]   prints the labeling view
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
TECH = re.compile(r"(?i)software|engineer|data|machine learning|\bml\b|scien|research|analyst|developer")

# much wider than the extraction rules on purpose, so labels are not limited to what the rules look for
WIDE = re.compile(
    r"(?i)graduat|degree|student|enrol|school|universit|college|academic|semester|quarter|term\b|bachelor|master|"
    r"ph\.?\s?d|doctora|\bb\.?s\b|\bm\.?s\b|\bba\b|undergrad|sponsor|visa|immigra|authori[sz]|citizen|permanent resident|"
    r"green card|clearance|export|itar|work permit|right to work|eligib|\b20[2-3]\d\b|spring|summer|fall|autumn|winter|"
    r"return|final year|class of|co-?op|opt\b|cpt\b|h-?1b")


def psql(sql):
    out = subprocess.run(["docker", "compose", "exec", "-T", "db", "psql", "-U", "fineprint", "-d", "fineprint",
                          "-tAc", sql], cwd=ROOT.parent, capture_output=True, text=True, check=True).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def export():
    splits = json.loads((ROOT / "splits.json").read_text())
    DATA.mkdir(exist_ok=True)
    for name in ("dev", "test"):
        boards = ",".join("'" + b + "'" for b in splits[name])
        rows = psql(f"""
            select row_to_json(t) from (
              select b.ats || ':' || b.token || ':' || p.external_id as key, b.ats || ':' || b.token as board,
                     v.id as version_id, v.content_hash, v.title, v.body_text, v.form
              from postings p join boards b on b.id = p.board_id
              join lateral (select * from posting_versions v where v.posting_id = p.id order by id desc limit 1) v on true
              where b.ats || ':' || b.token in ({boards}) and p.status <> 'closed'
              order by b.token, p.external_id) t""")
        rows = [r for r in rows if TECH.search(r["title"])]
        with open(DATA / f"{name}.jsonl", "w") as f:
            for r in rows:
                f.write(json.dumps(r) + "\n")
        print(name, len(rows), "postings")


def sentences(text):
    out = []
    for line in (text or "").split("\n"):
        line = re.sub(r"\b(U)\.(S)\.", "\\1\x01\\2\x01", line)
        for part in re.split(r"(?<=[.!?])\s+(?=[A-Z(\"'])", line):
            part = part.replace("\x01", ".").strip()
            if len(part) > 3:
                out.append(part)
    return out


def show(name, start, count):
    rows = [json.loads(l) for l in open(DATA / f"{name}.jsonl")]
    for i, r in enumerate(rows[start:start + count], start):
        print(f"=== {i} {r['key']} {r['content_hash'][:12]}")
        print("T:", r["title"])
        for s in sentences(r["body_text"]):
            if WIDE.search(s) and not re.search(r"(?i)without regard|equal (employment )?opportunity|regardless of", s):
                print("  -", s[:400])
        for q in r["form"] or []:
            # the same wide net as for sentences, applied to the question text
            if not WIDE.search(q["label"]) or re.search(r"(?i)pronoun|how did you|hear about", q["label"]):
                continue
            opts = (" [" + " | ".join(q["options"]) + "]") if q["options"] else ""
            print("  F:", q["label"][:200] + opts[:240])


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--show":
        show(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0, int(sys.argv[4]) if len(sys.argv) > 4 else 1000)
    else:
        export()
