import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import type { Api, Posting } from "../api";
import { Card } from "../Card";

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
        if (!stop) setP(got);
      } catch (e) {
        if (!stop) setError(e instanceof Error ? e.message : "not found");
      }
    }
    load();
    const t = setInterval(load, 3000);
    return () => {
      stop = true;
      clearInterval(t);
    };
  }, [id]);

  if (error) return <p className="error">{error}</p>;
  if (!p) return <p className="muted">Loading...</p>;

  return (
    <>
      <h2>{p.title || "Fetching posting..."}</h2>
      <p className="muted">
        {p.company} on {p.ats} | {p.status} | checked {p.lastCheckedAt ? new Date(p.lastCheckedAt).toLocaleString() : "not yet"} | {p.versions} version{p.versions === 1 ? "" : "s"}
        {p.url && <> | <a href={p.url} target="_blank" rel="noreferrer noopener">open posting</a></>}
      </p>
      {p.ready ? <Card rules={p.rules} /> : <p className="muted">Reading the posting. This takes a few seconds.</p>}
      <h3>History</h3>
      {p.changes.length === 0 && <p className="muted">No requirement changes recorded.</p>}
      <ul className="history">
        {p.changes.map((c, i) => (
          <li key={i}>
            <span className="label plain">{c.kind.replace("_", " ")}</span> {new Date(c.at).toLocaleString()}
            {c.summary.removed?.map((x, j) => <div key={"r" + j} className="removed">removed: <q>{x.quote}</q></div>)}
            {c.summary.added?.map((x, j) => <div key={"a" + j} className="added">added: <q>{x.quote}</q></div>)}
          </li>
        ))}
      </ul>
      <button className="secondary" onClick={async () => { await api.unsave(p.id); navigate("/"); }}>Remove from saved</button>
    </>
  );
}
