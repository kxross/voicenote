---
id: canvas
description: Render a visual React widget in the app instead of plain text — a chart, graph, comparison table, dashboard-style readout, or any formatted breakdown. Load whenever a picture would communicate better than prose, especially for anything quantitative ("chart", "graph", "plot", "visualize", "break down", "compare", "show me", "dashboard", trends, distributions, tallies over time).
---

You can render a **React widget** ("canvas") that shows in the app as a live,
rendered component — not text. Use it when a visual beats prose: charts, trends,
comparisons, tables, formatted readouts. For everyday replies, just answer in
text and do NOT load or use this.

Call `render_canvas(title, jsx)` **once**, then write a one or two sentence
`reply` that acts as the caption. The JSX is transpiled and rendered on-device
inside a sandbox.

## The dialect — follow exactly

- Write **React JSX**. Define a single top-level function component named **`App`**.
- **No `import`, no `export`, no `require`.** These globals are already present:
  - `React` and its hooks via `React.useState`, `React.useMemo`, etc.
  - `ReactDOM`
  - `Recharts` — the whole library. Destructure what you need at the top of your
    code, e.g. `const { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } = Recharts;`
- The sandbox has **no network, no storage, no timers for fetching**. Never call
  `fetch`, `XMLHttpRequest`, `localStorage`, `eval`, or reference `window`/`document`
  beyond normal React. All data must be **inlined as literals** in the JSX. It will
  be blocked otherwise.
- Client-side interactivity is welcome (state, hover, tabs, filtering, sorting) as
  long as it stays inside the component.

## Charts → Recharts

- Use **Recharts** for every chart. Always wrap a chart in
  `<ResponsiveContainer width="100%" height={260}>` — fixed height, width fills.
- Color chart marks from the preset monochrome palette, a global array **`MONO`**
  (ordered most- to least-prominent, already correct for the user's theme). Use
  `MONO[0]`, `MONO[1]`, … for `fill` / `stroke`. Example:
  `<Bar dataKey="hours" fill={MONO[0]} />`, second series `fill={MONO[1]}`.
- Axes/grid: thin and quiet. `<CartesianGrid stroke="var(--line)" vertical={false}/>`,
  axis `tick={{ fill: 'var(--muted)', fontSize: 12 }}` and `stroke="var(--line)"`.
- **Tooltip & Legend:** the harness already themes these to a dark surface with
  high-contrast text — do NOT give `<Tooltip>` a light/white `contentStyle`
  background, and do NOT color legend text. Leave them to the harness, or if you
  must set `contentStyle`, use `background: 'var(--bg)'` with `color: 'var(--fg)'`.

## Legibility — non-negotiable

Every piece of text must be high-contrast against whatever is directly behind it.
The theme is dark (dark surfaces, light text), so:
- Default all real text to `var(--fg)`. Use `var(--muted)` ONLY for small,
  de-emphasized labels (axis ticks, captions) that sit on the dark card — never
  for text on a light/white surface.
- Never place light text on a light background or dark text on a dark background.
  If you introduce your own surface, pair it deliberately: a light panel gets
  dark text, a dark panel gets light text. On this dark theme, prefer no filled
  panels at all — use `var(--line)` hairline borders instead of background fills.
- Do not hardcode `#fff`/`white` or near-white backgrounds behind text. Chart
  tooltips, legends, and labels must stay readable; when in doubt, use the CSS
  tokens, which are always contrast-correct for the current theme.

## Design — monochrome, clean (OpenAI-like)

- **Grayscale only. No hex colors, no color names, no accent hues.** Pull every
  color from these preset CSS variables (set on `:root`, theme-aware):
  `--bg` (surface), `--fg` (primary text), `--muted` (secondary text),
  `--line` (hairline borders/dividers), and the ramp `--mono-1` … `--mono-5`
  (most → least prominent). In JS/Recharts use the `MONO` array (same ramp).
- Aesthetic: generous whitespace, clear hierarchy, `--fg` for headings,
  `--muted` for labels, 1px `--line` borders, ~10px rounded corners, no shadows
  or gradients. System font is already applied — don't set `font-family`.
- Style with inline `style={{}}` using the variables, e.g.
  `style={{ color: 'var(--fg)', borderBottom: '1px solid var(--line)' }}`.
- Keep it to one self-contained component sized for a phone (~340px wide).

## Example shape (illustrative, not to copy verbatim)

```jsx
function App() {
  const { BarChart, Bar, XAxis, YAxis, Tooltip, CartesianGrid, ResponsiveContainer } = Recharts;
  const data = [
    { day: 'Mon', hours: 2 }, { day: 'Tue', hours: 5 },
    { day: 'Wed', hours: 3 }, { day: 'Thu', hours: 6 }, { day: 'Fri', hours: 4 },
  ];
  return (
    <div style={{ color: 'var(--fg)' }}>
      <ResponsiveContainer width="100%" height={260}>
        <BarChart data={data}>
          <CartesianGrid stroke="var(--line)" vertical={false} />
          <XAxis dataKey="day" stroke="var(--line)" tick={{ fill: 'var(--muted)', fontSize: 12 }} />
          <YAxis stroke="var(--line)" tick={{ fill: 'var(--muted)', fontSize: 12 }} />
          <Tooltip cursor={{ fill: 'var(--mono-5)' }} />
          <Bar dataKey="hours" fill={MONO[0]} radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```
