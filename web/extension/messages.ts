import type { Posting } from "../src/api";

export type Msg = { type: "lookup"; url: string } | { type: "save"; url: string };
export type Reply =
  | { ok: true; known: boolean; saved?: boolean; posting?: Posting }
  | { ok: false; error: string; needsLogin?: boolean };
