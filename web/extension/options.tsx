import { useEffect, useState, type FormEvent } from "react";
import { createRoot } from "react-dom/client";
import { client } from "../src/api";
import "../src/style.css";

function Options() {
  const [apiBase, setApiBase] = useState("http://localhost:8080");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [status, setStatus] = useState("");

  useEffect(() => {
    chrome.storage.local.get(["apiBase", "token"]).then((s) => {
      if (s.apiBase) setApiBase(s.apiBase as string);
      if (s.token) setStatus("Signed in.");
    });
  }, []);

  async function submit(e: FormEvent) {
    e.preventDefault();
    try {
      const { token } = await client(apiBase.replace(/\/$/, ""), () => null).login(email, password);
      await chrome.storage.local.set({ apiBase: apiBase.replace(/\/$/, ""), token });
      setStatus("Signed in.");
      setPassword("");
    } catch (err) {
      setStatus(err instanceof Error ? err.message : "could not sign in");
    }
  }

  return (
    <div className="login">
      <h1>FinePrint</h1>
      <form onSubmit={submit}>
        <label>API address <input value={apiBase} onChange={(e) => setApiBase(e.target.value)} /></label>
        <label>Email <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
        <label>Password <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required /></label>
        <button type="submit">Sign in</button>
      </form>
      <p className="muted">{status}</p>
      <button className="link" onClick={async () => { await chrome.storage.local.remove("token"); setStatus("Signed out."); }}>Sign out</button>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(<Options />);
