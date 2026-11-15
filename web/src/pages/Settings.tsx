import { useEffect, useState, type FormEvent } from "react";
import type { Api } from "../api";

export function Settings({ api, onDeleted }: { api: Api; onDeleted: () => void }) {
  const [email, setEmail] = useState("");
  const [gradMonth, setGradMonth] = useState("");
  const [degree, setDegree] = useState("master");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api.me().then((m) => {
      setEmail(m.email);
      setGradMonth(m.gradMonth);
      setDegree(m.degreeLevel);
    });
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    await api.updateMe(gradMonth, degree);
    setSaved(true);
  }

  async function remove() {
    if (confirm("Delete your account, saved postings, watches and alerts? This cannot be undone.")) {
      await api.deleteMe();
      onDeleted();
    }
  }

  return (
    <>
      <h2>Settings</h2>
      <p className="muted">{email}</p>
      <form onSubmit={save}>
        <label>Graduation month <input type="month" value={gradMonth} onChange={(e) => { setGradMonth(e.target.value); setSaved(false); }} required /></label>
        <label>Degree
          <select value={degree} onChange={(e) => { setDegree(e.target.value); setSaved(false); }}>
            <option value="bachelor">Bachelor's</option>
            <option value="master">Master's</option>
            <option value="phd">PhD</option>
          </select>
        </label>
        <button type="submit">Save</button> {saved && <span className="muted">Saved. Cards use the new profile right away.</span>}
      </form>
      <h3>Delete account</h3>
      <button className="danger" onClick={remove}>Delete my account</button>
    </>
  );
}
