import { useEffect, useState } from "react";
import type { Rule } from "../src/api";
import { Card } from "../src/Card";
import { atsName, companyName } from "../src/names";
import { Diagram } from "./Diagram";
import { Hero } from "./Hero";

const REPO = "https://github.com/itsmeck24/fineprint";
const LIVE_APP = "https://fineprint-app.onrender.com";

type DemoPosting = { company: string; ats: string; title: string; url: string; hasForm: boolean; cards: Record<string, Rule[]> };
type Demo = { snapshot: string; months: string[]; degrees: string[]; postings: DemoPosting[] };
type Variant = {
  grad_f1: number | null; degree_f1: number | null; return_f1: number | null; card_accuracy: number;
  false_matches: number; truth_conflicts: number; false_conflicts: number;
};
type EvalSummary = {
  file: string; note: string | null; postings: number; companies: number; commit: string; runs: number;
  variants: Record<string, Variant>; llm_median_seconds: number;
  form_value: Record<string, { unknown_with_text_only: number; informed_after_reading_form: number }>;
  spread: Record<string, { mean: number; sd: number; runs: number }>;
};
type CrashRun = {
  file: string; commit: string; kills: number; kills_with_work_in_flight: number; expected_changes: number;
  recorded_changes: number; notifications: number; emails_sent: number; resent_after_kill: number;
  duplicate_emails: number; note: string;
};
type Results = {
  heldout: EvalSummary | null; after_fixes: EvalSummary | null; crash_runs: CrashRun[];
  change_detection: { file: string; cases: Record<string, { cases: number; correct: number }> } | null;
  throughput: { file: string; workload: string; results: { threads: number; postings_per_second_mean: number; postings_per_second_sd: number }[] } | null;
};

const MONTHS: Record<string, string> = { "2026-12": "Dec 2026", "2027-05": "May 2027", "2027-12": "Dec 2027", "2028-05": "May 2028" };
const DEGREES: Record<string, string> = { bachelor: "Bachelor's", master: "Master's", phd: "PhD" };
const VARIANTS: [string, string][] = [
  ["title_only", "Title only"], ["rules", "Rules"], ["llm", "Local model"], ["hybrid", "Model picks the sentence, rules read it"],
];
const CASES: Record<string, string> = {
  wording_new_sentence: "a new opening sentence", wording_benefit_line: "a new benefit line",
  wording_pay_change: "a changed pay figure", sponsorship_withdrawn: "an added \"unable to sponsor\"",
  graduation_year_moved: "a graduation year moved by one",
};

const f2 = (x: number | null | undefined) => (x === null || x === undefined ? "none" : x.toFixed(2));
const src = (file: string) => `${REPO}/blob/main/results/${file}`;

function Section({ n, id, title, children }: { n: number; id: string; title: string; children: React.ReactNode }) {
  return (
    <section id={id} className="clause">
      <div className="clause-n">&sect;{n}</div>
      <div className="clause-body">
        <h2>{title}</h2>
        {children}
      </div>
    </section>
  );
}

function Fn({ n, file }: { n: number; file: string }) {
  return <a className="fn" href={src(file)} title={file}>[{n}]</a>;
}

