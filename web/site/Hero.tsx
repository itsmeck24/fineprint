// the opening: a real posting excerpt from the 2026-11-15 snapshot, marked up the way fineprint reads it.
// every quote here is copied from the posting or its application form.
const POSTING = "https://job-boards.greenhouse.io/verkada/jobs/5210813007";

function Mark({ n, children, tone = "hl" }: { n: number; children: React.ReactNode; tone?: "hl" | "red" }) {
  return (
    <span className={`mark ${tone}`} style={{ animationDelay: `${0.5 + n * 0.55}s` }}>
      {children}
      <sup>{n}</sup>
    </span>
  );
}

function Note({ n, kind, verdict, tone, children }: {
  n: number; kind: string; verdict: string; tone: "go" | "red" | "ink"; children: React.ReactNode;
}) {
  return (
    <li className="mnote" style={{ animationDelay: `${0.7 + n * 0.55}s` }}>
      <span className="note-n">{n}</span>
      <div>
        <div className="note-head">{kind} <span className={`verdict ${tone}`}>{verdict}</span></div>
        <p>{children}</p>
      </div>
    </li>
  );
}

export function Hero() {
  return (
    <section className="hero">
      <div className="hero-copy">
        <p className="kicker">FinePrint &middot; for new-grad and intern applicants</p>
        <h1>Read the fine print <em>before</em> you apply.</h1>
        <p className="lead">
          A job title says "Intern 2027". The description and the application form say who can actually apply. FinePrint
          reads all three, checks them against your graduation month and degree, and quotes the sentence behind every
          answer. When a rule changes, it tells you once.
        </p>
      </div>

      <div className="markup" aria-label="An annotated job posting">
        <article className="doc">
          <header className="doc-head">
            <span>Verkada</span><span>Greenhouse</span><span>read as: May 2027, master's</span>
          </header>
          <h2 className="doc-title">Backend Software Engineering Intern 2027</h2>
          <p className="doc-gap">&hellip;</p>
          <p>
            Actively pursuing a <Mark n={2}>Bachelor's or Master's degree</Mark> in Computer Science or similar technical
            field of study and <Mark n={1}>graduating by June 2028</Mark>
          </p>
          <p className="doc-gap">&hellip;</p>
          <p><Mark n={3}>We do sponsor and take over sponsorship of employment visas for this role.</Mark></p>
          <div className="doc-form">
            <span className="form-label">Application form</span>
            <p className="form-q">When do you graduate?</p>
            <div className="options">
              <Mark n={4} tone="red"><span className="opt">Sept - Dec 2027</span></Mark>
              <span className="opt">Jan - April 2028</span>
              <span className="opt">May - Aug 2028</span>
              <span className="opt">Sept - Dec 2028</span>
              <span className="opt">Other</span>
            </div>
          </div>
          <p className="doc-source">
            Quoted from <a href={POSTING}>the posting</a> as collected on 2026-11-15.
          </p>
        </article>

        <ol className="margin">
          <Note n={1} kind="Graduation window" verdict="matches you" tone="go">
            The window ends June 2028, so a May 2027 graduate is inside it.
          </Note>
          <Note n={2} kind="Degree" verdict="matches you" tone="go">
            Bachelor's or master's, and you are finishing a master's.
          </Note>
          <Note n={3} kind="Sponsorship" verdict="says it sponsors" tone="ink">
            Stated in the description. Most postings say nothing at all.
          </Note>
          <Note n={4} kind="The form disagrees" verdict="sources disagree" tone="red">
            The earliest option is September 2027. A May 2027 graduate can only pick "Other".
          </Note>
        </ol>
      </div>
    </section>
  );
}
