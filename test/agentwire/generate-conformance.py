#!/usr/bin/env python3
"""Regenerate motd's Agentwire conformance corpus from the upstream implementation.

The corpus is the cross-implementation oracle: every envelope is built and encoded by
agentwire's own `protocol.py`, and every expected state is produced by agentwire's own
reference renderer. A motd test that disagrees with this file disagrees with the bridge.

Run it from the agentwire checkout's dev shell, pointing at this repo:

    cd ~/Workspace/agentwire
    nix develop -c sh -c 'PYTHONPATH=src python ~/Workspace/motd/test/agentwire/generate-conformance.py'

Nothing in motd's build depends on Python: the generated JSON is committed and the Kotlin
tests read it as a resource.
"""

from __future__ import annotations

import copy
import json
import pathlib

from agentwire.protocol import Envelope, encode_envelope, fragment_envelope
from agentwire.reference_client import ProtocolClient

TOPIC = "agentwire:v1;account=trev;agent=agentwire;backend=claude | motd conformance"

EPOCH = "epoch-conformance"
INSTANCE = "11111111-1111-4111-8111-111111111111"
SESSION = "sess-conformance"
TURN = "turn-1"

# Envelope ids are fixed so the corpus is byte-stable across regenerations.
_next_id = 0


def envelope(kind: str, **kwargs) -> Envelope:
    """Build one canonical event. Ids and timestamps are fixed, never generated."""
    global _next_id
    _next_id += 1
    identifier = f"{_next_id:08d}-0000-4000-8000-000000000000"
    kwargs.setdefault("epoch", EPOCH)
    return Envelope(
        kind=kind,
        message_type="event",
        id=identifier,
        at=1785400000000 + _next_id,
        instance=INSTANCE,
        **kwargs,
    )


def claude_session() -> list[Envelope]:
    """A realistic Claude turn: handshake, binding, prompt, tools, plan, answer, request."""
    return [
        envelope(
            "agent.hello",
            data={
                "protocol": "agentwire-irc-v1",
                "backend": "claude",
                "epoch": EPOCH,
                "capabilities": [
                    "history", "queues", "requests", "sessions", "settings",
                    "steering", "sync", "turns", "workspaces",
                ],
                # The live bridge advertises `actions`, and motd gates every outbound
                # action on it. The abridged upstream fixtures omit it, so a corpus
                # without it would not exercise the path that matters most.
                "actions": [
                    "sync.request", "workspace.list.request", "session.list.request",
                    "history.request", "session.create", "session.attach", "session.detach",
                    "settings.update", "turn.prompt", "turn.steer", "turn.cancel",
                    "queue.edit", "queue.move", "queue.delete", "queue.clear",
                    "request.respond", "request.skip",
                ],
                "limits": {
                    "contentBytes": 65536,
                    "queueItems": 10,
                    "historyEvents": 200,
                    "historyBytes": 524288,
                    "historyDays": 30,
                },
                # Claude takes its model from deployment config, so it accepts delivery alone.
                "settings": ["delivery"],
            },
        ),
        envelope(
            "channel.snapshot",
            data={
                "active": True,
                "backend": "claude",
                "binding": {"sid": SESSION, "cwd": "/home/trev/Workspace/motd"},
                "busy": False,
                "tid": None,
                "settings": {"delivery": "queue"},
                "requests": [],
                "queue": [],
            },
        ),
        envelope(
            "workspace.page",
            data={
                "parent": None,
                "items": [
                    {"path": "/home/trev/Workspace", "name": "Workspace", "hasChildren": True},
                ],
                "cursor": None,
                "next": None,
            },
        ),
        envelope(
            "session.page",
            data={
                "scope": "live",
                "cwd": None,
                "items": [
                    {
                        "sid": SESSION,
                        "cwd": "/home/trev/Workspace/motd",
                        "busy": False,
                        "flags": [],
                        # Claude has no attachable TUI; this must stay false.
                        "tuiAttached": False,
                    },
                ],
                "cursor": None,
                "next": None,
            },
        ),
        envelope("binding.changed", session_id=SESSION, data={"sid": SESSION, "cwd": "/home/trev/Workspace/motd"}),
        envelope(
            "session.snapshot",
            session_id=SESSION,
            data={
                "cwd": "/home/trev/Workspace/motd",
                "busy": False,
                "flags": [],
                "tuiAttached": False,
                "status": "ready",
                "recentOutputs": [],
            },
        ),
        envelope("turn.started", session_id=SESSION, turn_id=TURN, data={}),
        envelope(
            "user.prompt",
            session_id=SESSION,
            turn_id=TURN,
            item_id="prompt-1",
            data={"content": "why does the test fail?"},
        ),
        envelope(
            "plan.updated",
            session_id=SESSION,
            turn_id=TURN,
            item_id="plan-1",
            data={
                "plan": True,
                "running": True,
                "status": "inProgress",
                "completedSteps": 0,
                "totalSteps": 2,
                "summary": "Read the failing test",
            },
        ),
        envelope(
            "tool.started",
            session_id=SESSION,
            turn_id=TURN,
            item_id="call-1",
            data={"kind": "file read", "label": "file read", "input": "AgentwireStateTest.kt"},
        ),
        envelope(
            "tool.completed",
            session_id=SESSION,
            turn_id=TURN,
            item_id="call-1",
            data={
                "kind": "file read",
                "label": "file read",
                "success": True,
                "output": "assertEquals(expected, actual)",
                "durationMs": 12,
            },
        ),
        envelope(
            "request.opened",
            session_id=SESSION,
            turn_id=TURN,
            request_id="req-1",
            data={
                "type": "question",
                "canSkip": True,
                "inactive": False,
                "questions": [
                    {
                        "id": "q1",
                        "header": "Scope",
                        "prompt": "Fix the test or the code?",
                        "options": ["test", "code"],
                        "multiple": False,
                        "custom": False,
                    },
                ],
            },
        ),
        envelope("request.resolved", session_id=SESSION, turn_id=TURN, request_id="req-1", data={}),
        envelope(
            "plan.updated",
            session_id=SESSION,
            turn_id=TURN,
            item_id="plan-1",
            data={
                "plan": True,
                "running": False,
                "status": "completed",
                "completedSteps": 2,
                "totalSteps": 2,
                "summary": "Read the failing test",
            },
        ),
        envelope(
            "assistant.completed",
            session_id=SESSION,
            turn_id=TURN,
            item_id="msg-1",
            data={"content": "The assertion arguments are inverted."},
        ),
        envelope("usage.updated", session_id=SESSION, turn_id=TURN, data={"inputTokens": 1200, "outputTokens": 340}),
        envelope("turn.completed", session_id=SESSION, turn_id=TURN, data={}),
        envelope("session.status", session_id=SESSION, data={"busy": False, "status": "ready"}),
    ]


