// the system on one line: boards, worker, postgres, outbox, inbox. drawn with currentColor so it follows the theme.
export function Diagram() {
  const box = (x: number, y: number, w: number, label: string, sub: string) => (
    <g>
      <rect x={x} y={y} width={w} height={64} rx={8} className="d-box" />
      <text x={x + w / 2} y={y + 28} textAnchor="middle" className="d-label">{label}</text>
      <text x={x + w / 2} y={y + 47} textAnchor="middle" className="d-sub">{sub}</text>
    </g>
  );
  const arrow = (x1: number, x2: number, y: number) => (
    <line x1={x1} y1={y} x2={x2} y2={y} className="d-line" markerEnd="url(#arrow)" />
  );
  return (
    <svg viewBox="0 0 880 200" className="diagram" role="img"
         aria-label="Job boards feed a worker, which writes versions, rules and changes to Postgres; an outbox sends one email per change">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 0 L 10 5 L 0 10 z" className="d-head" />
        </marker>
      </defs>
      {box(0, 20, 150, "Job boards", "Greenhouse, Lever")}
      {arrow(150, 196, 52)}
      {box(200, 20, 170, "Worker", "poll, fetch, read rules")}
      {arrow(370, 416, 52)}
      {box(420, 20, 190, "PostgreSQL", "versions, rules, job queue")}
      {arrow(610, 656, 52)}
      {box(660, 20, 210, "Outbox sender", "idempotency key per email")}
      {box(420, 120, 190, "API and React app", "cards, history, watches")}
      <line x1={515} y1={84} x2={515} y2={116} className="d-line" markerEnd="url(#arrow)" />
      {box(660, 120, 210, "Browser extension", "card on the job page")}
      <line x1={610} y1={152} x2={656} y2={152} className="d-line" markerEnd="url(#arrow)" />
    </svg>
  );
}
