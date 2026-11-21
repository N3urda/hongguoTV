// SPDX-License-Identifier: GPL-3.0-only
import { frameOptions, rotationOptions, settingsPage, speedSteps } from "./settings.js";

const shell = document.querySelector("#shell");
const main = document.querySelector("#main");
const watch = document.querySelector("#watch");
const nav = [...document.querySelectorAll("nav [data-section]")];
const categories = [["real", "真人剧"], ["comic", "漫剧"], ["ai", "AI 剧"]];
const sectionNames = { home: "首页", feed: "随心看", browse: "找剧", search: "搜索", rank: "排行榜", library: "我的", settings: "设置" };
const boards = [["all", "热播榜"], ["real", "真人剧"], ["comic", "漫剧"], ["ai", "AI 剧"], ["new", "新剧榜"], ["searched", "热搜榜"], ["favorites", "收藏榜"]];
const groupSize = 20;
const state = {
  section: "home", category: "real", page: 1, query: "", libraryTab: "history",
  library: { favorites: [], history: [], later: [] }, detail: null, returnPoint: null,
  navigation: null, player: null, playing: null, prepare: null,
  stopping: Promise.resolve(), savingPreferences: Promise.resolve(),
  board: "all", suggestion: null,
  preferences: { theme: "dark", quality: 1080, speed: 100, frame: "fit", rotation: 0,
    show_progress: false, auto_next: true, startup: "home", feed_autoplay: true, clock: "controls" },
};

function node(tag, className, text) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (text) item.textContent = text;
  return item;
}

function icon(name) {
  const item = node("span", `icon icon-${name}`);
  item.setAttribute("aria-hidden", "true");
  return item;
}

function button(label, action, className, glyph) {
  const item = node("button", className, label);
  item.type = "button";
  if (glyph) {
    item.prepend(icon(glyph));
    if (label) item.classList.add("with-icon");
  }
  item.addEventListener("click", action);
  return item;
}

function namedControl(item, label) {
  item.setAttribute("aria-label", label);
  item.title = label;
  return item;
}

async function request(path, options = {}) {
  const headers = options.body ? { "Content-Type": "application/json" } : undefined;
  const reply = await fetch(path, { ...options, headers, cache: "no-store" });
  if (reply.status === 204) return null;
  if (!reply.ok) {
    const error = await reply.json().catch(() => null);
    const failure = new Error(error?.error || `请求失败（HTTP ${reply.status}）`);
    failure.status = reply.status;
    throw failure;
  }
  return reply.json();
}

function post(path, data, extra) {
  return request(path, { method: "POST", body: JSON.stringify(data), ...extra });
}

function poster(series, eager = false) {
  const frame = node("span", "poster");
  if (!series.cover) return frame;
  try {
    const url = new URL(series.cover);
    if (url.protocol !== "https:") return frame;
    const image = node("img");
    image.alt = "";
    image.decoding = "async";
    if (!eager) image.loading = "lazy";
    image.addEventListener("error", () => image.remove(), { once: true });
    image.src = url.href;
    frame.append(image);
  } catch { /* Keep the brand placeholder when the source has no usable cover. */ }
  return frame;
}

function status(message, retry, className = "empty") {
  const panel = node("div", className);
  panel.setAttribute("role", "status");
  const copy = node("div");
  copy.append(node("p", "", message));
  if (retry) copy.append(button("重试", retry));
  panel.append(copy);
  return panel;
}

function formatTime(milliseconds) {
  const seconds = Math.max(0, Math.floor(milliseconds / 1000));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

function historyLabel(history) {
  const episode = `第 ${history.number} 集`;
  const watched = history.from_tv ? "已看完" : "试看完毕";
  return history.completed ? episode + watched : `${episode} · ${formatTime(history.position)}`;
}

function card(series, key, history) {
  const item = button("", () => openDetail(series.id), "card");
  item.dataset.key = `${key}:${series.id}`;
  const copy = node("span", "card-copy");
  copy.append(node("span", "card-title clamp", series.title));
  const meta = history ? historyLabel(history) : series.badge;
  if (meta) copy.append(node("span", "card-meta", meta));
  item.append(poster(series), copy);
  if (history?.duration > 0) {
    const progress = node("progress", "card-progress");
    progress.max = history.duration;
    progress.value = history.position;
    progress.setAttribute("aria-hidden", "true");
    item.append(progress);
  }
  return item;
}

function shelf(title, key, cards, more, signal) {
  const section = node("section", "shelf");
  const heading = node("div", "section-heading");
  heading.append(node("h2", "", title));
  const actions = node("div", "section-actions");
  if (more) actions.append(button("查看全部", more, "quiet"));
  const track = node("div", "shelf-track");
  track.dataset.shelf = key;
  track.append(...cards);
  const move = (direction) => track.scrollBy({ left: track.clientWidth * .8 * direction });
  const previous = namedControl(button("", () => move(-1), "icon-button quiet", "left"), `${title}向左滚动`);
  const next = namedControl(button("", () => move(1), "icon-button quiet", "right"), `${title}向右滚动`);
  const refresh = () => {
    previous.disabled = track.scrollLeft < 2;
    next.disabled = track.scrollWidth - track.clientWidth - track.scrollLeft < 2;
  };
  track.addEventListener("scroll", refresh, { passive: true });
  const observer = new ResizeObserver(refresh);
  observer.observe(track);
  signal.addEventListener("abort", () => observer.disconnect(), { once: true });
  actions.append(previous, next);
  heading.append(actions);
  section.append(heading, track);
  return section;
}

function spotlight(series, history) {
  const panel = node("article", "spotlight");
  const copy = node("div");
  if (history) copy.append(node("p", "history-line", "接着看"));
  copy.append(node("h1", "clamp", series.title));
  const meta = history ? historyLabel(history) : [...new Set([series.badge, ...series.tags])].filter(Boolean).join(" · ");
  if (meta) copy.append(node("p", "meta", meta));
  if (!history && series.description) copy.append(node("p", "summary clamp", series.description));
  const actions = node("div", "actions");
  const play = button(history ? "继续观看" : "打开剧集", () => openDetail(series.id, true), "primary", "play");
  const details = button("查看详情", () => openDetail(series.id), "quiet");
  play.dataset.key = "spotlight-play";
  details.dataset.key = "spotlight-detail";
  actions.append(play, details);
  copy.append(actions);
  panel.append(copy, poster(series, true));
  return panel;
}

function nextNavigation() {
  state.navigation?.abort();
  state.navigation = new AbortController();
  return state.navigation.signal;
}

function tabs(choices, selected, onSelect) {
  const group = node("div", "tabs");
  for (const [id, label] of choices) {
    const item = button(label, () => onSelect(id), "tab");
    item.setAttribute("aria-pressed", String(selected === id));
    item.dataset.key = `tab:${id}`;
    group.append(item);
  }
  return group;
}

function searchForm() {
  const form = node("form", "search");
  form.setAttribute("role", "search");
  const field = node("div", "search-field");
  const label = node("label", "sr-only", "搜索剧名");
  label.htmlFor = "query";
  const input = node("input");
  input.type = "search";
  input.id = "query";
  input.placeholder = "搜索剧名";
  input.maxLength = 80;
  input.autocomplete = "off";
  input.required = true;
  input.value = state.query;
  const submit = node("button", "primary", "搜索");
  submit.type = "submit";
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const query = input.value.trim();
    if (!query) return;
    state.query = query;
    openSection("search", { focus: "query" });
  });
  field.append(label, icon("search"), input);
  form.append(field, submit);
  return form;
}

