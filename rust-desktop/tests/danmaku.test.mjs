// SPDX-License-Identifier: GPL-3.0-only
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

const source = await readFile(new URL("../ui/danmaku.js", import.meta.url));
const { attachDanmaku } = await import("data:text/javascript;base64," + source.toString("base64"));

test("the overlay follows empty windows, resets on seek and stops at the end", async () => {
  const frames = new Map();
  let frameId = 0;
  globalThis.requestAnimationFrame = callback => {
    frames.set(++frameId, callback);
    return frameId;
  };
  globalThis.cancelAnimationFrame = id => frames.delete(id);
  globalThis.ResizeObserver = class {
    observe() {}
    disconnect() {}
  };
  globalThis.document = {
    createElement: () => ({ setAttribute() {}, replaceChildren() {}, remove() {}, style: { setProperty() {} } }),
  };
  const player = Object.assign(new EventTarget(), {
    currentTime: 0, duration: 88, paused: false, seeking: false, ended: false,
  });
  const calls = [];
  const pages = [
    { next_ms: 30_000, next_cursor: "window-2" },
    { next_ms: 60_000, next_cursor: "window-3" },
    { status: 400 },
    { next_ms: 88_000, next_cursor: null },
    { next_ms: 60_000, next_cursor: "after-seek" },
  ];
  const reports = [];
  const overlay = attachDanmaku({ append() {} }, player, async (start, duration, cursor) => {
    calls.push([start, duration, cursor]);
    const page = pages.shift();
    assert.ok(page);
    if (page.status) throw Object.assign(new Error("expired"), { status: page.status });
    return { ...page, items: [] };
  }, () => ({ danmaku: true }), unavailable => reports.push(unavailable));
  async function tick(seconds) {
    player.currentTime = seconds;
    const pending = [...frames.values()];
    frames.clear();
    for (const frame of pending) frame();
    await new Promise(resolve => setImmediate(resolve));
  }
  await tick(0);
  await tick(25);
  await tick(55);
  await tick(84);
  player.currentTime = 42;
  player.dispatchEvent(new Event("seeking"));
  player.dispatchEvent(new Event("seeked"));
  await tick(42);
  overlay.destroy();
  assert.deepEqual(calls, [
    [0, 88_000, null],
    [30_000, 88_000, "window-2"],
    [60_000, 88_000, "window-3"],
    [60_000, 88_000, null],
    [42_000, 88_000, null],
  ]);
  assert.deepEqual(reports, []);
});
