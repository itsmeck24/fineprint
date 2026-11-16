import { useEffect, useState, type FormEvent } from "react";
import type { Api, Notification, Watch } from "../api";
import { atsName, companyName } from "../names";

const ALERT: Record<string, string> = { change: "Requirement change", digest: "New postings" };

export function Watches({ api }: { api: Api }) {
  const [list, setList] = useState<Watch[]>([]);
  const [mail, setMail] = useState<Notification[]>([]);
  const [board, setBoard] = useState("");
  const [emailAlerts, setEmailAlerts] = useState(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setList(await api.watches());
    setMail(await api.notifications());
  }

  useEffect(() => {
    load().catch((e) => setError(e.message));
    api.me().then((m) => setEmailAlerts(m.emailAlerts)).catch(() => {});
  }, []);

  async function stop(boardId: number) {
    try {
      await api.unwatch(boardId);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "could not stop watching");
    }
  }

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
      <p className="muted">
        {emailAlerts
          ? "New postings on these boards that do not rule you out arrive in an hourly digest email."
          : "New postings on these boards that do not rule you out are listed under Alerts every hour. This copy of FinePrint does not send email."}
      </p>
      <form className="row" onSubmit={add}>
        <input placeholder="greenhouse:verkada, lever:palantir, or a board link" value={board}
               onChange={(e) => setBoard(e.target.value)} aria-label="Board" required />
        <button type="submit">Watch</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      <ul className="postings">
        {list.map((w) => (
          <li key={w.boardId}>
            <span className="title">{companyName(w.token)}</span> <span className="muted">on {atsName(w.ats)}, {w.openPostings} open posting{w.openPostings === 1 ? "" : "s"}, watching since {new Date(w.since).toLocaleDateString()}</span>{" "}
            <button className="link" onClick={() => stop(w.boardId)}>stop</button>
          </li>
        ))}
      </ul>
      <h3>Alerts</h3>
      {mail.length === 0 && <p className="muted">None yet.</p>}
      <ul className="history">
        {mail.map((n) => (
          <li key={n.id}>
            <span className="label plain">{ALERT[n.kind] ?? n.kind}</span> {n.subject}{" "}
            <span className="muted">{new Date(n.createdAt).toLocaleString()}{emailAlerts && n.status !== "sent" ? `, email ${n.status.replaceAll("_", " ")}` : ""}</span>
          </li>
        ))}
      </ul>
    </>
  );
}
