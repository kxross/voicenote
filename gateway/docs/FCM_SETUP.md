# FCM (fast_lane) setup

Real-time push delivery via Firebase Cloud Messaging. Today the gateway
delivers scheduled-agent replies via the slow_lane (`PollingPusher` →
`GET /v1/pending/{session}`), which Android drains on a 15-minute
`PeriodicWorkRequest`. FCM cuts that latency to seconds.

The plumbing is already in place — `FcmPusher` is registered next to
`PollingPusher` in `app/main.py` and receives every delivery the
scheduler fans out. Its `deliver()` is currently a no-op stub. Follow
the three steps below to flip it on.

---

## 1. Create a Firebase project + service account

1. https://console.firebase.google.com → **Add project**.
2. **Project settings → Service accounts → Generate new private key**.
   Save the downloaded JSON as `firebase-service-account.json` and mount
   it into the gateway container (e.g. via a Docker secret or a
   bind-mount that isn't committed to Git).
3. **Cloud Messaging** — enable if it isn't already.

## 2. Wire the gateway

```bash
# add to requirements.txt
firebase-admin>=6.5

# add to docker-compose.yml gateway service env
FIREBASE_CREDENTIALS: /run/secrets/firebase-service-account.json
```

Fill in `app/push/pusher.py::FcmPusher.deliver`:

```python
import firebase_admin
from firebase_admin import credentials, messaging

class FcmPusher(Pusher):
    def __init__(self, credentials_path: str, token_lookup):
        firebase_admin.initialize_app(credentials.Certificate(credentials_path))
        self._token_lookup = token_lookup  # session_id -> device token

    async def deliver(self, session_id, title, body, ops):
        token = await self._token_lookup(session_id)
        if not token:
            log.info("[FcmPusher] no device token for %s", session_id)
            return
        message = messaging.Message(
            token=token,
            data={
                "title": title,
                "body": body,
                "ops_json": json.dumps(ops),
            },
        )
        # firebase-admin is sync — run in a thread pool.
        await asyncio.to_thread(messaging.send, message)
```

`token_lookup` should read from a `device_tokens(session_id, token)`
table (add to `app/state.py` alongside snapshots and
pending_deliveries).

## 3. Wire Android

1. Add `com.google.firebase:firebase-messaging` to `app/build.gradle`.
2. Add the `com.google.gms:google-services` plugin + your Firebase
   project's `google-services.json` under `app/`.
3. Create a `FirebaseMessagingService` subclass that on
   `onNewToken(token)` POSTs to a new `/v1/device/register` endpoint
   (build it alongside the FCM changes) with `{session_id, token}`.
4. In `onMessageReceived(msg)`, hand the data payload to the **same**
   helpers the polling worker uses:
   ```kotlin
   val ops = JSONArray(msg.data["ops_json"])
   NoteDatabase.get(this).withTransaction {
       GatewayAgent.applyOpsJson(NoteDatabase.get(this).listDao(), ops)
   }
   GatewayAgent.postReplyNotification(this,
       title = msg.data["title"] ?: "Agent",
       reply = msg.data["body"] ?: "")
   ```
5. Register the service in `AndroidManifest.xml` with the FCM intent
   filter.

## Quick sanity test after wiring up

```bash
curl -X POST http://localhost:5001/v1/schedule/morning_groceries/run
```

Both `PollingPusher` and `FcmPusher` should fire. The FCM message
lands on-device within seconds; the polling entry is also there as a
safety net if the phone was offline when FCM fired.
