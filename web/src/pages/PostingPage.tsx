import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ApiError, type Api, type Posting } from "../api";
import { Card } from "../Card";
import { atsName, companyName } from "../names";

export function PostingPage({ api }: { api: Api }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [p, setP] = useState<Posting | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let stop = false;
    async function load() {
      try {
        const got = await api.posting(Number(id));
        if (!stop) { setP(got); setError(null); }
      } catch (e) {
        if (stop) return;
        // a 404 means the id is not in this user's saved list, so polling again will not help
        if (e instanceof ApiError && e.status === 404) {
          setError("This posting is not in your saved list.");
          clearInterval(t);
        } else {
          setError(e instanceof Error ? e.message : "could not load");
        }
      }
    }
    load();
    const t = setInterval(load, 3000);
    return () => {
      stop = true;
      clearInterval(t);
    };
  }, [id]);

  if (error) return <><p className="error" role="alert">{error}</p><p><Link to="/">Back to saved postings</Link></p></>;
  if (!p) return <p className="muted">Loading...</p>;

  if (p.status === "not_found") {
    return (
      <>
        <h2>Job not found</h2>
        <p>{atsName(p.ats)} has no job at this link. Check that it was copied in full, or the job may already be taken down.</p>
        <button className="secondary" onClick={async () => { await api.unsave(p.id); navigate("/"); }}>Remove from saved</button>
      </>
    );
  }

  return (
    <>
      <h2>{p.title || "Fetching posting..."}</h2>
      <p className="muted">
        {companyName(p.company)} on {atsName(p.ats)} | {p.status} | checked {p.lastCheckedAt ? new Date(p.lastCheckedAt).toLocaleString() : "not yet"} | {p.versions} version{p.versions === 1 ? "" : "s"}
        {p.url && <> | <a href={p.url} target="_blank" rel="noreferrer noopener">open posting</a></>}
      </p>
      {p.ready ? <Card rules={p.rules} /> : <p className="muted">Reading the posting. This takes a few seconds.</p>}
      <h3>History</h3>
      {p.changes.length === 0 && <p className="muted">No requirement changes recorded.</p>}
      <ul className="history">
        {p.changes.map((c, i) => (
          <li key={i}>
            <span className="label plain">{c.kind.replaceAll("_", " ")}</span> {new Date(c.at).toLocaleString()}
            {c.summary.removed?.map((x, j) => <div key={"r" + j} className="removed">removed: <q>{x.quote}</q></div>)}
            {c.summary.added?.map((x, j) => <div key={"a" + j} className="added">added: <q>{x.quote}</q></div>)}
          </li>
        ))}
      </ul>
      <button className="secondary" onClick={async () => { await api.unsave(p.id); navigate("/"); }}>Remove from saved</button>
    </>
  );
}
