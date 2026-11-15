"""Per-turn source tracking, so research replies and documents carry structured
citations the app can render as a "Sources" card with links out.

Three feeds, all landing in the shared per-request ``state["sources"]``:

  * **search** — every result ``web_search`` returns is recorded automatically
    and shown to the model with a stable per-turn number, ``[n]``.
  * **browse** — every page a successful sandbox command *fetched* (curl/wget,
    requests/httpx/urlopen, Playwright ``page.goto``, ``pd.read_csv`` of a URL,
    ``git clone``…), found in the command and in the scripts it ran. The
    command's output tells the model each page's ``[n]``.
  * **link** — any ``[title](url)`` or bare URL the model writes in its reply or
    an artifact is recorded at wrap time (and gives an auto-titled source its
    real title).

At the end of the turn each source is marked **cited** if the text references
its ``[n]`` marker or its URL; the rest were consulted but not cited. The
envelope carries the message's list (and each artifact gets its own, relative
to its text), cited first.
"""

from __future__ import annotations

import ipaddress
import re
from urllib.parse import unquote, urlsplit, urlunsplit

MAX_SOURCES = 80
_SNIPPET = 240

# [3]  or  [3, 7]  — but not a markdown link label "[3](…)".
_CITE = re.compile(r"\[(\d{1,3}(?:\s*,\s*\d{1,3})*)\](?!\()")
_MD_LINK = re.compile(r"\[([^\]\n]{1,200})\]\((https?://[^)\s]+)\)")
_URL = re.compile(r"https?://[^\s)\]>\"'<]+")
_TRACKING = ("utm_", "fbclid=", "gclid=", "mc_cid=", "mc_eid=")


def _norm(url: str) -> str:
    """Dedup key: lowercase scheme/host, no fragment, no trailing slash, no
    tracking params. Empty string for anything that isn't http(s)."""
    try:
        p = urlsplit((url or "").strip())
    except ValueError:
        return ""
    if p.scheme.lower() not in ("http", "https") or not p.netloc:
        return ""
    query = "&".join(
        kv for kv in p.query.split("&") if kv and not kv.lower().startswith(_TRACKING)
    )
    return urlunsplit((p.scheme.lower(), p.netloc.lower(), p.path.rstrip("/"), query, ""))


def _domain(url: str) -> str:
    try:
        host = urlsplit(url).netloc.lower()
    except ValueError:
        return ""
    return host[4:] if host.startswith("www.") else host


def _readable(url: str) -> str:
    """A title for a page we only have the URL of: domain + the last two path
    segments, e.g. "iea.org › reports › global-ev-outlook-2025"."""
    dom = _domain(url)
    try:
        segs = [unquote(s) for s in urlsplit(url).path.split("/") if s][-2:]
    except ValueError:
        segs = []
    return " › ".join([dom] + [s[:48] for s in segs]) if segs else dom


def record(state: dict, url: str, title: str = "", snippet: str = "", via: str = "search") -> int | None:
    """Add a source (or find the existing one) and return its per-turn number.
    None for a non-http(s) URL or once the per-turn cap is reached."""
    key = _norm(url)
    if not key:
        return None
    srcs: list = state.setdefault("sources", [])
    title = (title or "").strip()[:200]
    for s in srcs:
        if s["_norm"] == key:
            # A later, richer sighting replaces an auto-derived title and fills
            # in a missing snippet.
            if title and s.get("_auto"):
                s["title"], s["_auto"] = title, False
            if snippet and not s.get("snippet"):
                s["snippet"] = snippet.strip()[:_SNIPPET]
            return s["n"]
    if len(srcs) >= MAX_SOURCES:
        return None
    n = len(srcs) + 1
    srcs.append({
        "n": n,
        "url": url.strip(),
        "title": title or _readable(url),
        "domain": _domain(url),
        "snippet": (snippet or "").strip()[:_SNIPPET],
        "via": via,
        "_norm": key,
        "_auto": not title,
    })
    return n


# --- pages fetched in the sandbox -------------------------------------------

# A line that fetches something: shell clients, Python/Node HTTP + browser APIs.
_FETCH_HINT = re.compile(
    r"\b(curl|wget|xh|httpie|lynx|w3m|aria2c|git\s+clone|requests|httpx|aiohttp|urlopen|"
    r"urlretrieve|fetch|read_csv|read_html|read_json|read_excel|read_parquet|download)\b"
    r"|\.goto\s*\(|\.(get|post|head|request)\s*\(",
    re.I,
)
# Package installs aren't research sources.
_INSTALL = re.compile(r"\b(pip3?|uv|npm|pnpm|yarn|apt(-get)?|conda|mamba|brew|gem|cargo)\s+(install|add|i)\b", re.I)
_INFRA = (
    "pypi.org", "pythonhosted.org", "npmjs.org", "npmjs.com", "yarnpkg.com", "debian.org",
    "ubuntu.com", "anaconda.org", "anaconda.com", "pytorch.org", "nodesource.com",
    "get.docker.com", "playwright.azureedge.net", "deb.nodesource.com",
)


