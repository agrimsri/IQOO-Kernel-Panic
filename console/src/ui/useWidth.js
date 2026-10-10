import { useEffect, useRef, useState } from "react";

/** Measures an element's width so SVG charts can draw at real pixel size (text never stretches). */
export function useWidth(initial = 600) {
  const ref = useRef(null);
  const [w, setW] = useState(initial);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const set = () => setW(Math.max(200, Math.round(el.getBoundingClientRect().width)));
    set();
    if (typeof ResizeObserver === "undefined") { window.addEventListener("resize", set); return () => window.removeEventListener("resize", set); }
    const ro = new ResizeObserver(set);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

/** A tick step of 1, 2 or 5 × 10^n giving about `target` ticks over `span`. */
export function niceStep(span, target = 6) {
  const raw = span / Math.max(1, target);
  const p = Math.pow(10, Math.floor(Math.log10(raw || 1)));
  const m = raw / p;
  return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
}