function browseReal() {
  state.category = "real";
  openSection("browse");
}

async function renderHome(outlet, signal) {
  const [catalog, library] = await Promise.allSettled([
    request("api/catalog?category=real&page=1", { signal }),
    request("api/library", { signal }),
  ]);
  if (signal.aborted) return;
  if (library.status === "fulfilled") state.library = library.value;
  const items = catalog.status === "fulfilled" ? catalog.value.items : [];
  const recent = state.library.history;
  const feature = recent[0]?.series || items[0];
  if (feature) outlet.replaceChildren(spotlight(feature, recent[0]));
  else outlet.replaceChildren();
  if (recent.length) {
    const cards = recent.slice(0, 10).map((entry) => card(entry.series, "recent", entry));
    outlet.append(shelf("接着看", "recent", cards, () => openLibrary("history"), signal));
  }
  if (items.length) {
    const hot = items.filter((series) => series.id !== feature?.id);
    const cards = (hot.length ? hot : items).slice(0, 12).map((series) => card(series, "hot"));
    outlet.append(shelf("热门发现", "hot", cards, browseReal, signal));
  } else {
    const message = catalog.status === "rejected" ? catalog.reason.message : "热门作品暂不可用";
    outlet.append(status(message, () => openSection("home")));
  }
}

function openLibrary(tab) {
  state.libraryTab = tab;
  openSection("library", { focus: `tab:${tab}` });
}

function applyPreferences(preferences) {
  state.preferences = preferences;
  document.documentElement.dataset.theme = preferences.theme;
}

function savePreference(key, value) {
  const write = state.savingPreferences.then(async () => {
    const next = await post("api/preferences", { ...state.preferences, [key]: value });
    applyPreferences(next);
  });
  state.savingPreferences = write.catch(() => {});
  return write;
}

function pagination(section, page, hasMore, focus) {
  const paging = node("div", "paging");
  const previous = button("上一页", () => openSection(section, { page: page - 1, focus: "page" }));
  const next = button("下一页", () => openSection(section, { page: page + 1, focus: "page" }));
  previous.disabled = page === 1;
  next.disabled = !hasMore;
  paging.append(previous, node("span", "", `第 ${page} 页`), next);
  if (focus === "page") requestAnimationFrame(() => (next.disabled ? previous : next).focus({ preventScroll: true }));
  return paging;
}

async function renderRanks(outlet, signal, page, focus) {
  const results = await request(`api/rank?board=${state.board}&page=${page}`, { signal });
  if (signal.aborted) return;
  const items = results.items.map(({ series, rank, metric }) => {
    const item = card(series, "rank");
    item.querySelector(".poster").append(node("span", "rank-number", String(rank)));
    if (metric) item.querySelector(".card-copy").append(node("span", "card-meta rank-metric", metric));
    return item;
  });
  outlet.replaceChildren();
  if (results.updated) outlet.append(node("p", "meta rank-updated", results.updated));
  outlet.append(items.length ? grid(items) : status("这页没有榜单作品"));
  if (page > 1 || results.has_more) outlet.append(pagination("rank", page, results.has_more, focus));
}

async function renderFeed(outlet, signal, restore) {
  if (!restore || !state.suggestion) {
    const results = await request(`api/catalog?category=${state.category}&page=1`, { signal });
    if (signal.aborted) return;
    const candidates = results.items.filter((row) => row.id !== state.suggestion?.id);
    const items = candidates.length ? candidates : results.items;
    state.suggestion = items[Math.floor(Math.random() * items.length)];
  }
  if (!state.suggestion) {
    outlet.replaceChildren(status("暂无可推荐的作品", () => openSection("feed")));
    return;
  }
  const panel = spotlight(state.suggestion);
  const next = button("换一部", () => openSection("feed", { focus: "feed-next" }));
  next.dataset.key = "feed-next";
  panel.querySelector(".actions").append(next);
  outlet.replaceChildren(panel);
  if (!restore && state.preferences.feed_autoplay) await openDetail(state.suggestion.id, true);
}

function restorePoint(point) {
  if (!point) return;
  for (const track of main.querySelectorAll("[data-shelf]")) track.scrollLeft = point.shelves.get(track.dataset.shelf) || 0;
  main.scrollTop = point.top;
  focusKey(point.key);
}

