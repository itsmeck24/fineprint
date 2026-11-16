export type Reason = { source: string; quote: string; note: string };
export type Rule = { kind: string; label: string; sourcesDisagree: boolean; reasons: Reason[] };
export type Change = { kind: string; at: string; summary: { added?: Crit[]; removed?: Crit[] } };
export type Crit = { kind: string; source: string; quote: string; value: string | null; range: string | null };
export type Posting = {
  id: number;
  title: string;
  company: string;
  ats: string;
  url: string | null;
  status: string;
  lastCheckedAt: string | null;
  versions: number;
  ready: boolean;
  rules: Rule[];
  changes: Change[];
};
export type Watch = { boardId: number; ats: string; token: string; since: string; openPostings: number };
export type Me = { email: string; gradMonth: string; degreeLevel: string; emailAlerts: boolean };
export type Notification = { id: number; kind: string; subject: string; status: string; createdAt: string; sentAt: string | null };

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    // server reasons are written lower case for logs, the page shows them as a sentence
    super(message.charAt(0).toUpperCase() + message.slice(1));
  }
}

// base is "" in the web app (same origin through the proxy) and the configured api url in the extension
export function client(base: string, token: () => string | null) {
  async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const t = token();
    if (t) headers["Authorization"] = `Bearer ${t}`;
    let res: Response;
    try {
      res = await fetch(base + "/api" + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch {
      // the free host sleeps when idle and takes about a minute to wake
      throw new ApiError(0, "could not reach the server. It may be waking up, try again in a minute.");
    }
    if (!res.ok) {
      let msg = res.statusText || `request failed (${res.status})`;
      try {
        const j = await res.json();
        msg = j.message || j.error || msg;
      } catch {
        // no json body
      }
      throw new ApiError(res.status, msg);
    }
    return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
  }
  return {
    register: (email: string, password: string, gradMonth: string, degreeLevel: string) =>
      call<{ token: string }>("POST", "/auth/register", { email, password, gradMonth, degreeLevel }),
    login: (email: string, password: string) => call<{ token: string }>("POST", "/auth/login", { email, password }),
    me: () => call<Me>("GET", "/me"),
    updateMe: (gradMonth: string, degreeLevel: string) => call<Me>("PUT", "/me", { gradMonth, degreeLevel }),
    deleteMe: () => call<void>("DELETE", "/me"),
    postings: () => call<Posting[]>("GET", "/postings"),
    posting: (id: number) => call<Posting>("GET", `/postings/${id}`),
    save: (url: string) => call<{ id: number }>("POST", "/postings", { url }),
    unsave: (id: number) => call<void>("DELETE", `/postings/${id}`),
    lookup: (url: string) =>
      call<{ known: boolean; saved?: boolean; posting?: Posting }>("GET", `/postings/lookup?url=${encodeURIComponent(url)}`),
    watches: () => call<Watch[]>("GET", "/watches"),
    watch: (board: string) => call<{ boardId: number }>("POST", "/watches", { board }),
    unwatch: (boardId: number) => call<void>("DELETE", `/watches/${boardId}`),
    notifications: () => call<Notification[]>("GET", "/notifications"),
  };
}

export type Api = ReturnType<typeof client>;
