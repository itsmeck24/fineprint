import { useEffect, useState } from "react";
import type { Rule } from "../src/api";
import { Card } from "../src/Card";
import { Diagram } from "./Diagram";

const REPO = "https://github.com/itsmeck24/fineprint";

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
  recorded_changes: number; notifications: number; emails_sent: number; resent_after_kill: number; duplicate_emails: number; note: string;
};
type Results = {
  heldout: EvalSummary | null; after_fixes: EvalSummary | null; crash_runs: CrashRun[];
  change_detection: { file: string; cases: Record<string, { cases: number; correct: number }> } | null;
  throughput: { file: string; workload: string; results: { threads: number; postings_per_second_mean: number; postings_per_second_sd: number }[] } | null;
};

const MONTH_NAMES: Record<string, string> = { "2026-12": "Dec 2026", "2027-05": "May 2027", "2027-12": "Dec 2027", "2028-05": "May 2028" };
const DEGREE_NAMES: Record<string, string> = { bachelor: "Bachelor's", master: "Master's", phd: "PhD" };
const VARIANT_NAMES: [string, string][] = [
  ["title_only", "Title only"], ["rules", "Rules"], ["llm", "Local model"], ["hybrid", "Model picks the sentence, rules read it"],
];
const COMPANY: Record<string, string> = {
  verkada: "Verkada", doordashusa: "DoorDash", cloudflare: "Cloudflare", pinterest: "Pinterest", duolingo: "Duolingo",
  scaleai: "Scale AI", waymo: "Waymo", palantir: "Palantir", mongodb: "MongoDB", affirm: "Affirm", roblox: "Roblox", nuro: "Nuro",
};

function f2(x: number | null | undefined) {
  return x === null || x === undefined ? "none found" : x.toFixed(2);
}

