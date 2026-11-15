"""OllamaRuntime with progressive-disclosure skills.

At runtime, the model sees:
  1. The base system prompt.
  2. A SKILL CATALOG — one line per scoped skill: `<id> — <description>`.
  3. The lists snapshot as a system message (identical to ListManagerRuntime).
  4. One base tool: `load_skill(skill_id)`.

When the model calls `load_skill(id)`, we look the skill up in the
per-agent scope, build its tools bound to the per-request state, extend
the inner runtime's tools list, and return the SKILL.md body as the tool
response. The next iteration of the Ollama tool loop sees the augmented
tools + reads the body and proceeds.

We compose (not subclass) OllamaRuntime — the runtime instance we get
from the registry is a singleton across concurrent requests, so mutating
its `self.tools` would race. Instead each `arun` call builds a fresh
inner OllamaRuntime with per-request state.

Output envelope: `{"reply": <assistant text>, "ops": [<list ops>]}` —
same shape ListManagerRuntime produces, so the Android client's existing
parser handles both agents identically.
"""

from __future__ import annotations

import json
import re

from app.agent_base import AgentResult, BaseAgentRuntime
from app.runtimes.ollama_runtime import OllamaRuntime
from app.schemas import ChatMessage
from app.skill_registry import skill_registry

LOAD_SKILL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "load_skill",
        "description": (
            "Load a skill to gain access to its tools and instructions. Call this "
            "the moment you decide a skill from the SKILL CATALOG is relevant to "
            "the user's request. The returned text is the skill's SKILL.md body — "
            "read it and follow its guidance."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "skill_id": {
                    "type": "string",
                    "description": "The id of the skill to load, exactly as listed in the SKILL CATALOG.",
                },
            },
            "required": ["skill_id"],
        },
    },
}

