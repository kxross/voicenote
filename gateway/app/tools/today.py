"""`today` tool — current date, time, and timezone.

Gives the model a reliable "now" (it has no clock of its own). The timezone is
whatever the gateway container runs in (the TZ env, default Pacific/Honolulu),
so answers match the user's wall clock. Framework-agnostic, like the other
tools: `OLLAMA_TOOL_SCHEMA` is the shared definition, `today` is the plain async
function the tool loops call.
"""

from __future__ import annotations

import os
import time
from datetime import datetime

OLLAMA_TOOL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "today",
        "description": (
            "Get the current date, time, and timezone. Call this whenever the "
            "answer depends on 'now' — today's date, the current time, the day "
            "of the week, or anything relative like 'tomorrow' or 'in 3 days'."
        ),
        # No inputs. Empty properties is a valid JSON schema the tool loops accept.
        "parameters": {"type": "object", "properties": {}},
    },
}


async def today() -> str:
    now = datetime.now().astimezone()
    tz_name = os.environ.get("TZ") or now.tzname() or time.tzname[0]
    off = now.strftime("%z")  # e.g. -1000
    offset = f"UTC{off[:3]}:{off[3:]}" if off else "UTC"
    return (
        "Current date & time:\n"
        f"- Date: {now:%A, %B %d, %Y}\n"
        f"- Time: {now:%H:%M:%S} ({now:%I:%M %p})\n"
        f"- Timezone: {tz_name} ({offset})\n"
        f"- ISO 8601: {now.isoformat()}"
    )