function focusKey(key) {
  if (!key) return;
  [...main.querySelectorAll("[data-key]")].find((item) => item.dataset.key === key)?.focus({ preventScroll: true });
}

async function openSection(section, { page = 1, restore = false, focus, settingsTab } = {}) {
  const signal = nextNavigation();
  const stopped = leavePlayer();
  state.detail = null;
  state.section = section;
  state.page = page;
  shell.dataset.view = "catalog";
  for (const item of nav) {
    if (item.dataset.section === section) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
  const frame = node("div", "page catalog-page");
  if (section !== "home") {
    const heading = node("header", "page-heading");
    heading.append(node("h1", "", sectionNames[section]));
    if (section === "library") {
      heading.append(button("红果账户", () => openSection("settings", { settingsTab: "account" }), "quiet"));
    }
    frame.append(heading);
  }
  if (section === "browse" || section === "feed") frame.append(tabs(categories, state.category, (category) => {
    state.category = category;
    openSection(section, { focus: `tab:${category}` });
  }));
  if (section === "rank") frame.append(tabs(boards, state.board, (board) => {
    state.board = board;
    openSection("rank", { focus: `tab:${board}` });
  }));
  if (section === "search") frame.append(searchForm());
  if (section === "library") frame.append(tabs([["history", "观看历史"], ["favorites", "收藏"], ["later", "稍后看"]], state.libraryTab, openLibrary));
  const outlet = node("div");
  frame.append(outlet);
  main.replaceChildren(frame);
  main.scrollTop = 0;
  if (section === "settings") {
    outlet.append(settingsPage(signal, {
      node, button, request, post, preferences: () => state.preferences, applyPreferences,
      savePreference, initialTab: settingsTab,
    }));
    return;
  }
  if (focus === "query") {
    document.querySelector("#query").focus();
    document.querySelector("#query").select();
  } else focusKey(focus);
  if (section === "search" && !state.query) return;
  outlet.append(status("正在加载…"));
  try {
    await stopped;
    if (signal.aborted) return;
    if (section === "home") {
      await renderHome(outlet, signal);
    } else if (section === "rank") {
      await renderRanks(outlet, signal, page, focus);
    } else if (section === "feed") {
      await renderFeed(outlet, signal, restore);
      if (focus === "feed-next") focusKey(focus);
    } else if (section === "library") {
      const library = await request("api/library", { signal });
      if (signal.aborted) return;
      state.library = library;
      const tab = state.libraryTab;
      const message = {
        history: "观看记录会出现在这里",
        favorites: "还没有收藏",
        later: "稍后看的剧集会出现在这里",
      };
      const cards = tab === "history"
        ? state.library.history.map((entry) => card(entry.series, "history", entry))
        : state.library[tab].map((entry) => card(entry, tab));
      outlet.replaceChildren(cards.length ? grid(cards) : status(message[tab]));
    } else {
      const params = new URLSearchParams({ page: String(page), category: state.category });
      if (section === "search") params.set("q", state.query);
      const results = await request(`api/catalog?${params}`, { signal });
      if (signal.aborted) return;
      outlet.replaceChildren();
      if (section === "search") outlet.append(node("h2", "result-heading", `“${state.query}”的搜索结果`));
      outlet.append(results.items.length ? grid(results.items.map((entry) => card(entry, section))) : status("没有找到匹配的剧集"));
      if (page > 1 || results.has_more) outlet.append(pagination(section, page, results.has_more, focus));
    }
    if (restore && !signal.aborted) restorePoint(state.returnPoint);
  } catch (error) {
    if (!signal.aborted) outlet.replaceChildren(status(error.message, () => openSection(section, { page, restore, focus })));
  }
}

function grid(cards) {
  const items = node("div", "grid");
  items.append(...cards);
  return items;
}

function backToSection() {
  openSection(state.section, { page: state.page, restore: true });
}

async function openDetail(id, play = false) {
  if (shell.dataset.view !== "detail") {
    state.returnPoint = {
      top: main.scrollTop,
      key: document.activeElement?.dataset.key,
      shelves: new Map([...main.querySelectorAll("[data-shelf]")].map((item) => [item.dataset.shelf, item.scrollLeft])),
    };
  }
  const signal = nextNavigation();
  const stopped = leavePlayer();
  state.detail = null;
  shell.dataset.view = "detail";
  const frame = node("div", "page detail-page");
  const heading = node("header", "page-heading");
  const back = button("返回", backToSection, "back", "left");
  heading.append(back, node("h1", "", "剧集详情"));
  const outlet = node("div");
  outlet.append(status("正在读取剧集…"));
  frame.append(heading, outlet);
  main.replaceChildren(frame);
  main.scrollTop = 0;
  back.focus({ preventScroll: true });
  try {
    await stopped;
    const [detail, library] = await Promise.all([
      request(`api/detail/${id}`, { signal }), request("api/library", { signal }),
    ]);
    if (signal.aborted) return;
    state.detail = detail;
    state.library = library;
    const selected = detail.episodes.find((episode) => episode.id === detail.resume?.episode);
    const position = detail.resume?.position || 0;
    outlet.replaceChildren(renderDetail(detail, selected, position));
    if (play && selected?.full) openPlayer(selected, position);
  } catch (error) {
    if (!signal.aborted) outlet.replaceChildren(status(error.message, () => openDetail(id, play)));
  }
}

function episodeUrl(series, episode) {
  return `https://hongguoduanju.com/player/${series.id}/${episode.id}`;
}

function officialLink(series, episode) {
  const link = node("a", "action-link official", "在官网播放");
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  link.href = episodeUrl(series, episode);
  return link;
}

function renderDetail(detail, selected, position) {
  const { series, episodes } = detail;
  const history = state.library.history.find((item) => item.series.id === series.id);
  const layout = node("div", "detail");
  const hero = node("section", "detail-hero");
  const copy = node("div", "detail-copy");
  copy.append(node("h2", "", series.title));
  copy.append(node("p", "meta", [`全 ${episodes.length} 集`, ...series.tags].join(" · ")));
  const actions = node("div", "actions");
  let playLabel = "暂无可播分集";
  if (selected) {
    const verb = selected.full ? "播放" : "试看";
    playLabel = position > 0 ? "继续" + verb : `${verb}第 ${selected.number} 集`;
  }
  const play = button(playLabel, () => openPlayer(selected, position), "primary", "play");
  play.disabled = !selected;
  const note = node("p", "history-line", history ? historyLabel(history) : "");
  note.hidden = !history;
  note.setAttribute("role", "status");
  function savedAction(field, endpoint, label, activeLabel) {
    let saved = state.library[field].some((item) => item.id === series.id);
    const item = button(saved ? activeLabel : label, async () => {
      item.disabled = true;
      try {
        await post(endpoint, { id: series.id, saved: !saved });
        saved = !saved;
        item.textContent = saved ? activeLabel : label;
        item.setAttribute("aria-pressed", String(saved));
      } catch (error) {
        note.hidden = false;
        note.textContent = error.message;
      } finally {
        item.disabled = false;
      }
    });
    item.setAttribute("aria-pressed", String(saved));
    return item;
  }
  const favorite = savedAction("favorites", "api/favorite", "收藏", "已收藏");
  const later = savedAction("later", "api/later", "稍后看", "已加入稍后看");
  const official = officialLink(series, selected || episodes[0]);
  actions.append(play, official, favorite, later);
  if (series.description) {
    const summary = node("p", "summary clamp", series.description);
    summary.id = "synopsis";
    const toggle = button("完整简介", () => {
      const collapsed = summary.classList.toggle("clamp");
      toggle.textContent = collapsed ? "完整简介" : "收起简介";
      toggle.setAttribute("aria-expanded", String(!collapsed));
    }, "quiet");
    toggle.setAttribute("aria-expanded", "false");
    toggle.setAttribute("aria-controls", summary.id);
    copy.append(summary);
    actions.append(toggle);
  }
  copy.append(actions, note);
  hero.append(poster(series, true), copy);
  layout.append(hero, episodePicker(episodes, selected || episodes[0], history, true));
  if (detail.reviews.length) {
    const reviews = node("section", "reviews");
    const heading = node("div", "section-heading");
    heading.append(node("h2", "", "剧评"));
    reviews.append(heading);
    for (const entry of detail.reviews) {
      const review = node("article", "review");
      review.append(node("strong", "", entry.author), node("p", "summary", entry.content));
      reviews.append(review);
    }
    layout.append(reviews);
  }
  if (detail.related.length) {
    const cards = detail.related.map((entry) => card(entry, "related"));
    layout.append(shelf("相关剧集", "related", cards, null, state.navigation.signal));
  }
  return layout;
}

function episodePicker(episodes, current, history, showJump) {
  const picker = node("section", "episodes");
  const heading = node("div", "section-heading");
  const title = node("h2", "", "选集");
  title.append(node("span", "count", `全 ${episodes.length} 集`));
  heading.append(title);
  const tools = node("div", "episode-tools");
  const ranges = node("div", "episode-groups");
  const grid = node("div", "episode-grid");
  const rangeButtons = [];
  function showGroup(group, jumpTo) {
    rangeButtons.forEach((item, index) => item.setAttribute("aria-pressed", String(index === group)));
    const entries = episodes.slice(group * groupSize, (group + 1) * groupSize);
    const cells = entries.map((episode) => {
      const playing = episode.id === current.id;
      const label = playing ? "当前" : history?.episode === episode.id
        ? (history.completed ? "已看" : "上次") : episode.full ? "" : episode.available ? "试看" : "官网";
      const cell = button(String(episode.number), () => openPlayer(episode), episode.available ? "" : "external-episode");
      cell.dataset.episode = episode.id;
      const availability = episode.full ? "" : episode.available ? "，公开试看" : "，在官网播放";
      cell.setAttribute("aria-label", `第 ${episode.number} 集${availability}`);
      if (playing) cell.setAttribute("aria-current", "true");
      if (label) cell.append(node("span", "episode-flag", label));
      return cell;
    });
    grid.replaceChildren(...cells);
    const revealRange = () => {
      if (!ranges.isConnected || !ranges.clientWidth) return;
      const selected = rangeButtons[group].getBoundingClientRect();
      const track = ranges.getBoundingClientRect();
      if (selected.left < track.left) ranges.scrollLeft += selected.left - track.left - 4;
      else if (selected.right > track.right) ranges.scrollLeft += selected.right - track.right + 4;
    };
    requestAnimationFrame(revealRange);
    if (jumpTo !== undefined) cells[jumpTo % groupSize]?.focus();
  }
  for (let first = 0; first < episodes.length; first += groupSize) {
    const group = first / groupSize;
    const start = episodes[first].number;
    const end = episodes[Math.min(first + groupSize, episodes.length) - 1].number;
    const range = button(`${start}–${end}`, () => showGroup(group), "tab");
    range.setAttribute("aria-label", `第 ${start} 至 ${end} 集分组`);
    rangeButtons.push(range);
  }
  ranges.append(...rangeButtons);
  tools.append(ranges);
  if (showJump && episodes.length > groupSize) {
    const jump = node("form", "episode-jump");
    const number = namedControl(node("input"), "跳转集数");
    number.type = "number";
    number.min = 1;
    number.max = episodes.length;
    number.placeholder = "集数";
    number.required = true;
    const go = node("button", "", "跳转");
    go.type = "submit";
    jump.addEventListener("submit", (event) => {
      event.preventDefault();
      const index = episodes.findIndex((episode) => episode.number === number.valueAsNumber);
      if (index >= 0) showGroup(Math.floor(index / groupSize), index);
    });
    jump.append(number, go);
    tools.append(jump);
  }
  picker.append(heading, tools, grid);
  showGroup(Math.floor(Math.max(0, episodes.indexOf(current)) / groupSize));
  return picker;
}

function savePosition(session, completed = false) {
  const video = session.video;
  if (!Number.isFinite(video.duration) || video.currentTime <= 0) return;
  const duration = Math.round(video.duration * 1000);
  const position = Math.min(duration, Math.round(video.currentTime * 1000));
  const update = { position, duration, completed, sequence: ++session.sequence };
  session.savedAt = performance.now();
  session.queue = session.queue.then(() => post(`api/play/${session.token}/progress`, update, {
    keepalive: true, signal: AbortSignal.timeout(3500),
  })).catch(() => {
    if (state.playing === session) session.view.episodeLabel.textContent = "续播进度暂未保存";
  });
}

function release(token) {
  return request(`api/play/${token}`, { method: "DELETE", keepalive: true, signal: AbortSignal.timeout(3500) }).catch(() => {});
}

function stopPlayback() {
  state.prepare?.abort();
  state.prepare = null;
  state.player?.qualityChange?.abort();
  const session = state.playing;
  state.playing = null;
  if (session) {
    savePosition(session, session.video.ended);
    session.video.pause();
    session.video.removeAttribute("src");
    session.video.load();
    state.stopping = session.queue.then(() => release(session.token));
  }
  return state.stopping;
}

function leavePlayer() {
  const stopped = stopPlayback();
  if (state.player) {
    clearTimeout(state.player.hideTimer);
    clearTimeout(state.player.sleepTimer);
    clearInterval(state.player.clockTimer);
    state.player = null;
    if (document.fullscreenElement === watch) document.exitFullscreen().catch(() => {});
    watch.hidden = true;
    watch.replaceChildren();
    shell.hidden = false;
  }
  return stopped;
}

function toggleVideo() {
  const video = state.playing?.video;
  if (!video) return;
  if (video.paused) {
    state.player.sleepStopped = false;
    if (video.ended) video.currentTime = 0;
    video.play().catch(() => {});
  } else video.pause();
}

function seekTo(seconds) {
  const video = state.playing?.video;
  if (video && Number.isFinite(video.duration)) video.currentTime = Math.max(0, Math.min(video.duration, seconds));
}

function shiftEpisode(direction) {
  const current = state.player;
  const episode = current?.detail.episodes[current.index + direction];
  if (episode && state.playing?.playback.scope === "full" && !episode.full) {
    current.episodeLabel.textContent = "所选分集暂未接入正式播放源";
    showControls();
    return;
  }
  if (episode) openPlayer(episode);
}

function toggleEpisodes(view, open, restoreFocus = false) {
  if (open) toggleMore(view, false);
  view.menu.setAttribute("aria-expanded", String(open));
  view.panel.hidden = !open;
  if (open) {
    const history = state.library.history.find((entry) => entry.series.id === view.detail.series.id);
    const picker = episodePicker(view.detail.episodes, view.detail.episodes[view.index], history, false);
    view.panel.replaceChildren(picker);
    picker.querySelector('[aria-current="true"]')?.focus({ preventScroll: true });
  } else if (restoreFocus) view.menu.focus({ preventScroll: true });
  showControls();
}

function showControls() {
  const view = state.player;
  if (!view) return;
  delete watch.dataset.idle;
  clearTimeout(view.hideTimer);
  const video = state.playing?.video;
  if (!video || video.paused || video.ended || !view.panel.hidden || !view.more.hidden) return;
  view.hideTimer = setTimeout(() => {
    if (state.player === view && !watch.querySelector(".watch-header:hover, .watch-footer:hover, .watch-header:focus-within, .watch-footer:focus-within")) {
      watch.dataset.idle = "true";
    }
  }, 2800);
}

function toggleMore(view, open, restoreFocus = false) {
  if (open) toggleEpisodes(view, false);
  view.more.hidden = !open;
  view.moreButton.setAttribute("aria-expanded", String(open));
  if (open) view.more.querySelector("select")?.focus({ preventScroll: true });
  else if (restoreFocus) view.moreButton.focus({ preventScroll: true });
  showControls();
}

function playerPreference(view, control, key, apply) {
  control.addEventListener("change", async () => {
    const original = state.preferences[key];
    const value = control.type === "checkbox" ? control.checked
      : typeof original === "number" ? Number(control.value) : control.value;
    control.disabled = true;
    try {
      await savePreference(key, value);
      if (state.player === view) apply(value);
    } catch (error) {
      if (control.type === "checkbox") control.checked = state.preferences[key];
      else control.value = String(state.preferences[key]);
      if (state.player === view) view.episodeLabel.textContent = error.message;
    } finally {
      control.disabled = false;
    }
  });
}

function playerOptions(view) {
  const panel = node("div", "watch-panel watch-options");
  panel.id = "watch-options";
  panel.hidden = true;
  function row(label, key, options, apply) {
    const line = node("label", "settings-row");
    const control = node(options ? "select" : "input");
    if (options) {
      for (const [id, title] of options) {
        const option = node("option", "", title);
        option.value = id;
        control.append(option);
      }
      control.value = state.preferences[key];
    } else {
      control.type = "checkbox";
      control.checked = state.preferences[key];
    }
    line.append(node("span", "", label), control);
    panel.append(line);
    playerPreference(view, control, key, apply);
  }
  row("画面模式", "frame", frameOptions, value => { view.stage.dataset.frame = value; });
  row("画面旋转", "rotation", rotationOptions, value => { view.stage.dataset.rotation = value; });
  row("常驻播放进度", "show_progress", null, value => { view.ambient.hidden = !value; });
  panel.append(button("从头播放", () => {
    seekTo(0);
    toggleMore(view, false, true);
  }));
  return panel;
}

function createPlayer(detail) {
  const preferences = state.preferences;
  const view = { detail, index: 0, rate: preferences.speed / 100, volume: 1, muted: false, scrubbing: false };
  const header = node("header", "watch-header");
  const back = button("返回详情", () => openDetail(detail.series.id), "back", "left");
  const heading = node("div", "watch-heading");
  view.episodeLabel = node("p", "meta");
  view.episodeLabel.setAttribute("role", "status");
  heading.append(node("h1", "", detail.series.title), view.episodeLabel);
  view.official = officialLink(detail.series, detail.episodes[0]);
  header.append(back, view.official);
  view.stage = namedControl(node("div", "watch-stage"), "视频画面");
  view.stage.setAttribute("role", "group");
  view.stage.tabIndex = 0;
  view.stage.dataset.frame = preferences.frame;
  view.stage.dataset.rotation = preferences.rotation;
  const footer = node("footer", "watch-footer");
  const timeline = node("div", "timeline");
  view.elapsed = node("span", "clock");
  view.duration = node("span", "clock");
  view.slider = namedControl(node("input"), "播放进度");
  view.slider.type = "range";
  view.slider.min = 0;
  view.slider.step = .1;
  view.slider.addEventListener("input", () => {
    view.scrubbing = true;
    view.elapsed.textContent = formatTime(view.slider.valueAsNumber * 1000);
  });
  view.slider.addEventListener("change", () => {
    view.scrubbing = false;
    seekTo(view.slider.valueAsNumber);
  });
  for (const event of ["blur", "pointercancel"]) view.slider.addEventListener(event, () => {
    view.scrubbing = false;
    syncControls(view, state.playing?.video);
  });
  timeline.append(view.elapsed, view.slider, view.duration);
  const transport = node("div", "transport");
  const basic = node("div", "transport-group");
  view.previous = button("上一集", () => shiftEpisode(-1));
  view.next = button("下一集", () => shiftEpisode(1));
  view.play = button("播放", toggleVideo, "primary", "play");
  basic.append(view.previous, view.play, view.next);
  const settings = node("div", "transport-group");
  view.menu = button("选集", () => toggleEpisodes(view, view.panel.hidden, true));
  view.menu.setAttribute("aria-expanded", "false");
  view.menu.setAttribute("aria-controls", "watch-episodes");
  view.moreButton = button("更多设置", () => toggleMore(view, view.more.hidden, true));
  view.moreButton.setAttribute("aria-expanded", "false");
  view.moreButton.setAttribute("aria-controls", "watch-options");
  view.quality = namedControl(node("select"), "清晰度");
  view.quality.disabled = true;
  view.quality.addEventListener("change", () => switchQuality(view));
  const speed = namedControl(node("select"), "播放倍速");
  for (const step of speedSteps) {
    const option = node("option", "", `${step / 100}×`);
    option.value = step;
    speed.append(option);
  }
  speed.value = preferences.speed;
  playerPreference(view, speed, "speed", value => {
    view.rate = value / 100;
    const video = state.playing?.video;
    if (video) {
      video.defaultPlaybackRate = view.rate;
      video.playbackRate = view.rate;
    }
  });
  const mute = button("静音", () => {
    view.muted = !view.muted;
    mute.setAttribute("aria-pressed", String(view.muted));
    if (state.playing) state.playing.video.muted = view.muted;
  });
  mute.setAttribute("aria-pressed", "false");
  const volume = namedControl(node("input", "transport-volume"), "音量");
  volume.type = "range";
  volume.min = 0;
  volume.max = 1;
  volume.step = .05;
  volume.value = view.volume;
  volume.addEventListener("input", () => {
    view.volume = volume.valueAsNumber;
    if (state.playing) state.playing.video.volume = view.volume;
  });
  const autoLabel = node("label", "autoplay");
  const auto = node("input");
  auto.type = "checkbox";
  auto.checked = preferences.auto_next;
  playerPreference(view, auto, "auto_next", showControls);
  autoLabel.append(auto, document.createTextNode("自动连播"));
  view.sleep = namedControl(node("select"), "定时停止");
  for (const [id, label] of [["off", "定时"], ["episode", "本集播完"], ["episodes-3", "再播完 3 集"],
    ["episodes-5", "再播完 5 集"], ["15", "15 分钟"], ["30", "30 分钟"], ["60", "60 分钟"], ["90", "90 分钟"]]) {
    const option = node("option", "", label);
    option.value = id;
    view.sleep.append(option);
  }
  view.sleep.addEventListener("change", () => {
    clearTimeout(view.sleepTimer);
    const minutes = Number(view.sleep.value);
    view.episodesLeft = { episode: 1, "episodes-3": 3, "episodes-5": 5 }[view.sleep.value] || 0;
    if (minutes > 0) view.sleepTimer = setTimeout(() => stopForSleep(view), minutes * 60_000);
  });
  settings.append(view.menu, view.quality, speed, view.sleep, view.moreButton, mute, volume, autoLabel);
  if (document.fullscreenEnabled && watch.requestFullscreen) {
    view.fullscreen = button("全屏", async () => {
      try {
        if (document.fullscreenElement === watch) await document.exitFullscreen();
        else await watch.requestFullscreen();
      } catch { view.fullscreen.hidden = true; }
    });
    settings.append(view.fullscreen);
  }
  transport.append(basic, settings);
  view.panel = node("div", "watch-panel");
  view.panel.id = "watch-episodes";
  view.panel.hidden = true;
  view.more = playerOptions(view);
  footer.append(view.panel, view.more, heading, timeline, transport);
  watch.replaceChildren(header, view.stage, footer);
  view.ambient = node("progress", "watch-progress card-progress");
  view.ambient.setAttribute("aria-hidden", "true");
  view.ambient.hidden = !preferences.show_progress;
  watch.append(view.ambient);
  if (preferences.clock !== "off") {
    const clock = node("time", "watch-clock");
    clock.dataset.mode = preferences.clock;
    const showTime = () => { clock.textContent = new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }); };
    showTime();
    view.clockTimer = setInterval(showTime, 1000);
    watch.append(clock);
  }
  return view;
}

