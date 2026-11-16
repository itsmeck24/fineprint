// the system as a pen drawing: one line through, the queue in the middle, hatching for storage.
export function Diagram() {
  const box = (x: number, y: number, w: number, label: string, sub: string, hatch = false) => (
    <g>
      {hatch && <rect x={x + 6} y={y + 6} width={w} height={66} className="d-shadow" />}
      <rect x={x} y={y} width={w} height={66} className="d-box" />
      <text x={x + 14} y={y + 28} className="d-label">{label}</text>
      <text x={x + 14} y={y + 49} className="d-sub">{sub}</text>
    </g>
  );
  const arrow = (d: string) => <path d={d} className="d-line" markerEnd="url(#pen)" />;
  return (
    <svg viewBox="0 0 900 230" className="diagram" role="img"
         aria-label="Job boards feed a worker. The worker writes versions, rules and changes to Postgres. An outbox sends one email per change. The API serves the app and the extension.">
      <defs>
        <marker id="pen" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto">
          <path d="M0 1 L9 5 L0 9" className="d-head" />
        </marker>
        <pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <line x1="0" y1="0" x2="0" y2="6" className="d-hatch" />
        </pattern>
      </defs>
      {box(0, 20, 160, "Job boards", "Greenhouse, Lever")}
      {arrow("M160 53 L208 53")}
      {box(212, 20, 170, "Worker", "poll, fetch, read")}
      {arrow("M382 53 L430 53")}
      {box(434, 20, 200, "PostgreSQL", "versions, rules, queue", true)}
      {arrow("M634 53 L682 53")}
      {box(686, 20, 210, "Outbox sender", "one key per email")}
      {arrow("M534 92 L534 138")}
      {box(434, 142, 200, "API + React app", "cards, history, watches")}
      {arrow("M634 175 L682 175")}
      {box(686, 142, 210, "Browser extension", "card on the job page")}
      <path d="M297 92 C 297 130, 360 120, 430 112" className="d-line dashed" markerEnd="url(#pen)" />
      <text x={300} y={140} className="d-note">claims jobs with skip locked</text>
    </svg>
  );
}
