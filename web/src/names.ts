// boards are keyed by a url slug, so known ones get their real name and the rest are title-cased
const COMPANY: Record<string, string> = {
  verkada: "Verkada", doordashusa: "DoorDash", cloudflare: "Cloudflare", pinterest: "Pinterest", duolingo: "Duolingo",
  scaleai: "Scale AI", waymo: "Waymo", palantir: "Palantir", mongodb: "MongoDB", affirm: "Affirm", roblox: "Roblox", nuro: "Nuro",
};

export function companyName(slug: string): string {
  if (!slug) return "";
  return COMPANY[slug] ?? slug.split(/[-_]/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ");
}

export function atsName(ats: string): string {
  return ats === "greenhouse" ? "Greenhouse" : ats === "lever" ? "Lever" : ats;
}