function stopForSleep(view) {
  if (view !== state.player) return;
  clearTimeout(view.sleepTimer);
  view.sleepStopped = true;
  view.episodesLeft = 0;
  view.sleep.value = "off";
  state.playing?.video.pause();
  view.episodeLabel.textContent = "定时停止已生效";
  showControls();
}

function syncControls(view, video) {
  const duration = Number.isFinite(video?.duration) ? video.duration : 0;
  view.slider.disabled = duration === 0;
  if (!view.scrubbing) {
    view.slider.max = duration || 1;
    view.slider.value = video?.currentTime || 0;
    view.elapsed.textContent = formatTime((video?.currentTime || 0) * 1000);
  }
  view.duration.textContent = formatTime(duration * 1000);
  if (view.ambient) {
    view.ambient.max = duration || 1;
    view.ambient.value = video?.currentTime || 0;
  }
  view.play.disabled = !video;
  const playing = video && !video.paused && !video.ended;
  const label = playing ? "暂停" : "播放";
  if (view.play.textContent !== label) view.play.replaceChildren(icon(playing ? "pause" : "play"), document.createTextNode(label));
}

function qualityOptions(view, playback) {
  view.quality.replaceChildren();
  view.quality.disabled = !playback || playback.qualities.length <= 1;
  if (!playback) return;
  for (const quality of playback.qualities) {
    const option = node("option", "", quality + "P");
    option.value = quality;
    view.quality.append(option);
  }
  view.quality.value = playback.quality;
}

