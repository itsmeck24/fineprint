import { useEffect, useState, type FormEvent } from "react";
import type { Api, Notification, Watch } from "../api";

export function Watches({ api }: { api: Api }) {
  const [list, setList] = useState<Watch[]>([]);
  const [mail, setMail] = useState<Notification[]>([]);
  const [board, setBoard] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setList(await api.watches());
    setMail(await api.notifications());
  }

  useEffect(() => {
    load().catch((e) => setError(e.message));
  }, []);

  async function add(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.watch(board);
      setBoard("");
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "could not add");
    }
  }

  return (
    <>
      <h2>Watching</h2>
      <p className="muted">New postings on these boards that do not rule you out arrive in an hourly digest email.</p>
      <form className="row" onSubmit={add}>
        <input placeholder="greenhouse:verkada, lever:palantir, or a board link" value={board}
               onChange={(e) => setBoard(e.target.value)} aria-label="Board" required />
        <button type="submit">Watch</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      <ul className="postings">
        {list.map((w) => (
          <li key={w.boardId}>
            <span className="title">{w.token}</span> <span className="muted">{w.ats}, {w.openPostings} tracked, since {new Date(w.since).toLocaleDateString()}</span>
            <button className="link" onClick={async () => { await api.unwatch(w.boardId); load(); }}>stop</button>
          </li>
        ))}
      </ul>
      <h3>Alerts sent to you</h3>
      {mail.length === 0 && <p className="muted">None yet.</p>}
      <ul className="history">
        {mail.map((n) => (
          <li key={n.id}>
            <span className="label plain">{n.kind}</span> {n.subject} <span className="muted">({n.status})</span>
          </li>
        ))}
      </ul>
    </>
  );
}