def _public(url: str) -> bool:
    try:
        host = (urlsplit(url).hostname or "").lower()
    except ValueError:
        return False
    if not host or "." not in host or host == "localhost":
        return False
    if host.endswith((".local", ".internal", ".localhost", ".lan")):
        return False
    try:
        return ipaddress.ip_address(host).is_global
    except ValueError:
        pass  # a hostname, not an IP literal
    return not any(host == d or host.endswith("." + d) for d in _INFRA)


def _clean(url: str) -> str | None:
    url = url.rstrip(".,;:'\")]}")
    if "{" in url or "$" in url or "%s" in url:   # a template, not a real page
        return None
    return url if _public(url) else None


def fetched_urls(command: str, scripts: list[str] | None = None, limit: int = 12) -> list[str]:
    """URLs a sandbox command fetched. In the command: URLs on lines that fetch
    (and aren't package installs). In a script it ran: every URL, if the script
    fetches at all (scrapers usually keep their target URLs in variables)."""
    out: list[str] = []

    def add(u: str) -> bool:
        c = _clean(u)
        if c and c not in out:
            out.append(c)
        return len(out) >= limit

    for line in (command or "").splitlines():
        if _FETCH_HINT.search(line) and not _INSTALL.search(line):
            for u in _URL.findall(line):
                if add(u):
                    return out
    for src in scripts or []:
        if not _FETCH_HINT.search(src):
            continue
        for line in src.splitlines():
            if _INSTALL.search(line):
                continue
            for u in _URL.findall(line):
                if add(u):
                    return out
    return out


def adopt(state: dict, old_sources: list | None, text: str) -> str:
    """Bring an earlier document's sources into this turn's registry so a
    revision keeps its citations: each old source is recorded (deduped against
    anything already found this turn) and the document's ``[n]`` markers are
    renumbered to match. Returns the renumbered text. With nothing recorded yet
    this turn the numbering is unchanged."""
    mapping: dict[int, int] = {}
    olds = [s for s in (old_sources or []) if isinstance(s, dict) and s.get("url")]
    for s in sorted(olds, key=lambda s: int(s.get("n") or 0)):
        title = "" if s.get("title") == s.get("domain") else str(s.get("title") or "")
        m = record(state, str(s["url"]), title=title, snippet=str(s.get("snippet") or ""),
                   via=str(s.get("via") or "search"))
        if m is not None and str(s.get("n", "")).isdigit():
            mapping[int(s["n"])] = m
    if not mapping or all(k == v for k, v in mapping.items()):
        return text

    def renum(m: re.Match) -> str:
        nums = [x.strip() for x in m.group(1).split(",")]
        return "[" + ", ".join(str(mapping.get(int(x), x)) for x in nums) + "]"

    return _CITE.sub(renum, text)


def capture_links(state: dict, text: str) -> None:
    """Record the links the model itself wrote (markdown links, then bare URLs)."""
    if not text:
        return
    linked = set()
    for label, url in _MD_LINK.findall(text):
        record(state, url, title=label, via="link")
        linked.add(url)
    for url in _URL.findall(text):
        if url not in linked:
            record(state, url.rstrip(".,;:"), via="link")


def _cited_numbers(text: str) -> set[int]:
    out: set[int] = set()
    for m in _CITE.finditer(text or ""):
        out.update(int(x) for x in re.split(r"\s*,\s*", m.group(1)) if x.isdigit())
    return out


def for_text(state: dict, text: str) -> list[dict]:
    """The turn's sources, each flagged ``cited`` relative to ``text`` (by
    ``[n]`` marker or URL), cited first. Empty when the turn had none."""
    srcs = state.get("sources") or []
    if not srcs:
        return []
    nums = _cited_numbers(text)
    urls = {_norm(u.rstrip(".,;:")) for u in _URL.findall(text or "")}
    out = []
    for s in srcs:
        item = {k: v for k, v in s.items() if not k.startswith("_")}
        item["cited"] = s["n"] in nums or s["_norm"] in urls
        out.append(item)
    out.sort(key=lambda s: (not s["cited"], s["n"]))
    return out
