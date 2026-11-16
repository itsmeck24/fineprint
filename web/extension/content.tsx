import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Card } from "../src/Card";
import css from "../src/style.css?inline";
import type { Msg, Reply } from "./messages";

function ask(msg: Msg): Promise<Reply> {
  return chrome.runtime.sendMessage(msg);
}

function Panel() {
  const [reply, setReply] = useState<Reply | null>(null);
  const [open, setOpen] = useState(true);
  const url = location.href;

  async function refresh() {
    setReply(await ask({ type: "lookup", url }));
  }

  useEffect(() => {
    refresh();
  }, []);

  // after saving, the worker needs a few seconds to fetch and read the posting
  useEffect(() => {
    if (reply?.ok && reply.saved && !reply.posting?.ready) {
      const t = setTimeout(refresh, 2000);
      return () => clearTimeout(t);
    }
  }, [reply]);

  if (!open) return <button className="fp-toggle" onClick={() => setOpen(true)}>FinePrint</button>;
  return (
    <div className="fp-panel">
      <div className="fp-head">
        <strong>FinePrint</strong>
        <button className="link" onClick={() => setOpen(false)} aria-label="Close">close</button>
      </div>
      {!reply && <p className="muted">Checking...</p>}
      {reply && !reply.ok && <p className="error">{reply.error}</p>}
      {reply?.ok && !reply.known && (
        <>
          <p className="muted">Not saved yet.</p>
          <button onClick={async () => setReply(await ask({ type: "save", url }))}>Save to FinePrint</button>
        </>
      )}
      {reply?.ok && reply.known && reply.posting && (
        <>
          {!reply.saved && <button onClick={async () => setReply(await ask({ type: "save", url }))}>Save to FinePrint</button>}
          {reply.posting.ready ? <Card rules={reply.posting.rules} /> : <p className="muted">Reading the posting...</p>}
        </>
      )}
    </div>
  );
}

const host = document.createElement("div");
host.id = "fineprint-root";
document.body.appendChild(host);
const shadow = host.attachShadow({ mode: "open" });
const style = document.createElement("style");
// :root matches nothing inside a shadow tree, so the theme variables are moved onto the shadow host
style.textContent = css.replaceAll(":root", ":host") + `
  .fp-panel { position: fixed; right: 16px; bottom: 16px; width: 360px; max-height: 70vh; overflow: auto; z-index: 2147483647;
    background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 12px; box-shadow: 0 6px 24px rgba(0,0,0,.18);
    font-family: system-ui, sans-serif; color: var(--text); font-size: 14px; }
  .fp-head { display: flex; justify-content: space-between; margin-bottom: 8px; }
  .fp-toggle { position: fixed; right: 16px; bottom: 16px; z-index: 2147483647; }`;
shadow.appendChild(style);
const mount = document.createElement("div");
shadow.appendChild(mount);
createRoot(mount).render(<Panel />);