function prepareVideo(video, source, signal) {
  return new Promise((resolve, reject) => {
    function finish(error) {
      clearTimeout(timer);
      video.removeEventListener("canplay", ready);
      video.removeEventListener("error", failed);
      signal.removeEventListener("abort", cancelled);
      if (error) reject(error);
      else resolve();
    }
    const ready = () => finish();
    const failed = () => finish(new Error("所选清晰度暂时无法播放"));
    const cancelled = () => finish(new DOMException("Aborted", "AbortError"));
    const timer = setTimeout(failed, 15000);
    video.addEventListener("canplay", ready);
    video.addEventListener("error", failed);
    signal.addEventListener("abort", cancelled);
    video.src = source;
  });
}

async function switchQuality(view) {
  const current = state.playing;
  if (!current || current.view !== view || view.qualityChange) return;
  const quality = Number(view.quality.value);
  if (quality === current.playback.quality) return;
  const controller = new AbortController();
  view.qualityChange = controller;
  view.quality.disabled = true;
  const episode = view.detail.episodes[view.index];
  let playback;
  let candidate;
  try {
    playback = await post("api/play", { series: view.detail.series.id, episode: episode.id, quality, scope: current.playback.scope });
    if (controller.signal.aborted || state.playing !== current) return;
    candidate = node("video");
    candidate.preload = "auto";
    candidate.hidden = true;
    view.stage.append(candidate);
    await prepareVideo(candidate, "media/" + playback.token, controller.signal);
    if (state.playing !== current) return;
    const prepared = { playback, candidate, autoplay: !current.video.paused };
    view.qualityChange = null;
    playback = null;
    candidate = null;
    await openPlayer(episode, Math.round(current.video.currentTime * 1000), prepared);
  } catch (error) {
    if (!controller.signal.aborted && state.playing === current) view.episodeLabel.textContent = error.message;
  } finally {
    if (candidate) {
      candidate.removeAttribute("src");
      candidate.load();
      candidate.remove();
    }
    if (playback) release(playback.token);
    if (view.qualityChange === controller) view.qualityChange = null;
    if (!controller.signal.aborted && state.playing === current) qualityOptions(view, current.playback);
  }
}

