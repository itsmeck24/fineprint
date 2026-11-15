import type { Rule } from "./api";

// shared by the web app and the browser extension. quotes come from job boards, so they are rendered
// as text nodes only, never as html.
const NAMES: Record<string, string> = {
  grad_window: "Graduation window",
  degree: "Degree",
  return_to_school: "Return to school",
  work_auth: "Work authorization",
};

const LABELS: Record<string, string> = {
  matches: "Matches you",
  conflicts: "Rules you out",
  screened: "Asked on the form",
  unknown: "Not stated",
  sponsors: "Says it sponsors",
  no_sponsorship: "No sponsorship",
  hedged: "Sponsorship possible, not promised",
  citizenship_required: "Citizenship required",
  clearance_required: "Security clearance required",
  export_control: "Export control applies",
  authorization_required: "Must already be authorized",
};

function tone(label: string): string {
  if (label === "matches" || label === "sponsors") return "good";
  if (["conflicts", "no_sponsorship", "citizenship_required", "clearance_required"].includes(label)) return "bad";
  if (label === "unknown") return "plain";
  return "warn";
}

export function Card({ rules }: { rules: Rule[] }) {
  return (
    <div className="card">
      {rules.map((r) => (
        <section key={r.kind} className="rule" data-kind={r.kind}>
          <header>
            <span className="rule-name">{NAMES[r.kind] ?? r.kind}</span>
            <span className={`label ${tone(r.label)}`} data-label={r.label}>
              {LABELS[r.label] ?? r.label}
            </span>
            {r.sourcesDisagree && <span className="label warn" data-flag="disagree">Sources disagree</span>}
          </header>
          {r.reasons.length === 0 && <p className="muted">Nothing in the title, text or form.</p>}
          <ul>
            {r.reasons.map((x, i) => (
              <li key={i}>
                <span className="src">{x.source}</span>
                <q>{x.quote}</q>
                <span className="note">{x.note}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
