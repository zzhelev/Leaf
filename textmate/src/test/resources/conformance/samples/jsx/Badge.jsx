import { useState } from "react";

// A compare badge, A or B.
export function Badge({ letter, title = "side" }) {
  const [hover, setHover] = useState(false);
  const color = letter === "A" ? "#E9C754" : "#9FD1FF";
  return (
    <span
      className={`badge ${hover ? "badge--hover" : ""}`}
      style={{ background: color, width: 16 }}
      title={title}
      onMouseEnter={() => setHover(true)}
    >
      {letter}
      {/* the letter */}
    </span>
  );
}