RESOLVE_THREAD_SCHEMA = {
    "type": "function",
    "function": {
        "name": "resolve_thread",
        "description": (
            "Mark this conversation thread as resolved. Call when the user's "
            "request is fully handled and nothing is pending from them — e.g. a "
            "one-shot command you just completed, or a discussion that reached "
            "its conclusion. Do NOT call if you asked a question or made a "
            "proposal that awaits the user's answer."
        ),
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

NO_RESPONSE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "no_response",
        "description": (
            "Signal that this voice note needs no reply at all — plain "
            "journaling, musing, or a record with no actionable intent and no "
            "question. The transcript is still saved on the device; the user "
            "simply gets no notification. Call this INSTEAD of replying; any "
            "text you produce afterwards will be discarded."
        ),
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}


class SkillfulOllamaRuntime(BaseAgentRuntime):
    def __init__(
        self,
        *,
        model: str,
        base_url: str | None = None,
        system_prompt: str,
        skill_ids: list[str],
        think: bool | None = None,
        enable_subagents: bool = False,
    ) -> None:
        self.model = model
        self.base_url = base_url
        self.base_system_prompt = system_prompt
        self.think = think
        # Orchestrator: when on, expose the always-available run_subagents base
        # tool so this agent can delegate subtasks to other agents in parallel.
        self.enable_subagents = enable_subagents
        # Set once at startup by main.py's lifespan — image tools need it to
        # reach the blob store / caption cache.
        self.app_state = None
        # This agent's registry id, set by RegistryService.rebuild (alongside
        # app_state). Used to exclude self from the sub-agent delegatable list.
        self.agent_id = None

        # Resolve skill ids at construction so a typo or unknown id fails
        # fast at import time rather than at first request.
        missing = [sid for sid in skill_ids if skill_registry.get(sid) is None]
        if missing:
            known = ", ".join(s.id for s in skill_registry.list()) or "(none)"
            raise ValueError(
                f"SkillfulOllamaRuntime: unknown skill ids {missing}. "
                f"Registered skills: {known}"
            )
        self.available = {sid: skill_registry.get(sid) for sid in skill_ids}

    def _render_catalog(self) -> str:
        if not self.available:
            return "SKILL CATALOG: (empty — no skills scoped for this agent)"
        lines = ["SKILL CATALOG (call `load_skill(skill_id)` to activate one):"]
        for skill in self.available.values():
            lines.append(f"  • {skill.id} — {skill.description}")
        return "\n".join(lines)

    async def arun(
        self,
        messages: list[ChatMessage],
        *,
        session_id: str | None = None,
    ) -> AgentResult:
        (prompt, snapshot, notes, goals, scoped_goal, image_ids,
         model_override, provider_override, scoped_list,
         show_thinking, show_tools, resume_run_id) = _parse_payload(messages)

        # Per-request state — closure targets for load_skill and any skill's
        # tools. Sharing this dict across skills means e.g. a lists tool that
        # writes to state["ops"] shows up in the final wrapped response even
        # if the model also loaded the notes skill in the same turn.
        state: dict = {
            "snapshot": snapshot,
            "notes": notes,
            "goals": goals,
            "ops": [],
            "loaded": set(),
            "resolve": False,
            "silent": False,
            # An AI-generated React widget (the `canvas` skill writes here).
            # Stays None unless the model calls render_canvas.
            "canvas": None,
            # Long-form rendered documents (the `artifact` skill appends here).
            # Empty unless the model calls publish_artifact.
            "artifacts": [],
            # Sources consulted this turn (web_search results + links the model
            # wrote), for the app's "Sources" card. See app/sources.py.
            "sources": [],
            # Drafted schedule create/edit proposals (the `schedules` skill
            # appends here). Each needs explicit user approval on the phone;
            # empty unless the model called propose_schedule.
            "schedule_proposals": [],
            # Images live server-side, so their tools read the gateway's own
            # store rather than a client-supplied snapshot.
            "session": session_id,
            "app_state": self.app_state,
            # What the user asked for this turn — context for the sandbox's
            # smart-approval review of the commands the model runs.
            "user_request": prompt,
            # Documents published earlier in this thread (sent by the client),
            # so the artifact skill can revise one in place. id -> doc.
            "thread_docs": _parse_thread_docs(messages),
            # Videos attached to this note ([{id, duration_s, summary?}]) for the
            # `video` skill; "vision" = can the chosen model see frames (set by
            # the video guardrail below).
            "videos": _parse_videos(messages),
            "vision": True,
        }

        # VM sandbox wiring for the `vm` skill: where the daemon is, which
        # backend to ask for, and the scope key that selects which sandbox.
        # "shared" => one pinned machine for everything; "session" => one per
        # conversation (keyed by session, idle-reaped). Switching scope is this
        # one block — the tools and the model see no difference.
        from app.config import settings as _settings

        state["vm_base_url"] = _settings.vmmd_base_url
        state["default_backend"] = _settings.sandbox_backend
        if _settings.sandbox_scope == "session":
            state["vm_key"] = f"session:{session_id or 'default'}"
            state["vm_ttl_minutes"] = None       # daemon default TTL (idle-reaped)
        else:
            state["vm_key"] = "shared"
            state["vm_ttl_minutes"] = 0           # pinned — never idle-reaped

        # We need to close over `inner` inside load_skill to mutate its
        # tools list. Two-step build: declare, then assign.
        inner: OllamaRuntime | None = None

        async def load_skill(skill_id: str) -> str:
            skill = self.available.get(skill_id)
            if skill is None:
                known = ", ".join(self.available) or "(none scoped)"
                return f"[error] no such skill: '{skill_id}'. Available: {known}"
            if skill_id in state["loaded"]:
                return f"skill '{skill_id}' is already loaded — its tools are available"
            state["loaded"].add(skill_id)
            new_tools = skill.build_tools(state)
            # Runtime mutation: the next inner._call_once will send the
            # augmented tool set to Ollama.
            assert inner is not None
            inner.tools = inner.tools + new_tools
            return f"Skill '{skill_id}' loaded.\n\n{skill.body}"

        async def resolve_thread() -> str:
            state["resolve"] = True
            return "ok — thread will be marked resolved"

        async def no_response() -> str:
            state["silent"] = True
            return "ok — no reply will be delivered; the note is saved"

        threads_prompt = (
            "THREADS: you are in a threaded conversation. Earlier turns of this "
            "thread (if any) precede the current message — use them; don't re-ask "
            "what was already answered. When the request is fully handled and "
            "nothing is pending from the user, call resolve_thread() — one-shot "
            "commands like 'add milk' should be resolved in the same turn you "
            "complete them. If you ask a question or make a proposal, leave the "
            "thread open. If the note is pure journaling with no intent and no "
            "question, call no_response() and produce no reply."
        )

        orchestrator_prompt = ""
        if self.enable_subagents:
            orchestrator_prompt = (
                "\n\nORCHESTRATION: you can delegate with run_subagents(tasks) — it runs up "
                "to two other agents IN PARALLEL and returns their replies for you to "
                "synthesize. Use it ONLY when the request genuinely splits into independent "
                "pieces (e.g. research two different things at once); for a single linear "
                "task, just do it yourself. Each sub-agent sees only the task text you pass, "
                "and returns TEXT ONLY — it cannot edit the user's lists/goals or publish "
                "documents, so never delegate those. After results come back, write one "
                "combined answer; don't paste raw sub-agent output."
            )

        catalog_prompt = (
            f"{self.base_system_prompt}\n\n{threads_prompt}{orchestrator_prompt}\n\n{self._render_catalog()}"
        )

        # Per-request model/provider override (client picked one in Settings).
        # Guardrails: a model with no tool support can't drive skills at all
        # (Ollama 400s when tools are offered; a remote one would silently
        # degrade to a chatbot), so fall back to the configured default. And
        # only ask for reasoning when the model actually supports it.
        from app import model_catalog

        chosen_provider = provider_override or model_catalog.OLLAMA
        chosen_model = model_override or self.model
        chosen_think = self.think
        if model_override:
            flags = await model_catalog.model_flags(chosen_provider, model_override)
            if not flags["tools"]:
                # Can't run the skill loop — revert to the local default.
                chosen_provider = model_catalog.OLLAMA
                chosen_model = self.model
            else:
                chosen_think = self.think and flags["thinking"]

        # Vision guardrail: the local vLLM is served TEXT-ONLY (launched with an
        # image limit of 0 — the 27B's vision tower stays on Ollama/gemma3), so a
        # turn carrying attachments that still need to be SEEN (not yet captioned)
        # would 400 on vLLM. Route just those turns to the vision-capable local
        # default; captioned images are already plain text and stay on vLLM.
        if chosen_provider == model_catalog.VLLM and any(
            not (isinstance(e, dict) and e.get("summary")) for e in (image_ids or [])
        ):
            chosen_provider = model_catalog.OLLAMA
            chosen_model = self.model
            chosen_think = self.think

        # Video guardrail: watching a video means the model LOOKS at frames the
        # `video` skill returns mid-loop, so a turn carrying a video goes to a
        # vision-capable model. vLLM is text-only and some OpenRouter picks can't
        # see; those turns run on the local default instead. state["vision"]
        # tells the video tools whether to show frames or fall back to a
        # describer (if even the default can't see).
        if state["videos"]:
            vflags = await model_catalog.model_flags(chosen_provider, chosen_model)
            if not vflags.get("vision"):
                chosen_provider = model_catalog.OLLAMA
                chosen_model = self.model
                chosen_think = self.think
                vflags = await model_catalog.model_flags(chosen_provider, chosen_model)
            state["vision"] = bool(vflags.get("vision"))

        # Context guardrail: vLLM is served with a fixed max_model_len (65536),
        # while the local Ollama default runs a far larger window. The assistant
        # preloads the user's full lists, goals index, recent notes and thread
        # history — which for a heavy-data user exceeds vLLM's window and 400s
        # ("maximum context length"). Estimate the payload size and route only
        # the oversized turns to the big-context local default.
        if chosen_provider == model_catalog.VLLM:
            approx_chars = (
                len(self.base_system_prompt or "")
                + len(json.dumps(snapshot)) + len(json.dumps(goals))
                + sum(len(str(n)) for n in (notes or []))
                + len(prompt or "")
                # Count PRIOR thread turns only. messages[-1] is the raw payload
                # JSON whose snapshot/prompt are already counted above — including
                # it would double-count the (large) snapshot and fall back early.
                + sum(len(getattr(m, "content", "") or "") for m in messages[:-1])
                + 8000  # catalog + tool schemas + wiki index headroom
            )
            # Preload is JSON-dense (~2.6 chars/token effective here, a safe
            # under-count). Threshold = vllm_max_model_len * 2.6 chars keeps a
            # margin below the window for the uncounted catalog + tool schemas +
            # wiki index, and tracks the server's configured context automatically.
            if approx_chars > int(_settings.vllm_max_model_len * 2.6):
                chosen_provider = model_catalog.OLLAMA
                chosen_model = self.model
                chosen_think = self.think

        base_tools = [
            (LOAD_SKILL_SCHEMA, load_skill),
            (RESOLVE_THREAD_SCHEMA, resolve_thread),
            (NO_RESPONSE_SCHEMA, no_response),
        ]
        # Orchestrator: an always-on tool to delegate subtasks to other agents
        # in parallel (bounded; text-only; one level deep). See app/subagents.py.
        if self.enable_subagents:
            from app.subagents import build_subagents_tool
            base_tools.append(
                build_subagents_tool(self.agent_id, session_id, self.app_state)
            )
        if chosen_provider == model_catalog.VLLM:
            from app.config import settings as _s
            from app.runtimes.openai_runtime import OpenAICompatRuntime

            # Local vLLM (OpenAI-wire). Its own high-concurrency gate; qwen3
            # reasoning via top-level reasoning_effort. Model id comes from the
            # client pick, else the configured served-model-name.
            inner = OpenAICompatRuntime(
                model=model_override or _s.vllm_model,
                base_url=_s.vllm_base_url,
                api_key=_s.vllm_api_key,
                system_prompt=catalog_prompt,
                tools=base_tools,
                reasoning_effort="medium" if chosen_think else None,
                gate_provider=model_catalog.VLLM,
            )
        elif chosen_provider == model_catalog.OPENROUTER:
            from app.runtimes.openai_runtime import OpenAICompatRuntime

            inner = OpenAICompatRuntime(
                model=chosen_model,
                system_prompt=catalog_prompt,
                tools=base_tools,
                # Ollama's boolean `think` maps to an effort string here.
                reasoning_effort="medium" if chosen_think else None,
            )
        else:
            inner = OllamaRuntime(
                model=chosen_model,
                base_url=self.base_url,
                system_prompt=catalog_prompt,
                tools=base_tools,
                think=chosen_think,
                # End the tool loop as soon as the model signals completion via
                # resolve_thread / no_response, instead of looping to the cap.
                should_stop=lambda: state["resolve"] or state["silent"],
            )

        # Preload lists AND goals (goals carry their scoped notes, embedded
        # client-side) as a system message right before the user turn. This
        # Progressive disclosure: lists are small enough to preload in full
        # (the hot path is list mutations), but goals — with statements,
        # context, notes, plans, subtasks — are heavy, so only an INDEX is
        # injected. The agent expands specific goals via get_goal(title).
        # The scoped goal (if any) is preloaded in full because it's
        # certainly relevant to the current utterance.
        from app.tools.goal_ops import goal_index

        goals_index = goal_index(goals)
        parts = [
            "Current state of the user's lists (JSON):",
            json.dumps(snapshot),
            "",
            "INDEX of the user's goals (title, status, counts, plan summary). "
            "Call get_goal(title) to load a specific goal's full statement, "
            "context, notes, and plan — the goals skill's tools operate on "
            "goals BY TITLE, so you can act without a fetch if the index is "
            "enough. get_goals() returns the same index.",
            json.dumps(goals_index),
        ]
        if scoped_goal:
            scoped = next(
                (g for g in goals if str(g.get("title", "")).lower() == scoped_goal.lower()),
                None,
            )
            if scoped is not None:
                parts += [
                    "",
                    f"Scoped goal '{scoped_goal}' in FULL (preloaded because the "
                    "utterance targets it):",
                    json.dumps(scoped),
                ]
        # Learned wiki: inject a compact INDEX (title + when_to_use) of the
        # user's APPROVED entries so the model knows what it has learned and calls
        # wiki_search to pull the full entry — instead of re-deriving. Only when
        # the `wiki` retrieval plugin is scoped for this agent.
        if "wiki" in self.available:
            try:
                from app.wiki_store import store as _wiki
                widx = _wiki().index()
            except Exception:  # noqa: BLE001 — never fail a turn over the wiki
                widx = []
            if widx:
                lines = [f"- {e.get('title')} [id: {e.get('id')}] — {e.get('when_to_use', '')}"
                         for e in widx]
                parts += [
                    "",
                    "WIKI INDEX — reusable knowledge you've learned and the user approved. "
                    "Load the `wiki` skill and call wiki_search(query) to pull a full entry, "
                    "then apply it rather than re-deriving:",
                    "\n".join(lines),
                ]

        # Documents already in this thread: an INDEX only (bodies can be tens of
        # thousands of chars) — read_artifact pulls one in when it's needed.
        if state["thread_docs"] and "artifact" in self.available:
            parts += [
                "",
                "DOCUMENTS already published in this thread (newest first). When the "
                "user asks to change, fix, extend, shorten, update or reformat one of "
                "these, REVISE IT IN PLACE: load the `artifact` skill, call "
                "read_artifact(id), then edit_artifact(id, edits) — do NOT publish a "
                "new document, and don't redo research that's already in it. Publish "
                "a new document only when the user wants a separate one.",
                "\n".join(_doc_index_line(k, d) for k, d in state["thread_docs"].items()),
            ]

        snapshot_msg = ChatMessage(role="system", content="\n".join(parts))

        # A goal-scoped utterance (recorded from a goal's screen or as a
        # reply to a goal-scoped agent message) announces its target so the
        # model doesn't have to guess.
        # A scoped utterance (recorded from a goal's or list's screen, or as a
        # reply in a scoped thread) announces its target so the model doesn't
        # have to guess. Lists are already injected in full above, so naming
        # the list is enough — no extra preload needed.
        if scoped_goal:
            user_turn = f"[About goal: {scoped_goal}] {prompt}"
        elif scoped_list:
            user_turn = f"[About list: {scoped_list}] {prompt}"
        else:
            user_turn = prompt
        # Spoken from the document reader: the request is about THAT document.
        focus_id = _payload_str(messages, "focus_artifact")
        focus = state["thread_docs"].get(focus_id) if focus_id else None
        if focus is not None:
            user_turn = (
                f"[The user is reading the document \"{focus['title']}\" (id: {focus_id}) and "
                "asked this about it — if it asks for changes, revise that document in place] "
                + user_turn
            )

        # Turn-1 vision: attachments that haven't been captioned yet ride
        # inline as base64 so the model actually SEES them on the turn the
        # user attached them. Once captioned, later turns use the cached
        # text instead and no pixels are ever re-sent.
        inline_b64: list[str] = []
        if image_ids:
            from app import images as image_store

            captioned_notes = []
            for entry in image_ids:
                sha = entry.get("id") if isinstance(entry, dict) else str(entry)
                if not sha:
                    continue
                summary = entry.get("summary") if isinstance(entry, dict) else None
                if summary:
                    captioned_notes.append(f"- {sha[:8]}: {summary}")
                    continue
                b64 = image_store.blob_b64(sha)
                if b64:
                    inline_b64.append(b64)
            if inline_b64:
                user_turn += (
                    f"\n\n({len(inline_b64)} photo(s) attached to this note — "
                    "they are shown to you above/with this message.)"
                )
            if captioned_notes:
                user_turn += "\n\nPreviously captioned attachments:\n" + "\n".join(captioned_notes)

        # Videos: never inlined — the `video` skill pushes them into the sandbox
        # and shows the model sampled frames on demand. Announce them by short
        # id (+ what's already known, for a re-asked video).
        if state["videos"]:
            lines = []
            for v in state["videos"]:
                line = f"- vid_{v['id'][:8]} ({_fmt_duration(v['duration_s'])})"
                if v["summary"]:
                    line += f" — previously watched: {v['summary']}"
                lines.append(line)
            hint = (" Load the `video` skill to watch it (probe → overview → frames)."
                    if "video" in self.available else "")
            user_turn += (f"\n\n({len(lines)} video(s) attached to this note.{hint})\n"
                          + "\n".join(lines))

        # Resuming a paused run: point the model at the checkpoint instead of
        # starting fresh. (state["run_id"]/["run_dir"] are also set so the
        # continuation controller below targets the right run.)
        resume_seed_msgs: list[ChatMessage] = []
        if resume_run_id:
            state["run_id"] = resume_run_id
            state["run_dir"] = f"/workspace/runs/{resume_run_id}"
            resume_seed_msgs = [ChatMessage(role="system", content=_resume_seed(resume_run_id))]

        rewritten = (
            [snapshot_msg]
            + resume_seed_msgs
            + list(messages[:-1])
            + [
                ChatMessage(
                    role="user",
                    content=user_turn,
                    images=inline_b64 or None,
                )
            ]
        )

        # --- auto-continue controller -------------------------------------
        # A checkpointed run (one that called vm_new_run) that hits the tool-call
        # cap mid-task is re-seeded from its STATE.json and continued, up to
        # settings.max_auto_continue extra passes. Compaction = a fresh, lean
        # convo pointing at the run dir; the VM filesystem is the real memory.
        # After the ceiling, resume_handle is set and surfaced for one-tap resume.
        from app.diagnostics import progress_publish

        passes = 1 + max(0, _settings.max_auto_continue)
        resume_handle: dict | None = None
        convo = rewritten
        result = await inner.arun(convo, session_id=session_id)
        for attempt in range(1, passes):
            # "length" = hit the tool-call ceiling; "error" = a model/transport
            # failure mid-loop. Both get a fresh lean compaction pass for a
            # checkpointed run — a reseed from STATE.json often clears whatever
            # (overflowed context, flaky call) tripped the previous pass.
            if (result.finish_reason not in ("length", "error")
                    or state["resolve"] or state["silent"]
                    or not state.get("run_dir")):
                break   # finished, signalled completion, or no run to resume
            rid = state.get("run_id") or resume_run_id
            progress_publish(session_id, {
                "type": "progress", "phase": "continue",
                "n": attempt + 1, "of": passes,
                "label": f"Continuing research… (pass {attempt + 1}/{passes})",
            })
            # A user turn is required: Ollama's chat template rejects a
            # system-only conversation with HTTP 500 "no user query found in
            # messages", which would 500 every compaction pass.
            convo = [
                ChatMessage(role="system", content=_resume_seed(rid)),
                ChatMessage(role="user", content="Continue from the checkpoint now."),
            ]
            result = await inner.arun(convo, session_id=session_id)
        # Ceiling reached, or a model failure, while still incomplete -> hand off
        # to one-tap resume rather than failing the run. The VM filesystem + last
        # vm_checkpoint are the real memory, so Continue picks up from there.
        if (result.finish_reason in ("length", "error")
                and not state["resolve"] and not state["silent"]
                and state.get("run_dir")):
            rid = state.get("run_id") or resume_run_id
            if rid:
                resume_handle = {
                    "run_id": rid,
                    "run_dir": state.get("run_dir") or f"/workspace/runs/{rid}",
                    "label": "Research paused — tap to continue",
                }

        # Activity trace for the client's progress accordion: skills loaded,
        # tools called, and (if the user opted in) the model's reasoning.
        steps = _build_steps(state["loaded"], show_thinking, show_tools)

        # When we hand off to one-tap resume, give a clean lead-in rather than
        # the runtime's raw cap text (OpenRouter returns a "[gateway] max tool
        # iterations exceeded" sentinel).
        reply_text = result.text
        if resume_handle and (not (reply_text or "").strip()
                              or (reply_text or "").startswith("[gateway]")):
            reply_text = (
                "I've paused this research at a checkpoint — tap Continue to pick "
                "up where it left off."
            )
        elif (result.finish_reason == "error" and not (reply_text or "").strip()):
            # A non-research turn whose model call failed with nothing to show.
            reply_text = (
                "Something went wrong partway through and I couldn't finish this "
                "one — please try again."
            )

        # Video recall: once the agent has watched a video, keep a short text
        # summary of what it found on the video's record, so a later turn (or a
        # re-ask) knows what's in it without re-watching — the video analogue
        # of an image caption. Never fails the turn.
        watched = state.get("videos_watched") or set()
        if watched and not state["silent"] and state.get("app_state") is not None:
            basis = reply_text or ""
            if len(basis.strip()) < 120 and state["artifacts"]:
                basis = str(state["artifacts"][0].get("text") or basis)
            summary = re.sub(r"[#*_`>|\[\]]+", " ", basis)
            summary = re.sub(r"\s+", " ", summary).strip()[:400]
            if summary:
                for sha in watched:
                    try:
                        await state["app_state"].set_media_summary(sha, summary)
                    except Exception:  # noqa: BLE001
                        pass

        # Structured citations. Record the links the model itself wrote (e.g. a
        # page it read in the sandbox), then flag which sources each text
        # actually cites: the message gets the turn's list relative to its reply
        # + documents; each artifact gets its own, relative to its body.
        from app import sources as _sources

        texts = [reply_text or ""] + [str(a.get("text") or "") for a in state["artifacts"]]
        for t in texts:
            _sources.capture_links(state, t)
        message_sources = _sources.for_text(state, "\n".join(texts))
        for a in state["artifacts"]:
            a["sources"] = _sources.for_text(state, str(a.get("text") or ""))

        wrapped = json.dumps(
            {
                "reply": None if state["silent"] else reply_text,
                "ops": state["ops"],
                "resolve_thread": state["resolve"],
                "no_response": state["silent"],
                # None unless the canvas skill rendered a widget this turn.
                "canvas": None if state["silent"] else state["canvas"],
                # Long-form documents the artifact skill published this turn.
                # Empty list unless publish_artifact was called.
                "artifacts": [] if state["silent"] else state["artifacts"],
                # Set only when a checkpointed run hit the auto-continue ceiling
                # still incomplete: the client shows a one-tap "Continue" card.
                "resume": None if state["silent"] else resume_handle,
                # Drafted schedule proposals awaiting the user's approval.
                "schedule_proposals": [] if state["silent"] else state["schedule_proposals"],
                # Drafted learned-wiki entries awaiting approval (the wiki skill
                # appends these; previously never emitted, so they were lost).
                "wiki_proposals": [] if state["silent"] else state.get("wiki_proposals", []),
                # Sources behind this reply — cited first, each with n, url,
                # title, domain, snippet, via, cited. Empty if none consulted.
                "sources": [] if state["silent"] else message_sources,
                # What the assistant did this turn (post-hoc), for the client's
                # collapsible activity view. Empty list if nothing notable.
                "steps": steps,
            }
        )
        return AgentResult(
            text=wrapped,
            finish_reason=result.finish_reason,
            usage=result.usage,
        )


# Tools the model uses to steer the loop, not "work" worth showing as activity.
_CONTROL_TOOLS = {"load_skill", "resolve_thread", "no_response"}


def _build_steps(loaded: set, show_thinking: bool, show_tools: bool) -> list[dict]:
    """Derive a compact, chronological activity list from this request's trace:
    thinking per round, skills loaded, and tools called. Falls back to the
    loaded-skill set if no trace is active (e.g. a direct unit call)."""
    from app import diagnostics as diag

    from app import progress as prog

    tr = diag.current_trace.get()
    steps: list[dict] = []
    if tr is None:
        if show_tools:
            steps = [
                {"kind": "skill", "name": sid, "label": prog.label_for("skill", sid)}
                for sid in sorted(loaded)
            ]
        return steps

    for call in tr.calls:
        if show_thinking:
            txt = (call.thinking or "").strip()
            if txt:
                steps.append({"kind": "think", "text": txt[:800]})
        for t in call.tools:
            if t.name == "load_skill":
                if not show_tools:
                    continue
                sid = ""
                if isinstance(t.args, dict):
                    sid = str(t.args.get("skill_id", "") or "")
                steps.append(
                    {"kind": "skill", "name": sid or "skill",
                     "label": prog.label_for("skill", sid)}
                )
            elif t.name in _CONTROL_TOOLS:
                continue
            elif show_tools:
                steps.append(
                    {"kind": "tool", "name": t.name, "ok": t.error is None,
                     "label": prog.label_for("tool", t.name)}
                )
    return steps


def _resume_seed(run_id: str | None) -> str:
    """The system instruction that re-attaches the model to a paused run. The
    VM filesystem (STATE.json / PLAN.md / work/ / output/) is the real memory,
    so a continuation starts from a lean convo pointing here instead of
    re-feeding the whole prior transcript."""
    rid = run_id or ""
    run_dir = f"/workspace/runs/{rid}" if rid else "the run's folder"
    return (
        f"You are RESUMING run {rid}. Load the `vm` skill if it isn't loaded, then "
        f"FIRST read {run_dir}/STATE.json (and {run_dir}/PLAN.md) and continue from "
        f"its `next`. Do NOT call vm_new_run and do NOT redo completed steps — the "
        f"files in {run_dir}/work and {run_dir}/output already exist; reuse them. "
        f"Call vm_checkpoint after each step, update {run_dir}/GOAL.md, and call "
        f"resolve_thread once the whole request is satisfied."
    )


_MAX_THREAD_DOCS = 8
_MAX_DOC_CHARS = 120_000


def _parse_thread_docs(messages: list[ChatMessage]) -> dict:
    """The thread's earlier documents from the envelope's `artifacts` field
    (newest first): id -> {title, text, sources}. Empty when absent."""
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    try:
        payload = json.loads(last_user)
    except (json.JSONDecodeError, TypeError):
        return {}
    if not isinstance(payload, dict) or not isinstance(payload.get("artifacts"), list):
        return {}
    docs: dict = {}
    for a in payload["artifacts"]:
        if not isinstance(a, dict) or not a.get("id") or not a.get("text"):
            continue
        docs[str(a["id"])] = {
            "title": str(a.get("title") or "Document")[:200],
            "text": str(a["text"])[:_MAX_DOC_CHARS],
            "sources": a.get("sources") if isinstance(a.get("sources"), list) else [],
        }
        if len(docs) >= _MAX_THREAD_DOCS:
            break
    return docs


def _parse_videos(messages: list[ChatMessage]) -> list[dict]:
    """The note's video attachments from the envelope's `videos` field:
    [{id (sha), duration_s, summary?}]."""
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    try:
        payload = json.loads(last_user)
    except (json.JSONDecodeError, TypeError):
        return []
    vids = payload.get("videos") if isinstance(payload, dict) else None
    out = []
    for v in vids if isinstance(vids, list) else []:
        if isinstance(v, dict) and v.get("id"):
            out.append({"id": str(v["id"]), "duration_s": float(v.get("duration_s") or 0),
                        "summary": str(v.get("summary") or "")})
    return out[:3]


def _fmt_duration(s: float) -> str:
    s = int(round(s or 0))
    return f"{s // 60}:{s % 60:02d}"


def _payload_str(messages: list[ChatMessage], key: str) -> str | None:
    """A string field from the final user message's JSON envelope, if any."""
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    try:
        payload = json.loads(last_user)
    except (json.JSONDecodeError, TypeError):
        return None
    val = payload.get(key) if isinstance(payload, dict) else None
    return str(val) if val else None


def _doc_index_line(doc_id: str, d: dict) -> str:
    heads = [ln.lstrip("#").strip() for ln in d["text"].splitlines()
             if ln.startswith("#") and ln.lstrip("#").startswith(" ")]
    outline = "; ".join(h[:60] for h in heads[:10])
    return (f"- \"{d['title']}\" [id: {doc_id}] — {len(d['text']):,} chars"
            + (f"; sections: {outline}" if outline else ""))


def _parse_payload(
    messages: list[ChatMessage],
) -> tuple[str, list, list, list, str | None, list, str | None, str | None, str | None, bool, bool, str | None]:
    """Extract (prompt, lists_snapshot, notes, goals, scoped_goal, images,
    model_override, provider_override, scoped_list) from the last user message.

    `provider` is carried explicitly rather than inferred from the model
    string — Ollama tags (`qwen3.8:latest`) and OpenRouter slugs
    (`anthropic/claude-sonnet-4.5`) are far too easy to confuse by parsing.

    Same tolerant JSON-envelope contract ListManagerRuntime uses. Falls back
    to (raw content, [], [], [], None, [], None, None) if the message isn't a
    JSON dict — the agent can still respond, just without preloaded state.
    """
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    try:
        payload = json.loads(last_user)
        if isinstance(payload, dict):
            scoped = payload.get("scoped_goal")
            scoped_list = payload.get("scoped_list")
            model = payload.get("model")
            provider = payload.get("provider")
            # Display-only flags: whether the client wants reasoning / tool
            # activity in the steps trace. Absent => include both (useful for
            # direct curl tests); the app sends explicit booleans.
            show_thinking = bool(payload.get("show_thinking", True))
            show_tools = bool(payload.get("show_tools", True))
            resume_run_id = payload.get("resume_run_id")
            return (
                str(payload.get("prompt", last_user)),
                list(payload.get("lists", [])),
                list(payload.get("notes", [])),
                list(payload.get("goals", [])),
                str(scoped) if scoped else None,
                list(payload.get("images", [])),
                str(model) if model else None,
                str(provider) if provider else None,
                str(scoped_list) if scoped_list else None,
                show_thinking,
                show_tools,
                str(resume_run_id) if resume_run_id else None,
            )
    except (json.JSONDecodeError, TypeError):
        pass
    return last_user, [], [], [], None, [], None, None, None, True, True, None
