import { useState, type FormEvent } from "react";
import type { Api } from "../api";

export function Login({ api, onToken }: { api: Api; onToken: (t: string) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [gradMonth, setGradMonth] = useState("2027-05");
  const [degree, setDegree] = useState("master");
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const res = mode === "login"
        ? await api.login(email, password)
        : await api.register(email, password, gradMonth, degree);
      onToken(res.token);
    } catch (err) {
      setError(err instanceof Error ? err.message : "something went wrong");
    }
  }

  return (
    <div className="login">
      <h1>FinePrint</h1>
      <p className="muted">The eligibility rules in job postings and their application forms, with the sentence each one comes from.</p>
      <form onSubmit={submit}>
        <label>Email <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
        <label>Password <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={8} /></label>
        {mode === "register" && (
          <>
            <label>Graduation month <input type="month" value={gradMonth} onChange={(e) => setGradMonth(e.target.value)} required /></label>
            <label>Degree
              <select value={degree} onChange={(e) => setDegree(e.target.value)}>
                <option value="bachelor">Bachelor's</option>
                <option value="master">Master's</option>
                <option value="phd">PhD</option>
              </select>
            </label>
            <p className="muted small">That is all FinePrint stores about you, plus your email for alerts.</p>
          </>
        )}
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit">{mode === "login" ? "Sign in" : "Create account"}</button>
      </form>
      <button className="link" onClick={() => setMode(mode === "login" ? "register" : "login")}>
        {mode === "login" ? "New here? Create an account" : "Have an account? Sign in"}
      </button>
    </div>
  );
}
