import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { ApiError, type Api, type Posting } from "../api";

const SHORT: Record<string, string> = { matches: "fits", conflicts: "rules you out", screened: "asked on form", unknown: "not stated" };

export function Postings({ api, onAuthLost }: { api: Api; onAuthLost: () => void }) {
  const [list, setList] = useState<Posting[] | null>(null);
  const [url, setUrl] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setList(await api.postings());
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) onAuthLost();
      else setError(e instanceof Error ? e.message : "could not load");
    }
  }

  useEffect(() => {
    load();
    // refresh while new saves are still being fetched
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, []);

  async function add(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.save(url);
      setUrl("");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "could not save");
    }
  }

  return (
    <>
      <h2>Saved postings</h2>
      <form className="row" onSubmit={add}>
        <input placeholder="https://job-boards.greenhouse.io/... or https://jobs.lever.co/..." value={url}
               onChange={(e) => setUrl(e.target.value)} aria-label="Posting link" required />
        <button type="submit">Save</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {list && list.length === 0 && <p className="muted">Nothing saved yet. Paste a Greenhouse or Lever job link.</p>}
      <ul className="postings">
        {list?.map((p) => (
          <li key={p.id}>
            <Link to={`/postings/${p.id}`} className="title">{p.title || "Fetching..."}</Link>
            <span className="muted"> {p.company}{p.status !== "open" ? ` (${p.status})` : ""}</span>
            <div className="chips">
              {!p.ready && <span className="label plain">reading posting</span>}
              {p.rules.filter((r) => r.kind !== "work_auth").map((r) => (
                <span key={r.kind} className={`label ${r.label === "conflicts" ? "bad" : r.label === "matches" ? "good" : "plain"}`}>
                  {r.kind.replace("_", " ")}: {SHORT[r.label] ?? r.label}
                </span>
              ))}
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