export function Site() {
  const [demo, setDemo] = useState<Demo | null>(null);
  const [results, setResults] = useState<Results | null>(null);
  const [month, setMonth] = useState("2027-05");
  const [degree, setDegree] = useState("master");
  const [pick, setPick] = useState(0);

  useEffect(() => {
    fetch("./demo.json").then((r) => r.json()).then(setDemo);
    fetch("./results.json").then((r) => r.json()).then(setResults);
  }, []);

  // sections render after the data loads, so a link like #evidence has to be followed again
  useEffect(() => {
    if (results && location.hash) document.querySelector(location.hash)?.scrollIntoView();
  }, [results]);

  const posting = demo?.postings[pick];
  const h = results?.heldout;
  const silent = h?.form_value.work_auth;
  const clean = results?.crash_runs[results.crash_runs.length - 1];
  const footnotes: string[] = [];
  const fn = (file: string) => {
    if (!footnotes.includes(file)) footnotes.push(file);
    return <Fn n={footnotes.indexOf(file) + 1} file={file} />;
  };

  return (
    <div className="site">
      <header className="top">
        <a className="mark" href="#">FinePrint</a>
        <nav>
          <a href="#demo">Try it</a>
          <a href="#how">How it works</a>
          <a href="#evidence">Evidence</a>
          <a href={REPO}>Code</a>
          <a className="pill" href={LIVE_APP}>Open the app</a>
        </nav>
      </header>

      <Hero />

      {h && silent && (
        <Section n={1} id="why" title="The rules live in the form, not the title">
          <div className="findings">
            <div><strong>{silent.unknown_with_text_only}<span>/{h.postings}</span></strong>
              <p>held-out postings say nothing about sponsorship in the description {fn(h.file)}</p></div>
            <div><strong>{silent.informed_after_reading_form}</strong>
              <p>of those ask about it on the application form instead</p></div>
            <div><strong>{h.variants.rules.false_matches}<span>/{h.variants.rules.truth_conflicts}</span></strong>
              <p>times the rules said "matches you" when a stated rule ruled the student out</p></div>
          </div>
          <p className="prose">
            Silence is never read as "no restriction". A rule nobody states stays <span className="tag plain">not stated</span>,
            a question the form asks shows as <span className="tag warn">asked on the form</span>, and every other answer
            carries its quote.
          </p>
        </Section>
      )}

      <Section n={2} id="demo" title="Try it on twelve real postings">
        <p className="prose muted">
          The same rules the app runs, on {demo ? demo.snapshot : "a snapshot of public postings"}. Postings change, so
          open the original before you apply.
        </p>
        <div className="profile">
          <label>Graduating
            <select value={month} onChange={(e) => setMonth(e.target.value)}>
              {demo?.months.map((m) => <option key={m} value={m}>{MONTHS[m] ?? m}</option>)}
            </select>
          </label>
          <div className="segmented" role="group" aria-label="Degree">
            {demo?.degrees.map((d) => (
              <button key={d} className={d === degree ? "on" : ""} onClick={() => setDegree(d)}>{DEGREES[d]}</button>
            ))}
          </div>
        </div>
        <label className="picker-small">Posting
          <select value={pick} onChange={(e) => setPick(Number(e.target.value))}>
            {demo?.postings.map((p, i) => <option key={p.url} value={i}>{companyName(p.company)}: {p.title}</option>)}
          </select>
        </label>
        <div className="demo-grid">
          <ol className="picker">
            {demo?.postings.map((p, i) => (
              <li key={p.url}>
                <button className={i === pick ? "on" : ""} onClick={() => setPick(i)}>
                  <span className="co">{String(i + 1).padStart(2, "0")} &middot; {companyName(p.company)}</span>
                  <span className="ti"><span>{p.title}</span></span>
                </button>
              </li>
            ))}
          </ol>
          <div className="demo-card">
            {posting && (
              <>
                <h3>{posting.title}</h3>
                <p className="meta">
                  {companyName(posting.company)} &middot; {atsName(posting.ats)}
                  {posting.hasForm ? " · form read" : " · no form available"} &middot; <a href={posting.url}>original</a>
                </p>
                <Card rules={posting.cards[`${month}|${degree}`]} />
              </>
            )}
          </div>
        </div>
      </Section>

      <Section n={3} id="how" title="How it works">
        <Diagram />
        <div className="subclauses">
          <div><h3><span>3.1</span> Collect politely</h3>
            <p>A worker polls the public Greenhouse and Lever job board APIs once an hour, one request per host every
              two seconds, and logs every request. Postgres is the job queue: workers claim jobs with <code>FOR UPDATE
              SKIP LOCKED</code> and a lease, so a dead worker's job is picked up by another.</p></div>
          <div><h3><span>3.2</span> Keep every version</h3>
            <p>A posting gets a new version only when its title, text or form changes. It is called closed only after two
              404s an hour apart while it is missing from its board; server errors mark it unreachable and retry.</p></div>
          <div><h3><span>3.3</span> Read the rules, quote the source</h3>
            <p>Rules read dates, degree levels and sponsorship wording from the text and the form. A local model can pick
              the sentence instead, but its quote must appear word for word in the posting or it is dropped.</p></div>
          <div><h3><span>3.4</span> Tell you once</h3>
            <p>A change compares the rules of two versions, so new wording alone is not a change. The change and its
              emails are written in one transaction, and the sender retries with an idempotency key.</p></div>
        </div>
      </Section>

      {h && results && (
        <Section n={4} id="evidence" title="Evidence">
          <div className="exhibit">
            <p className="exhibit-label">Exhibit A &middot; extraction on {h.postings} postings from {h.companies} companies the rules were never written against</p>
            <div className="table-wrap">
              <table>
                <thead><tr><th></th><th>Graduation F1</th><th>Degree F1</th><th>Card accuracy</th><th>False "matches you"</th></tr></thead>
                <tbody>
                  {VARIANTS.map(([k, name]) => {
                    const v = h.variants[k];
                    return v && (
                      <tr key={k} className={k === "rules" ? "lead-row" : ""}>
                        <td>{name}</td><td>{f2(v.grad_f1)}</td><td>{f2(v.degree_f1)}</td><td>{f2(v.card_accuracy)}</td>
                        <td>{v.false_matches} of {v.truth_conflicts}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="exhibit-note">
              Labels were written from each posting's text and form and committed before anything was scored {fn(h.file)}.
              Local model: qwen3:14b on a laptop, median {h.llm_median_seconds} s per posting, {h.runs} runs, spread at most{" "}
              {Math.max(...Object.values(h.spread).map((s) => s.sd)).toFixed(3)}.
              {results.after_fixes && (
                <> After fixing the rule bugs these results exposed, the rules reach {f2(results.after_fixes.variants.rules.card_accuracy)} card
                  accuracy with {results.after_fixes.variants.rules.false_matches} false matches on the same postings, which is no longer
                  a held-out number {fn(results.after_fixes.file)}.</>
              )}
            </p>
          </div>

          {results.change_detection && (
            <div className="exhibit">
              <p className="exhibit-label">Exhibit B &middot; change detection on planted edits</p>
              <ul className="ledger">
                {Object.entries(results.change_detection.cases).map(([k, c]) => (
                  <li key={k}>
                    <span className="count">{c.correct}/{c.cases}</span>
                    <span>{CASES[k] ?? k} {k.startsWith("wording") ? "ignored" : "caught"}</span>
                  </li>
                ))}
              </ul>
              <p className="exhibit-note">
                Each edit was planted into a copy of a test posting and run through the code the worker uses. The missed
                year moves are postings whose window the rules never read: change detection sees only what extraction
                sees {fn(results.change_detection.file)}.
              </p>
            </div>
          )}

          <div className="exhibit">
            <p className="exhibit-label">Exhibit C &middot; kill -9, repeatedly. Every run is kept, failures included.</p>
            <div className="table-wrap">
              <table className="crash">
                <thead><tr><th>Commit</th><th>Kills</th><th>Changes</th><th>Emails sent</th><th>Duplicates</th><th>What it showed</th></tr></thead>
                <tbody>
                  {results.crash_runs.map((c) => {
                    const ok = c.recorded_changes === c.expected_changes && c.emails_sent === c.notifications;
                    return (
                      <tr key={c.file} className={ok ? "" : "short"}>
                        <td><a href={src(c.file)}>{c.commit}</a></td>
                        <td data-label="Kills">{c.kills} <span className="muted">({c.kills_with_work_in_flight} mid-job)</span></td>
                        <td data-label="Changes">{c.recorded_changes}/{c.expected_changes}</td>
                        <td data-label="Emails sent">{c.emails_sent}/{c.notifications}</td>
                        <td data-label="Duplicates">{c.duplicate_emails}</td>
                        <td className="note">{c.note}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {clean && (
              <p className="exhibit-note">
                Two worker processes run against a fake job board and a fake email provider while a killer stops one at a
                random moment every one to four seconds. In the last run {clean.resent_after_kill} emails reached the
                provider twice because a worker died between sending and recording; the idempotency key dropped every
                repeat.
              </p>
            )}
          </div>

          {results.throughput && (
            <div className="exhibit">
              <p className="exhibit-label">Exhibit D &middot; throughput of one worker process</p>
              <Bars rows={results.throughput.results} />
              <p className="exhibit-note">{results.throughput.workload}. Against real boards the limit is the two-second
                gap per host, not the queue {fn(results.throughput.file)}.</p>
            </div>
          )}
        </Section>
      )}

      <Section n={5} id="use" title="Use it">
        <div className="use">
          <div>
            <h3>The live app</h3>
            <p>Sign up with a graduation month and a degree, paste a Greenhouse or Lever link, and watch companies. It runs
              on a free server that sleeps when nobody uses it, so the first visit takes about a minute to wake up.</p>
            <a className="button" href={LIVE_APP}>Open the app</a>
          </div>
          <div>
            <h3>On your own machine</h3>
            <pre><code>git clone {REPO}{"\n"}cd fineprint{"\n"}docker compose up -d --build{"\n"}open http://localhost:8081</code></pre>
            <p className="muted small">Java 21, Spring Boot 4.1, PostgreSQL 16, React and TypeScript, a Chrome extension,
              JUnit with Testcontainers, Playwright, Ollama for the local model.</p>
          </div>
        </div>
      </Section>

      {footnotes.length > 0 && (
        <section className="footnotes">
          <ol>
            {footnotes.map((f) => <li key={f}><a href={src(f)}>results/{f}</a></li>)}
          </ol>
        </section>
      )}

      <footer>
        <span>FinePrint &middot; Charith Kutikuppala &middot; MIT license</span>
        <span>Posting text belongs to the employers. This page quotes short excerpts from a dated snapshot.</span>
      </footer>
    </div>
  );
}

function Bars({ rows }: { rows: { threads: number; postings_per_second_mean: number; postings_per_second_sd: number }[] }) {
  const max = Math.max(...rows.map((r) => r.postings_per_second_mean));
  return (
    <div className="bars">
      {rows.map((r) => (
        <div key={r.threads} className="bar-row">
          <span className="bar-label">{r.threads} thread{r.threads > 1 ? "s" : ""}</span>
          <span className="bar-track"><span className="bar" style={{ width: `${(r.postings_per_second_mean / max) * 100}%` }} /></span>
          <span className="bar-value">{r.postings_per_second_mean} /s</span>
        </div>
      ))}
    </div>
  );
}
