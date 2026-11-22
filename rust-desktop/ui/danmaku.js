// SPDX-License-Identifier: GPL-3.0-only
export const danmakuOptions = [
  ["danmaku_area", "显示区域", [[25, "顶部 1/4"], [50, "上半屏"], [100, "全屏"]]],
  ["danmaku_font", "弹幕字号", [16, 20, 24, 28, 32, 36, 40].map(size => [size, `${size} px`])],
  ["danmaku_opacity", "弹幕不透明度", [20, 40, 60, 80, 100].map(value => [value, `${value}%`])],
  ["danmaku_travel", "弹幕滚动时长", [5, 7, 9, 12, 15].map(value => [value, `${value} 秒`])],
];

export function attachDanmaku(stage, video, read, preferences, report) {
  const overlay = document.createElement("div");
  overlay.className = "danmaku-layer";
  overlay.setAttribute("aria-hidden", "true");
  stage.append(overlay);

  const lifecycle = new AbortController();
  let segment;
  let frame;
  let next;
  let cursor = null;
  let finished = false;
  let retryAt = 0;
  let pending = [];
  let visible = [];
  let width = 0;
  let height = 0;
  let hasError = false;

  function reset() {
    segment?.abort();
    segment = null;
    cancelAnimationFrame(frame);
    frame = null;
    overlay.replaceChildren();
    next = undefined;
    cursor = null;
    finished = false;
    pending = [];
    visible = [];
    retryAt = 0;
    if (hasError) report(false);
    hasError = false;
  }

  async function collect(start, duration) {
    const controller = new AbortController();
    segment = controller;
    try {
      let result;
      try {
        result = await read(start, duration, cursor, controller.signal);
      } catch (error) {
        if (controller.signal.aborted || !cursor || error.status !== 400) throw error;
        cursor = null;
        result = await read(start, duration, null, controller.signal);
      }
      if (controller.signal.aborted) return;
      const end = result.next_ms;
      if (!Number.isSafeInteger(end) || end <= start || end > duration) {
        throw new Error("弹幕时间段无效");
      }
      next = end;
      cursor = result.next_cursor;
      finished = !cursor;
      const now = video.currentTime * 1000;
      pending = pending.concat(result.items.filter(item => item.time_ms >= now - 1300))
        .sort((a, b) => a.time_ms - b.time_ms);
      if (hasError) report(false);
      hasError = false;
    } catch {
      if (controller.signal.aborted) return;
      retryAt = performance.now() + 5000;
      hasError = true;
      report(true);
    } finally {
      if (segment === controller) segment = null;
    }
  }

  function draw(now, settings) {
    const speed = width / (settings.danmaku_travel * 1000);
    const rowHeight = Math.ceil(settings.danmaku_font * 1.5);
    const lanes = Math.max(1, Math.min(16, Math.floor(height * settings.danmaku_area / 100 / rowHeight)));
    const occupied = new Set();
    visible = visible.filter(entry => {
      const left = width - (now - entry.time) * speed;
      const right = left + entry.width;
      if (right <= 0 || entry.lane >= lanes) {
        entry.element.remove();
        return false;
      }
      entry.element.style.transform = `translateX(${left}px)`;
      if (right > width - 28) occupied.add(entry.lane);
      return true;
    });
    while (pending.length && pending[0].time_ms <= now) {
      const item = pending.shift();
      if (now - item.time_ms > 1300) continue;
      let lane = 0;
      while (occupied.has(lane)) lane++;
      if (lane >= lanes) continue;
      const element = document.createElement("span");
      element.textContent = item.text;
      element.style.top = `${lane * rowHeight}px`;
      element.style.transform = `translateX(${width}px)`;
      overlay.append(element);
      visible.push({ element, lane, time: now, width: element.offsetWidth });
      occupied.add(lane);
    }
  }

  function tick() {
    frame = null;
    const settings = preferences();
    if (!settings.danmaku || video.seeking || video.ended || !Number.isFinite(video.duration) || !video.duration) return;
    const now = Math.floor(video.currentTime * 1000);
    const duration = Math.min(86_400_000, Math.ceil(video.duration * 1000));
    if (!segment && (next === undefined || next < now - 1000)) {
      next = Math.floor(now / 1000) * 1000;
      cursor = null;
    }
    if (!finished && !segment && next < duration && next - now <= 5000 && performance.now() >= retryAt) {
      collect(next, duration);
    }
    if (width > 0 && height > 0) draw(now, settings);
    if (!video.paused) frame = requestAnimationFrame(tick);
  }

  function wake() {
    if (frame == null && !lifecycle.signal.aborted) frame = requestAnimationFrame(tick);
  }

  function update() {
    reset();
    const settings = preferences();
    overlay.style.setProperty("--danmaku-font", `${settings.danmaku_font}px`);
    overlay.style.opacity = settings.danmaku_opacity / 100;
    if (settings.danmaku) wake();
  }

  const observer = new ResizeObserver(([entry]) => {
    width = entry.contentRect.width;
    height = entry.contentRect.height;
    wake();
  });
  observer.observe(overlay);
  const options = { signal: lifecycle.signal };
  for (const event of ["loadedmetadata", "play", "pause", "seeked", "timeupdate"]) {
    video.addEventListener(event, wake, options);
  }
  video.addEventListener("durationchange", () => {
    reset();
    wake();
  }, options);
  video.addEventListener("seeking", reset, options);
  video.addEventListener("ended", reset, options);
  update();
  return {
    update,
    destroy() {
      lifecycle.abort();
      observer.disconnect();
      reset();
      overlay.remove();
    },
  };
}