async function openPlayer(episode, position = 0, prepared) {
  const detail = state.detail;
  if (!detail || !episode) return;
  const focusStage = !state.player || state.player.panel.contains(document.activeElement) || document.activeElement === state.player.play;
  const view = state.player || createPlayer(detail);
  state.player = view;
  const stopped = stopPlayback();
  shell.hidden = true;
  watch.hidden = false;
  view.index = detail.episodes.indexOf(episode);
  view.scrubbing = false;
  if (!prepared) view.sleepStopped = false;
  const label = `第 ${episode.number} 集`;
  view.episodeLabel.textContent = label;
  view.official.href = episodeUrl(detail.series, episode);
  view.previous.disabled = view.index <= 0;
  view.next.disabled = view.index >= detail.episodes.length - 1;
  qualityOptions(view);
  toggleEpisodes(view, false);
  toggleMore(view, false);
  syncControls(view);
  if (focusStage || document.activeElement?.disabled) view.stage.focus({ preventScroll: true });
  if (!episode.available) {
    view.stage.replaceChildren(status("本集可在官网播放", null, "watch-status"));
    return;
  }
  const controller = new AbortController();
  state.prepare = controller;
  const loading = node("p", "watch-message", "正在打开试看…");
  loading.setAttribute("role", "status");
  view.stage.replaceChildren(loading);
  try {
    await stopped;
    if (controller.signal.aborted) {
      if (prepared) release(prepared.playback.token);
      return;
    }
    // Read the reply even after navigating away so the abandoned media session can be released.
    const playback = prepared?.playback || await post("api/play", {
      series: detail.series.id, episode: episode.id, scope: episode.full ? "full" : "preview",
    });
    if (controller.signal.aborted) {
      release(playback.token);
      return;
    }
    const video = prepared?.candidate || node("video");
    video.hidden = false;
    video.playsInline = true;
    video.preload = "auto";
    video.volume = view.volume;
    video.muted = view.muted;
    video.defaultPlaybackRate = view.rate;
    video.playbackRate = view.rate;
    video.setAttribute("aria-label", `${label}视频`);
    const session = { video, playback, token: playback.token, view, sequence: 0, savedAt: 0, queue: Promise.resolve() };
    state.playing = session;
    video.addEventListener("click", toggleVideo);
    const loaded = () => {
      if (state.playing !== session) return;
      if (position > 0 && Number.isFinite(video.duration)) {
        video.currentTime = Math.min(position / 1000, Math.max(0, video.duration - 1));
      }
      syncControls(view, video);
    };
    if (video.readyState >= 1) loaded();
    else video.addEventListener("loadedmetadata", loaded, { once: true });
    if (video.readyState >= 2) loading.remove();
    else video.addEventListener("loadeddata", () => loading.remove(), { once: true });
    for (const event of ["play", "pause", "seeked", "timeupdate", "durationchange"]) {
      video.addEventListener(event, () => {
        if (state.playing !== session) return;
        syncControls(view, video);
        if (event === "play" || event === "pause") showControls();
        if (!video.ended && (event === "pause" || event === "seeked" ||
            event === "timeupdate" && performance.now() - session.savedAt > 4000)) savePosition(session);
      });
    }
    video.addEventListener("ended", () => {
      if (state.playing !== session) return;
      syncControls(view, video);
      showControls();
      savePosition(session, true);
      if (view.episodesLeft > 0 && --view.episodesLeft === 0) {
        stopForSleep(view);
        return;
      }
      const next = detail.episodes[view.index + 1];
      const sameScope = next?.full === (playback.scope === "full");
      if (!view.sleepStopped && state.preferences.auto_next && next?.available && sameScope && view.panel.hidden && view.more.hidden) openPlayer(next);
    });
    video.addEventListener("error", () => {
      if (state.playing !== session) return;
      const retryPosition = Math.round(video.currentTime * 1000);
      stopPlayback();
      syncControls(view);
      showControls();
      view.stage.replaceChildren(status("视频读取失败", () => openPlayer(episode, retryPosition), "watch-status"));
    });
    view.stage.prepend(video);
    if (!view.sleepStopped) {
      const source = playback.qualities.length === 1 ? "仅提供 " : "";
      const mode = playback.scope === "full" ? "正片" : "公开试看";
      view.episodeLabel.textContent = `${label}${mode} · ${source}${playback.quality}P`;
    }
    qualityOptions(view, playback);
    if (!prepared) video.src = `media/${playback.token}`;
    syncControls(view, video);
    if (!view.sleepStopped && (prepared?.autoplay ?? true)) video.play().catch(() => {});
  } catch (error) {
    if (controller.signal.aborted) return;
    view.stage.replaceChildren(status(error.message, error.status === 404 ? null : () => openPlayer(episode, position), "watch-status"));
  }
}

