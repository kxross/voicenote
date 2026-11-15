"""Runtime for the list_manager agent.

Composes OllamaRuntime rather than subclassing it, because the tools are
rebuilt per request (each request gets its own closure-bound state). A
shared subclass instance would race between concurrent sessions.

Wire format expected on the way in — the caller packs the current lists
snapshot into the last user message's `content` as a JSON string:

    {"prompt": "add milk to groceries",
     "lists":  [{"name": "groceries", "items": [{"text": "eggs", "done": false}]}, ...]}

Wire format produced on the way out — the assistant's `content` is a JSON
string the client parses and applies to its local database:

    {"reply": "Added milk to groceries.",
     "ops":   [{"type": "add_item", "list": "groceries", "text": "milk"}, ...]}
"""

import json

from app.agent_base import AgentResult, BaseAgentRuntime
from app.runtimes.ollama_runtime import OllamaRuntime
from app.schemas import ChatMessage
from app.tools.list_ops import build_list_tools


SYSTEM_PROMPT = """You are the user's lists manager. The user's message is a natural-language request about their lists (like "groceries", "workout", or "packing"). The user's current lists live on their device, not on the server.

The FULL current state of the user's lists is provided to you upfront in a separate system message immediately after this one — always consult it before deciding what to do. A get_lists tool is also available if you want to double-check, but the snapshot is already in your context.

Voice notes (transcripts the user has recorded) are NOT preloaded because there can be many of them. A get_notes tool is available for when the request references notes, journal entries, or things the user has said before — call it with a small `limit` first (10-20) and only fetch more if you need to.

You have tools to mutate the lists: create_list, delete_list, rename_list, add_item, remove_item, set_item_done. Calling these does NOT immediately change the lists — each call queues one operation, and all queued operations are sent to the device at the end of your turn.

You will be receiving a noisy block of text from the user, since it is a voice transcript.

Rules for when to mutate:
- use your discretion to decide whether to create, delete, rename, or add/remove items from lists.
- If the user asks to add items to a list that already exists (case-insensitively), reuse the existing list name — don't create a near-duplicate.
- If the user asks to add items to a list that does not exist, call create_list first, then add_item for each item.
- Match item text case-insensitively against existing items when the user references one.
- Prefer minimal edits — don't rewrite lists that don't need changing.

Read-only queries (do NOT call any mutating tools for these):
- Sometimes the message is a read-only question triggered by a geofence, like "I'm near {store name}. What should I pick up?". In that case, look through the snapshot and find the list whose name or contents most plausibly match the store — a grocery store (Foodland, Whole Foods, Safeway, Times, Target, Down to Earth, etc.) → the "groceries" list; a hardware store (Home Depot, Lowe's, City Mill) → a "hardware" or "home improvement" list; and so on.
- Reply with the list's NOT-done items as a short comma-separated sentence (e.g., "Pick up: milk, eggs, bread."). If the matching list is empty or every item is already done, say so briefly. If nothing in the snapshot plausibly matches the store, say you couldn't find a relevant list.

At the end, produce a short one-sentence natural-language reply. For mutations, confirm what you did; for read-only queries, list the items. This reply is shown to the user as a notification on their phone, so keep it short and friendly."""


class ListManagerRuntime(BaseAgentRuntime):
    def __init__(self, *, model: str, base_url: str | None = None) -> None:
        self.model = model
        self.base_url = base_url

    async def arun(
        self,
        messages: list[ChatMessage],
        *,
        session_id: str | None = None,
    ) -> AgentResult:
        prompt, snapshot, notes = _parse_payload(messages)
        state: dict = {"snapshot": snapshot, "notes": notes, "ops": []}
        tools = build_list_tools(state)

        inner = OllamaRuntime(
            model=self.model,
            base_url=self.base_url,
            system_prompt=SYSTEM_PROMPT,
            tools=tools,
        )

        snapshot_msg = ChatMessage(
            role="system",
            content="Current state of the user's lists (JSON):\n" + json.dumps(snapshot),
        )
        rewritten = (
            [snapshot_msg]
            + list(messages[:-1])
            + [ChatMessage(role="user", content=prompt)]
        )
        result = await inner.arun(rewritten, session_id=session_id)

        wrapped = json.dumps({"reply": result.text, "ops": state["ops"]})
        return AgentResult(text=wrapped, finish_reason=result.finish_reason, usage=result.usage)


def _parse_payload(messages: list[ChatMessage]) -> tuple[str, list, list]:
    """Extract (prompt, lists_snapshot, notes) from the last user message.

    Tolerant: if the content isn't JSON or is missing keys, treat the whole
    string as the prompt with empty snapshot + notes — the agent can still
    respond, just without knowledge of existing data.
    """
    last_user = next((m.content for m in reversed(messages) if m.role == "user"), "")
    try:
        payload = json.loads(last_user)
        if isinstance(payload, dict):
            return (
                str(payload.get("prompt", last_user)),
                list(payload.get("lists", [])),
                list(payload.get("notes", [])),
            )
    except (json.JSONDecodeError, TypeError):
        pass
    return last_user, [], []
