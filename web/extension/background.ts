import { ApiError, client } from "../src/api";
import type { Msg, Reply } from "./messages";

// all api calls happen here, so job pages never see the token and page csp does not get in the way
async function api() {
  const { apiBase, token } = await chrome.storage.local.get(["apiBase", "token"]);
  return client((apiBase as string) || "http://localhost:8080", () => (token as string) || null);
}

async function handle(msg: Msg): Promise<Reply> {
  try {
    const a = await api();
    if (msg.type === "save") {
      await a.save(msg.url);
    }
    const r = await a.lookup(msg.url);
    return { ok: true, ...r };
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return { ok: false, error: "sign in from the extension options", needsLogin: true };
    return { ok: false, error: e instanceof Error ? e.message : "FinePrint is not reachable" };
  }
}

chrome.runtime.onMessage.addListener((msg: Msg, _sender, send) => {
  handle(msg).then(send);
  return true;
});
