# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""A small NIP-01 relay pool with NIP-42 AUTH (spec/ogs-nostr.md §8). asyncio + websockets."""
from __future__ import annotations

import asyncio
import json
from typing import Callable

import websockets

from . import events as E
from .nostr import Keys


class Pool:
    def __init__(self, urls: list[str], on_event: Callable[[dict, str], None], auth_key: Keys | None = None,
                 log: Callable[[str], None] = print):
        self.urls = list(dict.fromkeys(urls))
        self.on_event = on_event
        self.auth_key = auth_key
        self.log = log
        self.subs: dict[str, dict] = {}
        self.conns: dict[str, websockets.ClientConnection] = {}
        self.oks: dict[str, list[tuple[str, bool, str]]] = {}
        self._tasks: list[asyncio.Task] = []

    async def start(self):
        self._tasks = [asyncio.create_task(self._run(u)) for u in self.urls]
        for _ in range(50):
            if self.conns:
                break
            await asyncio.sleep(0.1)

    async def ready(self, timeout: float = 8.0):
        """Wait until every relay is connected (or the timeout)."""
        for _ in range(int(timeout * 10)):
            if len(self.conns) >= len(self.urls):
                return
            await asyncio.sleep(0.1)

    async def close(self):
        for t in self._tasks:
            t.cancel()
        for c in list(self.conns.values()):
            await c.close()

    async def _run(self, url: str):
        while True:
            try:
                async with websockets.connect(url, open_timeout=10, max_size=2 ** 22) as ws:
                    self.conns[url] = ws
                    for sid, flt in self.subs.items():
                        await ws.send(json.dumps(["REQ", sid, flt]))
                    async for raw in ws:
                        await self._handle(url, ws, json.loads(raw))
            except asyncio.CancelledError:
                return
            except Exception as e:  # noqa: BLE001 - keep the other relays going
                self.log(f"  {url}: {type(e).__name__} {e}"[:160])
            self.conns.pop(url, None)
            await asyncio.sleep(5)

    async def _handle(self, url, ws, msg):
        typ = msg[0]
        if typ == "EVENT" and len(msg) >= 3:
            self.on_event(msg[2], url)
        elif typ == "OK":
            self.oks.setdefault(msg[1], []).append((url, bool(msg[2]), msg[3] if len(msg) > 3 else ""))
        elif typ == "AUTH" and self.auth_key is not None:
            await ws.send(json.dumps(["AUTH", E.auth(self.auth_key, url, msg[1])]))
        elif typ == "CLOSED" and "auth-required" in (msg[2] if len(msg) > 2 else ""):
            await asyncio.sleep(1)              # AUTH was answered on the challenge; re-issue the REQ
            if msg[1] in self.subs:
                await ws.send(json.dumps(["REQ", msg[1], self.subs[msg[1]]]))
        elif typ == "NOTICE":
            self.log(f"  {url} NOTICE {msg[1]}"[:160])

    async def req(self, sid: str, flt: dict):
        self.subs[sid] = flt
        for ws in list(self.conns.values()):
            try:
                await ws.send(json.dumps(["REQ", sid, flt]))
            except Exception:  # noqa: BLE001
                pass

    async def publish(self, ev: dict, wait: float = 5.0) -> list[tuple[str, bool, str]]:
        for ws in list(self.conns.values()):
            try:
                await ws.send(json.dumps(["EVENT", ev]))
            except Exception:  # noqa: BLE001
                pass
        for _ in range(int(wait * 10)):
            if len(self.oks.get(ev["id"], [])) >= len(self.conns):
                break
            await asyncio.sleep(0.1)
        return self.oks.get(ev["id"], [])
