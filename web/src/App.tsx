import { useMemo, useState } from "react";
import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { client } from "./api";
import { Login } from "./pages/Login";
import { Postings } from "./pages/Postings";
import { PostingPage } from "./pages/PostingPage";
import { Watches } from "./pages/Watches";
import { Settings } from "./pages/Settings";

const KEY = "fineprint.token";

export function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(KEY));
  const navigate = useNavigate();
  const api = useMemo(() => client("", () => token), [token]);

  function signIn(t: string) {
    localStorage.setItem(KEY, t);
    setToken(t);
    navigate("/");
  }

  function signOut() {
    localStorage.removeItem(KEY);
    setToken(null);
    navigate("/login");
  }

  if (!token) {
    return (
      <Routes>
        <Route path="/login" element={<Login api={api} onToken={signIn} />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }

  return (
    <div className="shell">
      <nav>
        <Link to="/" className="brand">FinePrint</Link>
        <Link to="/">Saved</Link>
        <Link to="/watches">Watching</Link>
        <Link to="/settings">Settings</Link>
        <button className="link" onClick={signOut}>Sign out</button>
      </nav>
      <main>
        <Routes>
          <Route path="/" element={<Postings api={api} onAuthLost={signOut} />} />
          <Route path="/postings/:id" element={<PostingPage api={api} />} />
          <Route path="/watches" element={<Watches api={api} />} />
          <Route path="/settings" element={<Settings api={api} onDeleted={signOut} />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
