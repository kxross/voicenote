"""Long-poll + status fields on GET /v1/pending.

    docker exec -e PYTHONPATH=/app -w /app agent-gateway python /app/app/test_pending_longpoll.py
"""

import asyncio
import os
import tempfile
import time

from app.state import State


async def main() -> None:
    d = tempfile.mkdtemp()
    st = State(os.path.join(d, "t.db"))
    await st.open()
    sid = "s1"

    # 1. A waiter that took the event before listing is woken by a delivery.
    ev = st.delivery_event(sid)
    assert await st.list_pending(sid) == []

    async def later():
        await asyncio.sleep(0.3)
        await st.queue_delivery(sid, "t", "hello", [], {"thread_id": 7})

    t0 = time.monotonic()
    asyncio.create_task(later())
    woke = await st.wait_event(ev, 5)
    dt = time.monotonic() - t0
    assert woke and dt < 2, (woke, dt)
    got = await st.list_pending(sid)
    assert len(got) == 1 and got[0]["thread_id"] == 7, got
    print(f"ok  waiter woke in {dt:.2f}s")

    # 2. A delivery queued between taking the event and waiting is not missed.
    ev = st.delivery_event(sid)
    await st.queue_delivery(sid, "t", "second", [])
    assert await st.wait_event(ev, 0.1), "event already fired must return True at once"
    print("ok  no missed wake-up")

    # 3. Timeout returns False; other sessions don't wake us.
    ev = st.delivery_event(sid)
    await st.queue_delivery("other", "t", "x", [])
    t0 = time.monotonic()
    assert not await st.wait_event(ev, 0.5)
    assert 0.4 < time.monotonic() - t0 < 1.5
    print("ok  timeout + session isolation")

    # 4. Endpoint shape (handler called directly).
    from app import main as m
    from app import scheduler as sch

    class _Sched:
        def next_run_time(self, _id):
            return None

    m.app.state.state = st
    m.app.state.scheduler = _Sched()
    m._async_inflight.add((sid, 42))
    sch.running[sid] = 1
    try:
        r = await m.get_pending(sid, wait=0, _=None)
        assert set(r) == {"deliveries", "busy_threads", "scheduled_running", "next_due"}, r.keys()
        assert r["busy_threads"] == [42] and r["scheduled_running"] is True, r
        assert r["next_due"] is None and len(r["deliveries"]) == 2, r
        # Long-poll through the handler: empty session, delivery arrives.
        async def later2():
            await asyncio.sleep(0.3)
            await st.queue_delivery("s2", "t", "lp", [])
        asyncio.create_task(later2())
        t0 = time.monotonic()
        r2 = await m.get_pending("s2", wait=10, _=None)
        assert len(r2["deliveries"]) == 1 and time.monotonic() - t0 < 2, r2
        assert r2["busy_threads"] == [] and r2["scheduled_running"] is False
        print("ok  endpoint shape + handler long-poll")
    finally:
        m._async_inflight.discard((sid, 42))
        sch.running.pop(sid, None)
        await st.close()
    print("ALL PASS")


if __name__ == "__main__":
    asyncio.run(main())