function moveFocus(target, key) {
  const horizontal = key === "ArrowLeft" || key === "ArrowRight";
  const direction = key === "ArrowRight" || key === "ArrowDown" ? 1 : -1;
  const bounds = target.getBoundingClientRect();
  let closest;
  let bestScore = Infinity;
  const scope = state.player ? watch : shell;
  for (const control of scope.querySelectorAll("button:enabled, a[href], input:enabled, select:enabled")) {
    if (control === target || !control.getClientRects().length) continue;
    const candidate = control.getBoundingClientRect();
    const dx = (candidate.left + candidate.right - bounds.left - bounds.right) / 2;
    const dy = (candidate.top + candidate.bottom - bounds.top - bounds.bottom) / 2;
    const along = (horizontal ? dx : dy) * direction;
    const across = Math.abs(horizontal ? dy : dx);
    if (along < 2 || across > along * 3) continue;
    const score = along + across * 3;
    if (score >= bestScore) continue;
    closest = control;
    bestScore = score;
  }
  if (closest) closest.focus();
}

for (const item of nav) item.addEventListener("click", () => openSection(item.dataset.section, { focus: item.dataset.section === "search" ? "query" : null }));
watch.addEventListener("pointermove", showControls);
watch.addEventListener("pointerleave", showControls);
document.addEventListener("keydown", (event) => {
  if (event.defaultPrevented) return;
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    openSection("search", { focus: "query" });
    return;
  }
  if (event.altKey || event.ctrlKey || event.metaKey) return;
  if (state.player && event.key !== "Escape") showControls();
  if (event.key === "Escape") {
    if (document.fullscreenElement === watch) {
      event.preventDefault();
      document.exitFullscreen().catch(() => {});
    } else if (state.player) {
      if (!state.player.more.hidden) toggleMore(state.player, false, true);
      else if (!state.player.panel.hidden) toggleEpisodes(state.player, false, true);
      else openDetail(state.player.detail.series.id);
    } else if (shell.dataset.view === "detail") backToSection();
    return;
  }
  const stage = state.player?.stage;
  if (stage && (event.target === stage || event.target instanceof HTMLVideoElement)) {
    const video = state.playing?.video;
    if (event.key === " ") toggleVideo();
    else if (event.key === "ArrowLeft" || event.key === "ArrowRight") seekTo((video?.currentTime || 0) + (event.key === "ArrowLeft" ? -10 : 10));
    else if (event.key === "ArrowUp" || event.key === "ArrowDown") shiftEpisode(event.key === "ArrowUp" ? -1 : 1);
    else return;
    event.preventDefault();
  } else if (event.key.startsWith("Arrow") && event.target instanceof HTMLElement && event.target.matches("button, a[href]")) {
    event.preventDefault();
    moveFocus(event.target, event.key);
  }
});
document.addEventListener("fullscreenchange", () => {
  const control = state.player?.fullscreen;
  if (control) control.textContent = document.fullscreenElement ? "退出全屏" : "全屏";
});
window.addEventListener("pagehide", () => {
  if (state.playing) savePosition(state.playing, state.playing.video.ended);
});
request("api/preferences").then(applyPreferences, () => {}).finally(() => {
  const startup = state.preferences.startup;
  if (startup === "favorites" || startup === "history") openLibrary(startup);
  else openSection(startup || "home", { focus: startup === "search" ? "query" : null });
});
