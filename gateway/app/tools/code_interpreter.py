"""PLACEHOLDER — no sandboxing here on purpose. Do not execute the `code`
argument with bare `exec()`/`subprocess` in production; route it to an
actual sandbox (E2B, a gVisor/Firecracker microVM, a locked-down Docker
container with no network/filesystem access, etc.) before this does
anything real.
"""

OLLAMA_TOOL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "code_interpreter",
        "description": "Execute a snippet of Python and return stdout/stderr.",
        "parameters": {
            "type": "object",
            "properties": {
                "code": {"type": "string", "description": "Python source to run."}
            },
            "required": ["code"],
        },
    },
}


async def code_interpreter(code: str) -> str:
    # TODO: replace with a real sandboxed execution call, e.g.:
    #   sandbox = await e2b.Sandbox.create()
    #   result = await sandbox.run_code(code)
    #   return result.stdout + result.stderr
    return (
        "[code_interpreter stub] No sandbox configured — refusing to exec locally. "
        "Wire this up in app/tools/code_interpreter.py.\n"
        f"Code received ({len(code)} chars) was not run."
    )