function fileLink(file: string) {
  return <a href={`${REPO}/blob/main/results/${file}`}>{file}</a>;
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

  // sections render after the data loads, so a link like #results has to be followed again
  useEffect(() => {
    if (results && location.hash) {
      document.querySelector(location.hash)?.scrollIntoView();
    }
  }, [results]);

  const posting = demo?.postings[pick];
  const h = results?.heldout;
  const silent = h?.form_value.work_auth;

  return (
    <div className="site">
      <header className="top">
        <span className="mark">FinePrint</span>
        <nav>
          <a href="#demo">Demo</a>
          <a href="#how">How it works</a>
          <a href="#results">Results</a>
          <a href="#reliability">Reliability</a>
          <a href={REPO}>Code</a>
        </nav>
      </header>

      <section className="hero">
        <h1>The eligibility rules in a job posting, with the sentence each one comes from.</h1>
        <p className="lead">
          FinePrint reads a posting's title, description and application form, and checks four things against your
          graduation date and degree: the graduation window, the degree level, whether you must return to school, and
          what it says about visa sponsorship. It keeps every version of a saved posting and emails you once when a rule
          changes or the posting closes.
        </p>
        <p className="actions">
          <a className="button" href="#demo">Try it on 12 real postings</a>
          <a className="button secondary" href={REPO}>Read the code</a>
        </p>
      </section>

      <section id="demo" className="demo">
        <h2>Try it</h2>
        <p className="muted">
          Cards produced by the same rules the app runs, on {demo ? demo.snapshot : "a snapshot of public postings"}.
          Postings change, so open the original to check.
        </p>
        <div className="profile">
          <label>Graduating
            <select value={month} onChange={(e) => setMonth(e.target.value)}>
              {demo?.months.map((m) => <option key={m} value={m}>{MONTH_NAMES[m] ?? m}</option>)}
            </select>
          </label>
          <div className="segmented" role="group" aria-label="Degree">
            {demo?.degrees.map((d) => (
              <button key={d} className={d === degree ? "on" : ""} onClick={() => setDegree(d)}>{DEGREE_NAMES[d]}</button>
            ))}
          </div>
        </div>
        <label className="picker-small">Posting
          <select value={pick} onChange={(e) => setPick(Number(e.target.value))}>
            {demo?.postings.map((p, i) => <option key={p.url} value={i}>{COMPANY[p.company] ?? p.company}: {p.title}</option>)}
          </select>
        </label>
        <div className="demo-grid">
          <ul className="picker">
            {demo?.postings.map((p, i) => (
              <li key={p.url}>
                <button className={i === pick ? "on" : ""} onClick={() => setPick(i)}>
                  <span className="co">{COMPANY[p.company] ?? p.company}</span>
                  <span className="ti">{p.title}</span>
                </button>
              </li>
            ))}
          </ul>
          <div className="demo-card">
            {posting && (
              <>
                <h3>{posting.title}</h3>
                <p className="muted small">
                  {COMPANY[posting.company] ?? posting.company} on {posting.ats === "greenhouse" ? "Greenhouse" : "Lever"}
                  {posting.hasForm ? ", application form read" : ", no form available"} | <a href={posting.url}>open the posting</a>
                </p>
                <Card rules={posting.cards[`${month}|${degree}`]} />
              </>
            )}
          </div>
        </div>
      </section>

      {h && silent && (
        <section className="facts">
          <h2>Why the form matters</h2>
          <div className="stats">
            <div><strong>{silent.unknown_with_text_only} of {h.postings}</strong><span>held-out postings say nothing about sponsorship in the description</span></div>
            <div><strong>{silent.informed_after_reading_form}</strong><span>of those ask about it on the application form</span></div>
            <div><strong>{h.variants.rules.false_matches} of {h.variants.rules.truth_conflicts}</strong><span>times the rules said "matches you" when a stated rule ruled the student out</span></div>
          </div>
          <p>
            A title like "Software Engineer Intern 2027" rarely says that you must graduate between December 2027 and
            August 2028, or that the form counts STEM OPT as sponsorship. FinePrint reads both and quotes them, and it
            never treats silence as "no restriction": a rule that is not stated stays "not stated", and a question the
            form asks shows as "asked on the form".
          </p>
        </section>
      )}

      <section id="how" className="how">
        <h2>How it works</h2>
        <Diagram />
        <div className="cols">
          <div>
            <h3>Collect</h3>
            <p>A worker polls the public Greenhouse and Lever job board APIs once an hour, one request per host every two
              seconds, and logs every request. Postgres is the job queue: workers claim jobs with
              <code> FOR UPDATE SKIP LOCKED</code> and a lease, so a dead worker's job is picked up by another.</p>
          </div>
          <div>
            <h3>Keep versions</h3>
            <p>A posting gets a new version only when its title, text or form changes. A posting is called closed only
              after two 404s an hour apart while it is missing from its board; server errors mark it unreachable and retry.</p>
          </div>
          <div>
            <h3>Read the rules</h3>
            <p>Rules read dates, degree levels and sponsorship wording from the text and the form. A local model through
              Ollama can pick the sentence instead; its quote must appear word for word in the posting or it is dropped.</p>
          </div>
          <div>
            <h3>Tell you once</h3>
            <p>A change compares the rules of two versions, so new wording alone is not a change. The change and its
              emails are written in one transaction, and the sender retries with an idempotency key, so a crash after
              sending does not mean a second email.</p>
          </div>
        </div>
      </section>

      {h && (
        <section id="results" className="results">
          <h2>Measured on postings it had not seen</h2>
          <p className="muted">
            {h.postings} postings from {h.companies} companies that were not used to write the rules. Every posting was
            labeled from its own text and form, and the labels were committed before anything was scored.
            Cards are scored for a student graduating in May 2027 at each degree level.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th></th><th>Graduation window F1</th><th>Degree F1</th><th>Card accuracy</th><th>Said "matches you" when a rule ruled them out</th></tr>
              </thead>
              <tbody>
                {VARIANT_NAMES.map(([k, name]) => {
                  const v = h.variants[k];
                  return v && (
                    <tr key={k}>
                      <td>{name}</td><td>{f2(v.grad_f1)}</td><td>{f2(v.degree_f1)}</td><td>{f2(v.card_accuracy)}</td>
                      <td>{v.false_matches} of {v.truth_conflicts}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <p className="small muted">
            Local model: qwen3:14b on a laptop, median {h.llm_median_seconds} s per posting, {h.runs} runs with a spread of
            at most {Math.max(...Object.values(h.spread).map((s) => s.sd)).toFixed(3)} in any score. Source: {fileLink(h.file)}.
          </p>
          {results?.after_fixes && (
            <p className="small">
              Reading these results turned up rule bugs (a negated "sponsorship is not available", degree minimums). After
              fixing them, the rules reach a card accuracy of {f2(results.after_fixes.variants.rules.card_accuracy)} with{" "}
              {results.after_fixes.variants.rules.false_matches} false matches on the same postings. That number is no
              longer from unseen data, so it is shown apart. Source: {fileLink(results.after_fixes.file)}.
            </p>
          )}
          {results?.change_detection && (
            <>
              <h3>Change detection</h3>
              <p className="muted small">Edits planted into copies of the test postings, checked with the same code the worker runs.</p>
              <ul className="plain">
                {Object.entries(results.change_detection.cases).map(([k, c]) => (
                  <li key={k}><strong>{c.correct} of {c.cases}</strong> {k.replaceAll("_", " ")}{k.startsWith("wording") ? " ignored" : " caught"}</li>
                ))}
              </ul>
              <p className="small muted">The missed graduation edits are postings whose window the rules did not read in the first place: change detection only sees what extraction sees. Source: {fileLink(results.change_detection.file)}.</p>
            </>
          )}
        </section>
      )}

      {results && (
        <section id="reliability" className="reliability">
          <h2>Kill -9, repeatedly</h2>
          <p>
            A harness runs two real worker processes against a fake job board and a fake email provider, changes every
            posting's rules again and again, and kills a random worker at a random moment, then counts everything from
            the database. Every run is kept, including the ones that failed.
          </p>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Commit</th><th>Kills (mid-job)</th><th>Changes recorded</th><th>Emails sent</th><th>Resent after a kill</th><th>Duplicate emails</th><th>What it showed</th></tr>
              </thead>
              <tbody>
                {results.crash_runs.map((c) => (
                  <tr key={c.file}>
                    <td><a href={`${REPO}/blob/main/results/${c.file}`}>{c.commit}</a></td>
                    <td>{c.kills} ({c.kills_with_work_in_flight})</td>
                    <td>{c.recorded_changes} of {c.expected_changes}</td>
                    <td>{c.emails_sent} of {c.notifications}</td>
                    <td>{c.resent_after_kill}</td>
                    <td>{c.duplicate_emails}</td>
                    <td className="note">{c.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="small muted">
            "Resent after a kill" counts emails that reached the provider twice because a worker died between sending and
            recording. The provider's idempotency key dropped every one of those repeats.
          </p>
          {results.throughput && (
            <>
              <h3>Throughput</h3>
              <p className="muted small">{results.throughput.workload}.</p>
              <ul className="plain">
                {results.throughput.results.map((t) => (
                  <li key={t.threads}><strong>{t.postings_per_second_mean}</strong> postings per second with {t.threads} thread{t.threads > 1 ? "s" : ""} (sd {t.postings_per_second_sd})</li>
                ))}
              </ul>
              <p className="small muted">Source: {fileLink(results.throughput.file)}.</p>
            </>
          )}
        </section>
      )}

      <section className="stack">
        <h2>Built with</h2>
        <p>
          Java 21 and Spring Boot 4.1 for the API and the worker, PostgreSQL 16 as the database and the job queue,
          React and TypeScript for the app and a Chrome extension, JUnit 5 with Testcontainers, Playwright for browser
          tests, Docker Compose, GitHub Actions, and Ollama for the local model. Nothing runs on a paid service.
        </p>
        <pre><code>git clone {REPO}{"\n"}cd fineprint{"\n"}docker compose up -d --build{"\n"}open http://localhost:8081</code></pre>
      </section>

      <footer>
        <p>
          Made by Charith Kutikuppala. MIT license. Posting text belongs to the employers; this page shows short quotes from
          a dated snapshot and links to the originals.
        </p>
      </footer>
    </div>
  );
}