def queue_and_failure() -> list[Envelope]:
    """Queue maintenance and the acknowledgement kinds, which the session above never reaches."""
    return [
        envelope(
            "queue.snapshot",
            session_id=SESSION,
            data={"items": [{"iid": "q-1", "content": "run the tests", "position": 0}]},
        ),
        envelope(
            "queue.item.added",
            session_id=SESSION,
            data={"iid": "q-2", "content": "then lint", "position": 1},
        ),
        envelope(
            "queue.item.updated",
            session_id=SESSION,
            data={"iid": "q-2", "content": "then lint everything", "position": 1},
        ),
        envelope("queue.item.moved", session_id=SESSION, data={"iid": "q-2", "position": 0}),
        envelope("queue.item.removed", session_id=SESSION, data={"iid": "q-2"}),
        envelope("action.accepted", reply="00000000-0000-4000-8000-00000000cafe", data={}),
        envelope("action.succeeded", reply="00000000-0000-4000-8000-00000000cafe", data={}),
        envelope(
            "action.failed",
            reply="00000000-0000-4000-8000-00000000beef",
            # Exactly what a followed Claude session answers when asked to steer.
            data={"message": "Claude session is observed only; its turn belongs to another process"},
        ),
        envelope("action.uncertain", reply="00000000-0000-4000-8000-00000000f00d", data={}),
        envelope("turn.failed", session_id=SESSION, turn_id="turn-2", data={"message": "interrupted"}),
    ]


def main() -> int:
    # The corpus lives in :app because its most valuable assertion compares motd's reducer with
    # the reference renderer, and :app can reach the :irc codec while the reverse is not true.
    out_dir = pathlib.Path(__file__).resolve().parents[2] / "app/src/test/resources/agentwire/conformance"
    out_dir.mkdir(parents=True, exist_ok=True)

    for name, builder in (("claude-session", claude_session), ("queue-and-acks", queue_and_failure)):
        client = ProtocolClient(device="device-conformance", instance=INSTANCE)
        activation = client.set_topic(TOPIC)
        assert activation is not None, "the conformance topic must activate"
        steps = []
        for env in builder():
            tag = encode_envelope(env)
            decoded = client.ingest(tag)
            assert decoded is not None, f"reference client rejected {env.kind}"
            # to_dict() hands back its live containers, so a snapshot has to be deep-copied or
            # every step would record the final state.
            steps.append({"kind": env.kind, "tag": tag, "state": copy.deepcopy(client.state.to_dict())})
        path = out_dir / f"{name}.json"
        path.write_text(json.dumps({"topic": TOPIC, "steps": steps}, indent=2, sort_keys=True) + "\n")
        print(f"wrote {path} ({len(steps)} steps)")

    # A payload large enough to force base64url fragmentation on both sides.
    big = envelope(
        "assistant.completed",
        session_id=SESSION,
        turn_id=TURN,
        item_id="msg-big",
        data={"content": "λ" * 8000},
    )
    fragments = fragment_envelope(big)
    assert len(fragments) > 1, "expected the oversized envelope to fragment"
    path = out_dir / "fragmented.json"
    path.write_text(
        json.dumps({"envelope": encode_envelope(big), "fragments": fragments}, indent=2, sort_keys=True) + "\n"
    )
    print(f"wrote {path} ({len(fragments)} fragments)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
