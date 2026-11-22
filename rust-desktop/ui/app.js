// SPDX-License-Identifier: GPL-3.0-only
import { attachDanmaku, danmakuOptions } from "./danmaku.js";
import { discoveryPage, discoveryState } from "./discovery.js";
import { frameOptions, settingsPage, speedSteps } from "./settings.js";

const shell = document.querySelector("#shell");
const main = document.querySelector("#main");
const watch = document.querySelector("#watch");
const nav = [...document.querySelectorAll(".app-bar [data-section]")];
const categories = [["real", "真人剧"], ["comic", "漫剧"], ["ai", "AI 剧"]];
const sectionNames = { home: "首页", feed: "随心看", browse: "找剧", search: "搜索", rank: "排行榜", library: "我的", settings: "设置" };
const boards = [["all", "热播榜"], ["real", "真人剧"], ["comic", "漫剧"], ["ai", "AI 剧"], ["new", "新剧榜"], ["searched", "热搜榜"], ["favorites", "收藏榜"]];
const groupSize = 20;
const state = {
  section: "home", discovery: discoveryState(), page: 1, query: "", libraryTab: "history", reservationTab: "pending",
  library: { favorites: [], history: [], later: [] }, detail: null, returnPoint: null, detailTrail: [],
  navigation: null, player: null, playing: null, prepare: null,
  stopping: Promise.resolve(), savingPreferences: Promise.resolve(), savingSearch: Promise.resolve(),
  board: "all", feedCategory: "real", feed: null,
  preferences: { theme: "dark", quality: 1080, speed: 100, frame: "fit",
    show_progress: false, auto_next: true, season_autoplay: false, cloud_progress: false, danmaku: true,
    danmaku_area: 50, danmaku_font: 24, danmaku_opacity: 80, danmaku_travel: 9, startup: "home", feed_autoplay: true, clock: "controls" },
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
  const episode = history.number > 0 ? `第 ${history.number} 集` : "观看进度";
  const watched = history.from_tv ? "已看完" : "试看完毕";
  return history.completed ? episode + watched : `${episode} · ${formatTime(history.position)}`;
}

function card(series, key, history) {
  const item = button("", () => openDetail(series.id, Boolean(history), undefined, history?.resumeSource), "card");
  item.dataset.key = `${key}:${series.id}`;
  const copy = node("span", "card-copy");
  copy.append(node("span", "card-title clamp", series.title));
  const meta = history ? [historyLabel(history), history.account ? "账户" : ""].filter(Boolean).join(" · ") : series.badge;
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
  const play = button(history ? "继续观看" : "开始观看", () => openDetail(series.id, true), "primary", "play");
  const details = button("查看详情", () => openDetail(series.id), "quiet");
  play.dataset.key = "spotlight-play";
  details.dataset.key = "spotlight-detail";
  actions.append(play, details);
  copy.append(actions);
  const art = poster(series, true);
  const backdrop = node("div", "spotlight-art");
  backdrop.append(art);
  panel.append(copy, backdrop);
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

function searchForm(signal) {
  const section = node("div", "search-tools");
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
  const matches = node("section", "search-matches");
  const heading = node("h2", "meta");
  const words = node("div", "search-words");
  const notice = node("p", "meta");
  notice.setAttribute("role", "status");
  matches.append(heading, words, notice);
  matches.hidden = true;
  let pending;
  let timer;

  function recordSearch(query) {
    state.savingSearch = state.savingSearch.then(() => post("api/search/recent", { q: query })).catch(() => {});
    state.query = query;
    openSection("search", { focus: "query" });
  }

  async function showWords(query, signal) {
    const recent = query === "";
    if (recent) await state.savingSearch;
    if (signal.aborted) return;
    const path = recent ? "api/search/recent" : `api/search/suggestions?${new URLSearchParams({ q: query })}`;
    try {
      const entries = await request(path, { signal });
      if (signal.aborted) return;
      heading.textContent = recent ? "最近搜索" : "搜索联想";
      notice.textContent = "";
      words.replaceChildren(...entries.map(word => button(word, () => recordSearch(word), "tab")));
      if (recent && entries.length) {
        const clear = button("清空最近搜索", async () => {
          clear.disabled = true;
          try {
            await request("api/search/recent", { method: "DELETE" });
            if (signal.aborted) return;
            input.focus();
            refresh();
          } catch (error) {
            if (!signal.aborted) notice.textContent = error.message;
          } finally {
            clear.disabled = false;
          }
        }, "quiet");
        words.append(clear);
      }
      matches.hidden = entries.length === 0;
    } catch {
      if (!signal.aborted) matches.hidden = true;
    }
  }

  function refresh() {
    clearTimeout(timer);
    pending?.abort();
    matches.hidden = true;
    if (signal.aborted) return;
    pending = new AbortController();
    const query = input.value.trim();
    const responseSignal = pending.signal;
    if (query) timer = setTimeout(() => showWords(query, responseSignal), 220);
    else showWords("", responseSignal);
  }
  signal.addEventListener("abort", () => {
    clearTimeout(timer);
    pending?.abort();
  }, { once: true });
  input.addEventListener("input", event => { if (!event.isComposing) refresh(); });
  input.addEventListener("compositionstart", () => { clearTimeout(timer); pending?.abort(); matches.hidden = true; });
  input.addEventListener("compositionend", refresh);
  input.addEventListener("keydown", event => {
    if (event.key !== "ArrowDown" || event.isComposing || matches.hidden) return;
    event.preventDefault();
    words.querySelector("button")?.focus();
  });
  words.addEventListener("keydown", event => {
    if (event.key !== "ArrowUp") return;
    event.preventDefault();
    input.focus();
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const query = input.value.trim();
    if (query) recordSearch(query);
  });
  field.append(label, icon("search"), input);
  form.append(field, submit);
  section.append(form, matches);
  if (!input.value) refresh();
  return section;
}

function browseReal() {
  state.discovery = discoveryState({ genre: "short_play" });
  openSection("browse");
}

function combinedHistory(local, cloud) {
  const known = new Map(local.map(row => [row.series.id, row]));
  for (const row of cloud) {
    const current = known.get(row.series.id);
    if (current && (!row.updated_at || current.from_tv && current.updated_at >= row.updated_at)) continue;
    const series = current && !row.series.cover ? { ...row.series, cover: current.series.cover } : row.series;
    known.set(row.series.id, { ...row, series, from_tv: true, account: true });
  }
  return [...known.values()].sort((a, b) => b.updated_at - a.updated_at);
}

async function renderHome(outlet, signal) {
  const accountHistory = request("api/cloud-history", { signal }).catch(() => null);
  const [catalog, library] = await Promise.allSettled([
    request("api/catalog?category=real&page=1", { signal }),
    request("api/library", { signal }),
  ]);
  if (signal.aborted) return;
  if (library.status === "fulfilled") state.library = library.value;
  const items = catalog.status === "fulfilled" ? catalog.value.items : [];
  function show(recent) {
    const feature = recent[0]?.series || items[0];
    if (feature) outlet.replaceChildren(spotlight(feature, recent[0]));
    else outlet.replaceChildren();
    if (recent.length > 1) {
      const cards = recent.slice(1, 11).map(entry => card(entry.series, "recent", entry));
      outlet.append(shelf("接着看", "recent", cards, () => openLibrary("history"), signal));
    }
    if (items.length) {
      const hot = items.filter(series => series.id !== feature?.id);
      const cards = (hot.length ? hot : items).slice(0, 12).map(series => card(series, "hot"));
      outlet.append(shelf("热门发现", "hot", cards, browseReal, signal));
    } else {
      const message = catalog.status === "rejected" ? catalog.reason.message : "热门作品暂不可用";
      outlet.append(status(message, () => openSection("home")));
    }
  }
  show(state.library.history);
  const result = await accountHistory;
  if (!signal.aborted && result?.rows.length && !outlet.contains(document.activeElement) && main.scrollTop === 0) {
    show(combinedHistory(state.library.history, result.rows));
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

function newFeed(category) {
  return { category, items: [], index: 0, page: 0, hasMore: true, loading: false };
}

async function extendFeed(feed, signal) {
  const page = feed.page + 1;
  const results = await request(`api/catalog?category=${feed.category}&page=${page}`, { signal });
  if (signal.aborted) return;
  const seen = new Set(feed.items.map(series => series.id));
  const items = results.items.filter(series => {
    if (seen.has(series.id)) return false;
    seen.add(series.id);
    return true;
  });
  // Shuffle each page once so going back returns to the same works.
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [items[i], items[j]] = [items[j], items[i]];
  }
  const watched = new Set(state.library.history.map(row => row.series.id));
  items.sort((a, b) => Number(watched.has(a.id)) - Number(watched.has(b.id)));
  feed.items.push(...items);
  feed.page = page;
  feed.hasMore = results.has_more && page < 100;
}

function syncFeedButtons(view) {
  if (!view?.feed) return;
  const { index, items, hasMore, loading } = view.feed;
  view.previous.disabled = loading || index === 0;
  view.next.disabled = loading || (index + 1 >= items.length && !hasMore);
}

async function playFeedItem(feed, index, signal) {
  const series = feed.items[index];
  const detail = await request(`api/detail/${series.id}`, { signal });
  if (signal.aborted) return;
  const saved = detail.episodes.find(episode => episode.id === detail.resume?.episode && episode.available);
  const episode = saved || detail.episodes.find(episode => episode.available);
  feed.index = index;
  state.detail = detail;
  openPlayer(episode, saved ? detail.resume.position : 0, undefined, feed);
}

async function renderFeed(outlet, signal) {
  const feed = state.feed?.category === state.feedCategory ? state.feed : newFeed(state.feedCategory);
  state.feed = feed;
  if (feed.page === 0) {
    const library = await request("api/library", { signal }).catch(() => null);
    if (signal.aborted) return;
    if (library) state.library = library;
  }
  for (let i = 0; i < 3 && !feed.items.length && feed.hasMore; i++) {
    await extendFeed(feed, signal);
    if (signal.aborted) return;
  }
  if (signal.aborted) return;
  if (feed.items.length) await playFeedItem(feed, feed.index, signal);
  else outlet.replaceChildren(status("暂无可推荐的作品", () => {
    state.feed = null;
    openSection("feed");
  }));
}

async function shiftFeed(view, direction) {
  const feed = view.feed;
  const index = feed.index + direction;
  if (feed.loading || index < 0 || (index >= feed.items.length && !feed.hasMore)) return;
  const { signal } = state.navigation;
  feed.loading = true;
  const previousLabel = view.episodeLabel.textContent;
  view.episodeLabel.textContent = "正在切换剧集…";
  syncFeedButtons(view);
  try {
    // Try a few more pages when the catalogue repeats previous entries.
    for (let i = 0; i < 3 && index >= feed.items.length && feed.hasMore; i++) {
      await extendFeed(feed, signal);
      if (signal.aborted) return;
    }
    if (feed.items[index]) await playFeedItem(feed, index, signal);
    else view.episodeLabel.textContent = feed.hasMore ? "暂未找到新剧，可继续换剧" : "已看完本次推荐";
  } catch (error) {
    if (!signal.aborted) view.episodeLabel.textContent = error.message || previousLabel;
  } finally {
    feed.loading = false;
    if (state.player === view) {
      const control = direction < 0 ? view.previous : view.next;
      syncFeedButtons(view);
      if (!control.disabled && document.activeElement === document.body) control.focus({ preventScroll: true });
      showControls();
    }
  }
}

function selectFeedCategory(category) {
  if (category === state.feedCategory && state.player?.feed) return;
  state.feedCategory = category;
  openSection("feed", { focus: `tab:${category}` });
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
  state.detailTrail = [];
  state.section = section;
  state.page = page;
  shell.dataset.view = "catalog";
  for (const item of nav) {
    if (item.dataset.section === section && !item.classList.contains("brand")) item.setAttribute("aria-current", "page");
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
  if (section === "feed") frame.append(tabs(categories, state.feedCategory, selectFeedCategory));
  if (section === "rank") frame.append(tabs(boards, state.board, (board) => {
    state.board = board;
    openSection("rank", { focus: `tab:${board}` });
  }));
  if (section === "search") frame.append(searchForm(signal));
  if (section === "library") frame.append(tabs([["history", "观看历史"], ["favorites", "收藏"], ["later", "稍后看"], ["reservations", "预约"]], state.libraryTab, openLibrary));
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
      await renderFeed(outlet, signal);
    } else if (section === "library" && state.libraryTab === "reservations") {
      await renderReservations(outlet, signal);
    } else if (section === "library") {
      const library = await request("api/library", { signal });
      if (signal.aborted) return;
      state.library = library;
      await renderSavedLibrary(outlet, library, state.libraryTab, signal);
    } else if (section === "browse") {
      await discoveryPage(outlet, state.discovery, signal, { node, button, post, card, grid, status, scroller: main });
    } else {
      const params = new URLSearchParams({ page: String(page), q: state.query });
      const results = await request(`api/catalog?${params}`, { signal });
      if (signal.aborted) return;
      outlet.replaceChildren(node("h2", "result-heading", `“${state.query}”的搜索结果`));
      outlet.append(results.items.length ? grid(results.items.map(entry => card(entry, section))) : status("没有找到匹配的剧集"));
      if (page > 1 || results.has_more) outlet.append(pagination(section, page, results.has_more, focus));
    }
    if (restore && !signal.aborted) restorePoint(state.returnPoint);
  } catch (error) {
    if (!signal.aborted) {
      outlet.replaceChildren(status(error.message, () => openSection(section, { page, restore, focus })));

    }
  }
}

async function renderSavedLibrary(outlet, library, tab, signal) {
  const results = node("div");
  const toolbar = node("div", "saved-library-toolbar");
  const message = node("p", "meta");
  message.setAttribute("role", "status");
  const isHistory = tab === "history";
  const current = isHistory ? library.history : library[tab];
  let cloud = [];
  let mode = "all";
  const select = namedControl(node("select"), "记录来源");
  for (const [id, name] of [["all", "全部记录"], ["local", "本机"], ["account", "账户"]]) {
    const option = node("option", "", name);
    option.value = id;
    select.append(option);
  }
  select.addEventListener("change", () => {
    mode = select.value;
    show();
  });
  function show() {
    const account = isHistory
      ? cloud.map(row => ({ ...row, account: true, from_tv: true }))
      : cloud.map(row => row.series);
    let rows = mode === "local" ? current : mode === "account" ? account : current;
    if (mode === "all" && cloud.length) {
      if (isHistory) rows = combinedHistory(current, cloud);
      else {
        const byId = new Map(current.map(series => [series.id, series]));
        for (const series of account) if (!byId.has(series.id)) byId.set(series.id, series);
        rows = [...byId.values()];
      }
    }
    const cards = rows.map(entry => isHistory
      ? card(entry.series, tab, { ...entry, resumeSource: mode })
      : card(entry, tab));
    const empty = { history: "暂无观看历史", favorites: "还没有收藏", later: "暂无稍后看的作品" };
    results.replaceChildren(cards.length ? grid(cards) : status(empty[tab]));
  }
  async function read(fresh = false) {
    refresh.disabled = true;
    message.textContent = "正在读取账户记录…";
    try {
      const path = isHistory ? "api/cloud-history" : "api/cloud-favorite";
      const reply = await request(fresh ? `${path}?fresh=true` : path, { signal });
      if (signal.aborted) return;
      cloud = reply.rows;
      message.textContent = reply.notice || "";
      select.hidden = false;
      refresh.hidden = false;
      signIn.hidden = true;
      show();
    } catch (error) {
      if (signal.aborted) return;
      const unsigned = error.status === 401;
      if (unsigned || error.status === 409) {
        cloud = [];
        select.hidden = true;
        select.value = mode = "all";
        show();
      }
      refresh.hidden = unsigned;
      signIn.hidden = !unsigned;
      message.textContent = unsigned ? "登录可查看账户记录" : error.message;
    } finally {
      refresh.disabled = false;
    }
  }
  const signIn = button("登录红果账户", accountSignIn, "quiet");
  const refresh = button("刷新账户记录", () => read(true), "quiet");
  select.hidden = refresh.hidden = signIn.hidden = true;
  toolbar.append(select, refresh, signIn, message);
  outlet.replaceChildren(...(tab === "later" ? [] : [toolbar]), results);
  show();
  if (tab !== "later") await read();
}

function accountSignIn() {
  openSection("settings", { settingsTab: "account" });
}

function reservationDate(row) {
  if (row.expected) return row.expected;
  if (row.scheduled_at > 0 && row.scheduled_at <= 8_640_000_000_000_000) {
    return `${new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(row.scheduled_at)} 预计上线`;
  }
  return "待上线";
}

function reservationCard(series, meta, action) {
  const item = node("article", "card reservation-card");
  const copy = node("div", "card-copy");
  copy.append(node("h3", "card-title", series.title), node("p", "card-meta", meta));
  if (action) copy.append(action);
  item.append(poster(series), copy);
  return item;
}

async function renderReservations(outlet, signal) {
  const filters = tabs([["pending", "待上线"], ["online", "已上线"]], state.reservationTab, tab => {
    state.reservationTab = tab;
    openSection("library", { focus: `tab:reservation-${tab}` });
  });
  for (const item of filters.querySelectorAll("[data-key]")) {
    item.dataset.key = item.dataset.key.replace("tab:", "tab:reservation-");
  }
  const results = node("div", "reservation-results");
  outlet.replaceChildren(filters, results);
  const online = state.reservationTab === "online";
  focusKey(`tab:reservation-${state.reservationTab}`);
  async function load() {
    results.replaceChildren(status("正在加载预约…"));
    try {
      const rows = await request(`api/reservations?online=${online}`, { signal });
      if (signal.aborted) return;
      const cards = rows.map(row => online
        ? card(row.series, "reservation")
        : reservationCard(row.series, reservationDate(row)));
      const message = online ? "暂无已上线的预约" : "暂无待上线的预约";
      results.replaceChildren(button("刷新预约", load, "quiet reservation-refresh"),
        cards.length ? grid(cards) : status(message));
    } catch (error) {
      if (signal.aborted) return;
      if (error.status === 401) {
        const signIn = status("登录红果账户后查看预约");
        signIn.firstChild.append(button("登录红果账户", accountSignIn, "primary"));
        results.replaceChildren(signIn);
      } else {
        results.replaceChildren(status(error.message, load));
      }
    }
  }
  await load();
}

async function renderRelated(section, detail, signal) {
  const seriesId = detail.series.id;
  const fallback = () => {
    section.replaceChildren();
    if (detail.related.length) {
      section.append(shelf("相关推荐", "related", detail.related.map(entry => card(entry, "related")), null, signal));
    }
  };
  fallback();
  let works;
  try {
    works = await request(`api/detail/${seriesId}/related`, { signal });
  } catch {
    if (!signal.aborted) section.append(button("重新加载相关作品", () => renderRelated(section, detail, signal), "quiet"));
    return;
  }
  if (signal.aborted || !works.length) return;
  const upcoming = works.some(work => work.upcoming);
  let rows = [];
  let signedIn = true;
  if (upcoming) {
    // Only the upcoming entries need the account's private reservation labels.
    try {
      rows = await request("api/reservations?online=false", { signal });
    } catch (error) {
      if (error.status === 401) signedIn = false;
    }
  }
  if (signal.aborted) return;
  const cards = works.map(work => {
    const { series } = work;
    if (!work.upcoming) return card(series, "related-works");
    const row = rows.find(item => item.series.id === series.id || item.series.id === `reservation:${series.id}`);
    let result = row ? "confirmed" : "";
    const note = node("p", "card-meta reservation-note");
    note.setAttribute("role", "status");
    const action = button(row ? "已预约" : signedIn ? "预约" : "登录后预约", async () => {
      if (!signedIn) return accountSignIn();
      const previous = action.textContent;
      action.disabled = true;
      action.textContent = result ? "查询中…" : "预约中…";
      note.textContent = "";
      try {
        result = await post("api/reservations", { from: seriesId, id: series.id });
        if (signal.aborted) return;
        action.textContent = result === "confirmed" ? "已预约" : "查询预约状态";
        if (result === "submitted") note.textContent = "已提交，等待红果更新预约列表";
        if (result === "unknown") note.textContent = "提交结果待确认";
      } catch (error) {
        if (signal.aborted) return;
        if (error.status === 401) signedIn = false;
        action.textContent = signedIn ? previous : "登录后预约";
        note.textContent = error.message;
      } finally {
        action.disabled = result === "confirmed";
      }
    });
    action.disabled = result === "confirmed";
    const meta = [work.season, row ? reservationDate(row) : "待上线"].filter(Boolean).join(" · ");
    const item = reservationCard(series, meta, action);
    item.lastElementChild.append(note);
    return item;
  });
  const title = node("h2", "", "相关作品");
  const heading = node("div", "section-heading");
  heading.append(title);
  const worksSection = node("section", "shelf related-works");
  worksSection.append(heading, grid(cards));
  fallback();
  section.prepend(worksSection);
}

function grid(cards) {
  const items = node("div", "grid");
  items.append(...cards);
  return items;
}

function backToSection() {
  openSection(state.section, { page: state.page, restore: true });
}

function currentPoint() {
  return {
    top: main.scrollTop,
    key: document.activeElement?.dataset.key,
    shelves: new Map([...main.querySelectorAll("[data-shelf]")].map(item => [item.dataset.shelf, item.scrollLeft])),
  };
}

function backFromDetail() {
  const previous = state.detailTrail.pop();
  if (previous) openDetail(previous.id, false, previous.point);
  else backToSection();
}

async function openDetail(id, play = false, restore, source = "all") {
  if (shell.dataset.view !== "detail") {
    state.returnPoint = currentPoint();
  } else if (!restore && !state.player && state.detail && state.detail.series.id !== id) {
    state.detailTrail.push({ id: state.detail.series.id, point: currentPoint() });
    if (state.detailTrail.length > 12) state.detailTrail.shift();
  }
  const signal = nextNavigation();
  const cached = source === "all" && state.player?.detail.series.id === id ? state.player.detail : null;
  const stopped = leavePlayer();
  state.detail = cached;
  shell.dataset.view = "detail";
  const frame = node("div", "page detail-page");
  const heading = node("header", "page-heading");
  const back = button("返回", backFromDetail, "back", "left");
  heading.append(back);
  const outlet = node("div");
  const show = (detail) => {
    const saved = detail.episodes.find(episode => episode.id === detail.resume?.episode && episode.available);
    const selected = saved || detail.episodes.find(episode => episode.available);
    const position = saved ? detail.resume.position : 0;
    outlet.replaceChildren(renderDetail(detail, selected, position, restore && (() => {
      if (document.activeElement === back) restorePoint(restore);
    })));
    if (restore) restorePoint(restore);
    if (play && selected?.available) openPlayer(selected, position);
  };
  if (cached) show(cached);
  else outlet.append(status("正在读取剧集…"));
  frame.append(heading, outlet);
  main.replaceChildren(frame);
  main.scrollTop = 0;
  back.focus({ preventScroll: true });
  try {
    await stopped;
    const [detail, library] = await Promise.all([
      request(`api/detail/${id}?resume=${source}`, { signal }),
      request("api/library", { signal }).catch(() => state.library),
    ]);
    // Playback may have started from the cached detail while the refresh was pending.
    if (signal.aborted || state.player) return;
    state.library = library;
    if (!cached || !outlet.contains(document.activeElement)) {
      state.detail = detail;
      show(detail);
    }
  } catch (error) {
    if (!signal.aborted && !cached) outlet.replaceChildren(status(error.message, () => openDetail(id, play, restore, source)));
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

function renderDetail(detail, selected, position, relatedReady) {
  const { series, episodes } = detail;
  const history = state.library.history.find((item) => item.series.id === series.id);
  const layout = node("div", "detail");
  const hero = node("section", "detail-hero");
  const copy = node("div", "detail-copy");
  copy.append(node("h1", "", series.title));
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
  actions.append(play, favorite, later, cloudFavoriteAction(series.id, state.navigation.signal));
  const first = selected || episodes[0];
  if (first) actions.append(officialLink(series, first));
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
  layout.append(hero, episodePicker(episodes, selected || episodes[0], history));
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
  const related = node("div", "detail-related");
  layout.append(related);
  renderRelated(related, detail, state.navigation.signal).then(relatedReady);
  return layout;
}

function cloudFavoriteAction(id, signal) {
  let revision;
  let saved = false;
  let pending = null;
  let guest = false;
  const action = button("账户收藏", async () => {
    if (guest) return accountSignIn();
    if (!revision) return read();
    const desired = pending ?? !saved;
    action.disabled = true;
    action.textContent = "正在确认…";
    try {
      const result = await post("api/cloud-favorite", { id, saved: desired, revision });
      if (signal.aborted) return;
      if (result.confirmed) {
        pending = null;
        saved = desired;
      } else pending = desired;
    } catch (error) {
      if (signal.aborted) return;
      action.title = error.message;
      if (error.status === 401 || error.status === 409) return read();
    } finally {
      if (!signal.aborted) show();
    }
  }, "quiet");
  function show() {
    action.disabled = false;
    action.textContent = guest ? "登录后收藏到账户" : pending !== null ? "查询收藏状态" :
      saved ? "已收藏到账户" : revision ? "收藏到账户" : "重读账户收藏";
    action.setAttribute("aria-pressed", String(saved));
  }
  async function read() {
    action.disabled = true;
    try {
      const reply = await request("api/cloud-favorite", { signal });
      if (signal.aborted) return;
      if (revision !== reply.revision) pending = null;
      revision = reply.revision;
      saved = reply.rows.some(row => row.series.id === id);
      guest = false;
    } catch (error) {
      if (signal.aborted) return;
      revision = undefined;
      saved = false;
      pending = null;
      guest = error.status === 401;
      action.title = error.message;
    } finally {
      if (!signal.aborted) show();
    }
  }
  read();
  return action;
}

function episodeCell(episode, current, history, inPlayer = false) {
  const playing = episode.id === current?.id;
  const unavailable = inPlayer ? "暂不可播" : "官网";
  const label = playing ? "当前" : history?.episode === episode.id
    ? (history.completed ? "已看" : "上次") : episode.full ? "" : episode.available ? "试看" : unavailable;
  const cell = button(String(episode.number), () => {
    if (!inPlayer || episode.available) openPlayer(episode);
  }, episode.available ? "" : "external-episode");
  cell.dataset.episode = episode.id;
  const availability = episode.full ? "" : episode.available ? "，公开试看" : `，${unavailable}`;
  cell.setAttribute("aria-label", `第 ${episode.number} 集${availability}`);
  if (playing) cell.setAttribute("aria-current", "true");
  if (inPlayer && !episode.available) cell.setAttribute("aria-disabled", "true");
  if (label) cell.append(node("span", "episode-flag", label));
  return cell;
}

function episodePicker(episodes, current, history) {
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
    const cells = entries.map(episode => episodeCell(episode, current, history));
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
  if (episodes.length > groupSize) {
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

function savePosition(session, completed = false, flush = false) {
  const video = session.video;
  if (!Number.isFinite(video.duration) || video.currentTime <= 0) return;
  const duration = Math.round(video.duration * 1000);
  const position = Math.min(duration, Math.round(video.currentTime * 1000));
  const update = { position, duration, completed, sequence: ++session.sequence, sync: session.watched, flush };
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
    session.danmaku?.destroy();
    session.recovery?.abort();
    session.seasonRequest?.abort();
    clearTimeout(session.recoveryTimer);
    savePosition(session, session.video.ended, true);
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
    state.player.commentRequest?.abort();
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
  const session = state.playing;
  const video = session?.video;
  if (!video) return;
  if (video.paused) {
    state.player.sleepStopped = false;
    if (video.ended) video.currentTime = 0;
    session.wantsPlayback = true;
    video.play().catch(() => { session.wantsPlayback = false; });
  } else {
    session.wantsPlayback = false;
    video.pause();
  }
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

function showPlaybackEpisodes(view, history) {
  const episodes = view.detail.episodes;
  const current = view.detail.episodes[view.index];
  const picker = node("section", "watch-episode-picker");
  const header = node("div", "watch-episode-header");
  const title = node("h2", "", "选集");
  title.append(node("span", "count", `全 ${episodes.length} 集`));
  const close = playbackButton("收起", "collapse", () => toggleEpisodes(view, false, true), "quiet");
  header.append(title);

  const list = node("div", "watch-episode-list");
  const grid = node("div", "episode-grid");
  grid.setAttribute("role", "group");
  grid.setAttribute("aria-label", "分集");
  const cells = episodes.map(episode => episodeCell(episode, current, history, true));
  let focused = Math.max(0, view.index);
  cells.forEach((cell, index) => { cell.tabIndex = index === focused ? 0 : -1; });
  grid.append(...cells);
  list.append(grid);
  picker.append(header, list);

  function focusEpisode(index, showNearby = false) {
    const cell = cells[index];
    if (!cell) return;
    cell.focus({ preventScroll: true });
    const visible = list.getBoundingClientRect();
    const item = cell.getBoundingClientRect();
    if (showNearby) {
      const row = item.height + parseFloat(getComputedStyle(grid).rowGap);
      list.scrollTop += item.top - visible.top - row * 2 - 10;
    } else if (item.top < visible.top + 8) list.scrollTop += item.top - visible.top - 8;
    else if (item.bottom > visible.bottom - 8) list.scrollTop += item.bottom - visible.bottom + 8;
  }

  grid.addEventListener("focusin", event => {
    const index = cells.indexOf(event.target);
    if (index < 0) return;
    if (cells[focused]) cells[focused].tabIndex = -1;
    focused = index;
    cells[focused].tabIndex = 0;
  });
  grid.addEventListener("keydown", event => {
    if (!cells.includes(event.target) || event.altKey || event.metaKey || event.ctrlKey) return;
    const columns = getComputedStyle(grid).gridTemplateColumns.trim().split(/\s+/).length;
    const offsets = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -columns, ArrowDown: columns };
    if (event.key === "Home") focusEpisode(0);
    else if (event.key === "End") focusEpisode(cells.length - 1);
    else if (Object.hasOwn(offsets, event.key)) {
      focusEpisode(Math.max(0, Math.min(cells.length - 1, focused + offsets[event.key])));
    } else return;
    event.preventDefault();
  });

  if (episodes.length > 1) {
    const jump = node("form", "episode-jump");
    const number = namedControl(node("input"), "跳转集数");
    number.type = "number";
    number.min = episodes[0].number;
    number.max = episodes.at(-1).number;
    number.placeholder = "集数";
    number.required = true;
    const go = node("button", "", "定位");
    go.type = "submit";
    number.addEventListener("input", () => number.setCustomValidity(""));
    jump.addEventListener("submit", event => {
      event.preventDefault();
      const index = episodes.findIndex(episode => episode.number === number.valueAsNumber);
      if (index >= 0) focusEpisode(index, true);
      else {
        number.setCustomValidity("未找到这一集");
        number.reportValidity();
      }
    });
    jump.append(number, go);
    header.append(jump);
  }
  header.append(close);
  view.panel.replaceChildren(picker);
  if (cells.length) focusEpisode(focused, true);
  else close.focus();
}

function toggleEpisodes(view, open, restoreFocus = false) {
  if (open) {
    toggleMore(view, false);
    toggleComments(view, false);
  }
  view.menu.setAttribute("aria-expanded", String(open));
  view.panel.hidden = !open;
  syncWatchDrawer(view);
  if (open) {
    const history = state.library.history.find((entry) => entry.series.id === view.detail.series.id);
    showPlaybackEpisodes(view, history);
  } else {
    view.panel.replaceChildren();
    if (restoreFocus) view.menu.focus({ preventScroll: true });
  }
  showControls();
}

function syncWatchDrawer(view) {
  view.drawer.hidden = view.panel.hidden && view.comments.hidden;
  view.drawer.dataset.mode = view.panel.hidden ? "comments" : "episodes";
}

function showControls() {
  const view = state.player;
  if (!view) return;
  delete watch.dataset.idle;
  clearTimeout(view.hideTimer);
  const video = state.playing?.video;
  if (document.fullscreenElement !== watch || !video || video.paused || video.ended || !view.drawer.hidden || !view.more.hidden) return;
  view.hideTimer = setTimeout(() => {
    if (state.player === view && !watch.querySelector(".watch-header:hover, .watch-footer:hover, .watch-header:focus-within, .watch-footer:focus-within")) {
      watch.dataset.idle = "true";
    }
  }, 2800);
}

function toggleMore(view, open, restoreFocus = false) {
  if (open) {
    toggleEpisodes(view, false);
    toggleComments(view, false);
  }
  view.more.hidden = !open;
  view.moreButton.setAttribute("aria-expanded", String(open));
  if (open) view.more.querySelector("select")?.focus({ preventScroll: true });
  else if (restoreFocus) view.moreButton.focus({ preventScroll: true });
  showControls();
}

async function toggleComments(view, open, restoreFocus = false) {
  view.commentRequest?.abort();
  view.comments.hidden = !open;
  syncWatchDrawer(view);
  view.commentsButton.setAttribute("aria-expanded", String(open));
  if (!open) {
    if (restoreFocus) view.commentsButton.focus({ preventScroll: true });
    showControls();
    return;
  }
  toggleEpisodes(view, false);
  toggleMore(view, false);
  const episode = view.detail.episodes[view.index];
  const controller = new AbortController();
  view.commentRequest = controller;
  const { signal } = controller;
  const heading = node("h2", "", `第 ${episode.number} 集评论`);
  const body = status("正在读取公开评论…");
  view.comments.replaceChildren(heading, body);
  view.comments.scrollTop = 0;
  view.comments.focus({ preventScroll: true });
  showControls();
  try {
    const { items, total } = await request(`api/detail/${view.detail.series.id}/episodes/${episode.id}/comments`, { signal });
    if (signal.aborted) return;
    if (!items.length) {
      body.replaceWith(status("本集暂无公开评论"));
      return;
    }
    if (total > items.length) heading.append(node("span", "count", `已显示 ${items.length} 条 · 共 ${total} 条`));
    body.replaceWith(...items.map(({ author, content }) => {
      const review = node("article", "review");
      review.append(node("strong", "", author), node("p", "summary", content));
      return review;
    }));
  } catch (error) {
    if (!signal.aborted) body.replaceWith(status(error.message, () => toggleComments(view, true)));
  }
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
  row("常驻播放进度", "show_progress", null, value => { view.ambient.hidden = !value; });
  row("跨季自动连播", "season_autoplay", null, enabled => {
    if (!enabled) view.seasonBanner.hidden = true;
    else if (state.playing) offerNextSeason(state.playing);
  });
  for (const [key, label, options] of danmakuOptions) {
    row(label, key, options, () => state.playing?.danmaku?.update());
  }
  panel.append(button("从头播放", () => {
    seekTo(0);
    toggleMore(view, false, true);
  }));
  return panel;
}

function preferenceControl(label, ...controls) {
  const row = node("div", "settings-row");
  const group = node("div", "transport-group");
  group.append(...controls);
  row.append(node("span", "", label), group);
  return row;
}

// Geometry for the shared controls follows the TV client's TvGlyph.
const playbackGlyphs = {
  play: "M7 3L21 12 7 21Z",
  pause: "M8 4v16M16 4v16",
  episodes: "M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z M15 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z M5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1Z M15 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1Z",
  danmaku: "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z M5 9h6m4 0h4m-11 6h9",
  comments: "M3 4h18v13H10l-5 4v-4H3Z M7 9h10m-10 4h6",
  settings: "M12 5a7 7 0 1 0 0 14 7 7 0 1 0 0-14Z M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5Z M12 2v3m7.07-.07-2.12 2.12M22 12h-3m.07 7.07-2.12-2.12M12 22v-3m-7.07.07 2.12-2.12M2 12h3m-.07-7.07 2.12 2.12",
  previous: "M5 5v14m14-14L8 12l11 7Z",
  next: "M19 5v14M5 5l11 7-11 7Z",
  back: "M15 5l-7 7 7 7",
  collapse: "M9 5l7 7-7 7",
  detail: "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18Zm0 8v5m0-8v.1",
  fullscreen: "M3 9V3h6m6 0h6v6m0 6v6h-6m-6 0H3v-6",
  windowed: "M9 3v6H3m12-6v6h6m0 6h-6v6m-6 0v-6H3",
  sound: "M3 9h4l5-4v14l-5-4H3Zm12 0a4 4 0 0 1 0 6m3-8a7 7 0 0 1 0 10",
  muted: "M3 9h4l5-4v14l-5-4H3Zm13 1 5 5m0-5-5 5",
};

function setPlaybackIcon(control, label, glyph) {
  if (control.dataset.glyph !== glyph) {
    const ns = "http://www.w3.org/2000/svg";
    const symbol = document.createElementNS(ns, "svg");
    symbol.setAttribute("viewBox", "0 0 24 24");
    symbol.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", playbackGlyphs[glyph]);
    symbol.append(path);
    control.replaceChildren(symbol);
    control.dataset.glyph = glyph;
  }
  if (control.title !== label) namedControl(control, label);
  return control;
}

function playbackButton(label, glyph, action, style = "") {
  return setPlaybackIcon(button("", action, `watch-icon ${style}`.trim()), label, glyph);
}

function createPlayer(detail, feed) {
  const preferences = state.preferences;
  const view = { detail, feed, index: 0, rate: preferences.speed / 100, volume: 1, muted: false, scrubbing: false };
  const header = node("header", "watch-header");
  const back = playbackButton(feed ? "退出随心看" : "详情", "back", () => {
    if (feed) openSection("home");
    else openDetail(view.detail.series.id);
  }, "back quiet");
  const heading = node("div", "watch-heading");
  view.episodeLabel = node("p", "meta");
  view.episodeLabel.setAttribute("role", "status");
  view.title = node("h1", "", detail.series.title);
  heading.append(view.title, view.episodeLabel);
  header.append(back, heading);
  if (feed) {
    const filters = namedControl(node("select", "watch-feed-category"), "随心看分类");
    for (const [id, label] of categories) {
      const option = node("option", "", label);
      option.value = id;
      filters.append(option);
    }
    filters.value = feed.category;
    filters.addEventListener("change", () => selectFeedCategory(filters.value));
    header.append(filters, playbackButton("剧集详情", "detail", () => openDetail(view.detail.series.id), "quiet"));
  }
  view.stage = namedControl(node("div", "watch-stage"), "视频画面");
  view.stage.setAttribute("role", "group");
  view.stage.tabIndex = 0;
  view.stage.dataset.frame = preferences.frame;
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
  const move = direction => feed ? shiftFeed(view, direction) : shiftEpisode(direction);
  view.previous = playbackButton(feed ? "上一部" : "上一集", "previous", () => move(-1));
  view.next = playbackButton(feed ? "下一部" : "下一集", "next", () => move(1));
  view.play = playbackButton("播放", "play", toggleVideo, "primary");
  basic.append(view.previous, view.play, view.next);
  const settings = node("div", "transport-group");
  view.menu = playbackButton("选集", "episodes", () => toggleEpisodes(view, view.panel.hidden, true));
  view.menu.setAttribute("aria-expanded", "false");
  view.menu.setAttribute("aria-controls", "watch-episodes");
  view.moreButton = playbackButton("播放设置", "settings", () => toggleMore(view, view.more.hidden, true));
  view.moreButton.setAttribute("aria-expanded", "false");
  view.moreButton.setAttribute("aria-controls", "watch-options");
  view.commentsButton = playbackButton("本集评论", "comments", () => toggleComments(view, view.comments.hidden, true));
  view.commentsButton.setAttribute("aria-expanded", "false");
  view.commentsButton.setAttribute("aria-controls", "watch-comments");
  view.quality = namedControl(node("select"), "清晰度");
  view.quality.disabled = true;
  view.quality.addEventListener("change", () => switchQuality(view));
  const speed = namedControl(node("select", "watch-speed"), "播放倍速");
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
  const mute = playbackButton("静音", "sound", () => {
    view.muted = !view.muted;
    mute.setAttribute("aria-pressed", String(view.muted));
    setPlaybackIcon(mute, view.muted ? "取消静音" : "静音", view.muted ? "muted" : "sound");
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
  playerPreference(view, auto, "auto_next", enabled => {
    if (!enabled) view.seasonBanner.hidden = true;
    else if (state.playing) offerNextSeason(state.playing);
    showControls();
  });
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
  view.more = playerOptions(view);
  view.more.prepend(
    node("h2", "", "播放设置"),
    preferenceControl("定时停止", view.sleep),
    preferenceControl("音量", volume, mute),
    preferenceControl("连播", autoLabel),
  );
  const danmaku = playbackButton("弹幕", "danmaku", async () => {
    danmaku.disabled = true;
    try {
      await savePreference("danmaku", !state.preferences.danmaku);
      if (state.player !== view) return;
      danmaku.setAttribute("aria-pressed", String(state.preferences.danmaku));
      state.playing?.danmaku?.update();
    } catch (error) {
      if (state.player === view) view.episodeLabel.textContent = error.message;
    } finally {
      danmaku.disabled = false;
    }
  });
  danmaku.setAttribute("aria-pressed", String(preferences.danmaku));
  view.danmakuNote = node("span", "danmaku-note");
  view.danmakuNote.setAttribute("role", "status");
  view.danmakuNote.hidden = true;
  settings.append(view.menu, speed, danmaku, view.commentsButton, view.quality, view.moreButton);
  if (document.fullscreenEnabled && watch.requestFullscreen) {
    view.fullscreen = playbackButton("全屏", "fullscreen", async () => {
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
  view.comments = node("section", "watch-panel watch-comments");
  view.comments.id = "watch-comments";
  view.comments.hidden = true;
  view.comments.tabIndex = -1;
  view.comments.setAttribute("aria-label", "本集评论");
  view.seasonBanner = node("div", "watch-season");
  view.seasonBanner.hidden = true;
  footer.append(view.more, view.seasonBanner, view.danmakuNote, timeline, transport);
  view.drawer = node("aside", "watch-drawer");
  view.drawer.hidden = true;
  view.drawer.append(view.panel, view.comments);
  const theater = node("div", "watch-theater");
  theater.append(view.stage, footer);
  const body = node("div", "watch-body");
  body.append(theater, view.drawer);
  watch.replaceChildren(header, body);
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
  view.seasonBanner.hidden = true;
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
  setPlaybackIcon(view.play, playing ? "暂停" : "播放", playing ? "pause" : "play");
}

function qualityOptions(view, playback) {
  view.quality.replaceChildren();
  view.quality.disabled = !playback || playback.qualities.length <= 1;
  if (!playback) {
    view.quality.append(node("option", "", "清晰度"));
    return;
  }
  for (const quality of playback.qualities) {
    const option = node("option", "", quality > 0 ? quality + "P" : "自动");
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

function offerNextSeason(session) {
  const { view, video, playback } = session;
  if (state.playing !== session || session.seasonChecked || view.feed || view.seasonCancelled || view.sleepStopped ||
      playback.scope !== "full" || !state.preferences.auto_next || !state.preferences.season_autoplay ||
      view.index !== view.detail.episodes.length - 1 || !Number.isFinite(video.duration) ||
      video.currentTime < video.duration / 2) return;
  session.seasonChecked = true;
  const controller = new AbortController();
  session.seasonRequest = controller;
  session.seasonCheck = request(`api/play/${session.token}/next-season`, { signal: controller.signal })
    .then(work => {
      if (!work || controller.signal.aborted || state.playing !== session || view.seasonCancelled ||
          !state.preferences.season_autoplay || !state.preferences.auto_next || view.sleepStopped) return null;
      const notice = node("div", "", `接下来播放《${work.series.title}》`);
      notice.setAttribute("role", "status");
      view.seasonBanner.replaceChildren(notice, button("取消跨季", () => {
        view.seasonCancelled = true;
        controller.abort();
        view.seasonBanner.hidden = true;
        view.play.focus({ preventScroll: true });
      }, "quiet"));
      view.seasonBanner.hidden = false;
      return work;
    }).catch(() => null);
}

async function continueSeason(session) {
  const { view } = session;
  const canContinue = () => state.playing === session && !view.seasonCancelled && !view.sleepStopped &&
    state.preferences.auto_next && state.preferences.season_autoplay;
  if (!canContinue()) return;
  const controller = session.seasonRequest;
  const work = await session.seasonCheck;
  if (!work || !controller || !canContinue()) return;
  try {
    const detail = await request(`api/detail/${work.series.id}`, { signal: controller.signal });
    if (controller.signal.aborted || !canContinue()) return;
    const first = detail.episodes[0];
    if (!first?.full || !first.available) return;
    state.detail = detail;
    openPlayer(first);
  } catch {
    if (!controller.signal.aborted && canContinue()) view.episodeLabel.textContent = "下一季暂时无法打开";
  } finally {
    if (state.playing === session) view.seasonBanner.hidden = true;
  }
}

async function recoverVideo(session, episode, position = Math.round(session.video.currentTime * 1000)) {
  const { view, video } = session;
  if (state.playing !== session || session.recovery) return;
  const canResume = () => session.wantsPlayback && !view.sleepStopped;
  clearTimeout(session.recoveryTimer);
  if (session.playback.recoverable) {
    const controller = new AbortController();
    session.recovery = controller;
    view.episodeLabel.textContent = "正在切换备用播放源…";
    let playback;
    let candidate;
    try {
      playback = await post(`api/play/${session.token}/recover`, {});
      if (controller.signal.aborted || state.playing !== session) return;
      candidate = node("video");
      candidate.preload = "auto";
      candidate.hidden = true;
      view.stage.append(candidate);
      await prepareVideo(candidate, `media/${playback.token}`, controller.signal);
      if (controller.signal.aborted || state.playing !== session) return;
      // The primary may have recovered while the new source was preparing.
      const resume = Math.round(video.currentTime * 1000) || position;
      const prepared = { playback, candidate, autoplay: canResume(), recovered: true };
      playback = candidate = null;
      await openPlayer(episode, resume, prepared);
      return;
    } catch {
      if (controller.signal.aborted || state.playing !== session) return;
    } finally {
      if (candidate) {
        candidate.removeAttribute("src");
        candidate.load();
        candidate.remove();
      }
      if (playback) release(playback.token);
      if (session.recovery === controller) session.recovery = null;
    }
  }
  if (state.playing !== session) return;
  const autoplay = canResume();
  stopPlayback();
  syncControls(view);
  showControls();
  view.stage.replaceChildren(status("视频读取失败", () => openPlayer(episode, position, { autoplay }), "watch-status"));
}

function watchStalls(session, episode) {
  const { video } = session;
  const clear = () => clearTimeout(session.recoveryTimer);
  const schedule = () => {
    if (state.playing !== session || !session.wantsPlayback || session.recovery || video.ended) return;
    if (session.recoveryTimer != null) return;
    session.recoveryTimer = setTimeout(() => {
      session.recoveryTimer = null;
      if (session.wantsPlayback && !video.ended) recoverVideo(session, episode);
    }, 10_000);
  };
  for (const event of ["waiting", "stalled"]) video.addEventListener(event, schedule);
  for (const event of ["playing", "pause", "ended", "canplay"]) video.addEventListener(event, () => {
    clear();
    session.recoveryTimer = null;
  });
}

async function openPlayer(episode, position = 0, prepared, feed) {
  const detail = state.detail;
  if (!detail || (!episode && !feed)) return;
  const focusStage = !state.player || state.player.panel.contains(document.activeElement) || document.activeElement === state.player.play;
  const view = state.player || createPlayer(detail, feed);
  state.player = view;
  const stopped = stopPlayback();
  shell.hidden = true;
  watch.hidden = false;
  view.detail = detail;
  view.title.textContent = detail.series.title;
  view.index = detail.episodes.indexOf(episode);
  view.seasonCancelled = false;
  view.seasonBanner.hidden = true;
  view.scrubbing = false;
  if (!prepared) view.sleepStopped = false;
  const label = episode ? `第 ${episode.number} 集` : "";
  view.episodeLabel.textContent = label;
  view.menu.disabled = detail.episodes.length === 0;
  view.commentsButton.disabled = !episode;
  if (view.feed) syncFeedButtons(view);
  else {
    view.previous.disabled = view.index <= 0;
    view.next.disabled = view.index >= detail.episodes.length - 1;
  }
  qualityOptions(view);
  toggleEpisodes(view, false);
  toggleMore(view, false);
  toggleComments(view, false);
  syncControls(view);
  if (focusStage || document.activeElement?.disabled) view.stage.focus({ preventScroll: true });
  if (!episode?.available) {
    view.stage.replaceChildren(status(episode ? "本集暂无可播放的来源" : "这部剧暂无可播放的分集", null, "watch-status"));
    return;
  }
  const controller = new AbortController();
  state.prepare = controller;
  const loading = node("p", "watch-message", "正在准备播放…");
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
    const autoplay = prepared?.autoplay ?? (view.feed ? state.preferences.feed_autoplay : true);
    const session = { video, playback, token: playback.token, view, sequence: 0, savedAt: 0, queue: Promise.resolve(),
      wantsPlayback: !view.sleepStopped && autoplay, watched: false, playedMs: 0 };
    watchStalls(session, episode);
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
        if (event === "play" || event === "pause") {
          if (!video.error) session.wantsPlayback = event === "play";
          showControls();
        }
        if (event === "seeked") session.lastClock = video.currentTime * 1000;
        if (event === "timeupdate" && !video.seeking) {
          const clock = video.currentTime * 1000;
          const elapsed = clock - (session.lastClock ?? clock);
          session.lastClock = clock;
          if (!video.paused && elapsed > 0 && elapsed <= 1800) {
            session.playedMs += elapsed;
            session.watched = session.playedMs >= 500;
          }
          offerNextSeason(session);
        }
        if (!video.ended && (event === "pause" || event === "seeked" ||
            event === "timeupdate" && performance.now() - session.savedAt > 4000)) {
          savePosition(session, false, event === "pause" || event === "seeked");
        }
      });
    }
    video.addEventListener("ended", () => {
      if (state.playing !== session) return;
      syncControls(view, video);
      showControls();
      savePosition(session, true, true);
      if (view.episodesLeft > 0 && --view.episodesLeft === 0) {
        stopForSleep(view);
        return;
      }
      const next = detail.episodes[view.index + 1];
      const sameScope = next?.full === (playback.scope === "full");
      if (view.sleepStopped || !state.preferences.auto_next || !view.panel.hidden || !view.more.hidden || !view.comments.hidden) return;
      if (next?.available && sameScope) openPlayer(next);
      else if (!next && state.preferences.season_autoplay) {
        offerNextSeason(session);
        continueSeason(session);
      }
    });
    video.addEventListener("error", () => recoverVideo(session, episode));
    view.stage.prepend(video);
    session.danmaku = attachDanmaku(view.stage, video, (start, duration, cursor, signal) => {
      const params = new URLSearchParams({ start, duration });
      if (cursor) params.set("cursor", cursor);
      return request(`api/play/${session.token}/danmaku?${params}`, { signal: AbortSignal.any([signal, AbortSignal.timeout(18_000)]) });
    }, () => state.preferences, unavailable => {
      view.danmakuNote.textContent = unavailable ? "弹幕暂时无法读取" : "";
      view.danmakuNote.hidden = !unavailable;
    });
    if (!view.sleepStopped) {
      const source = playback.qualities.length === 1 ? "仅提供 " : "";
      const mode = playback.scope === "full" ? "正片" : "公开试看";
      const quality = playback.quality > 0 ? `${playback.quality}P` : "自动";
      view.episodeLabel.textContent = `${label}${mode} · ${source}${quality}${prepared?.recovered ? " · 备用源" : ""}`;
    }
    qualityOptions(view, playback);
    if (!prepared?.candidate) video.src = `media/${playback.token}`;
    syncControls(view, video);
    if (session.wantsPlayback) video.play().catch(() => { session.wantsPlayback = false; });
  } catch (error) {
    if (controller.signal.aborted) return;
    view.stage.replaceChildren(status(error.message, error.status === 404 ? null : () => openPlayer(episode, position), "watch-status"));
  }
}

const shortcut = document.querySelector(".global-search kbd");
if (/Mac/.test(navigator.platform)) shortcut.textContent = "⌘ K";

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
      else if (!state.player.comments.hidden) toggleComments(state.player, false, true);
      else if (state.player.feed) openSection("home");
      else openDetail(state.player.detail.series.id);
    } else if (shell.dataset.view === "detail") backFromDetail();
    return;
  }
  if (event.key === "/" && !event.target.closest?.("input, textarea, select, [contenteditable]")) {
    event.preventDefault();
    openSection("search", { focus: "query" });
    return;
  }
  const stage = state.player?.stage;
  if (stage && (event.target === stage || event.target instanceof HTMLVideoElement)) {
    const video = state.playing?.video;
    if (event.key === " ") toggleVideo();
    else if (event.key === "ArrowLeft" || event.key === "ArrowRight") seekTo((video?.currentTime || 0) + (event.key === "ArrowLeft" ? -10 : 10));
    else if (state.player.feed && (event.key === "ArrowUp" || event.key === "ArrowDown")) {
      shiftFeed(state.player, event.key === "ArrowUp" ? -1 : 1);
    } else if (event.key.toLowerCase() === "p" || event.key.toLowerCase() === "n") {
      const direction = event.key.toLowerCase() === "p" ? -1 : 1;
      if (state.player.feed) shiftFeed(state.player, direction);
      else shiftEpisode(direction);
    }
    else return;
    event.preventDefault();

  }
});
document.addEventListener("fullscreenchange", () => {
  const control = state.player?.fullscreen;
  if (control) {
    const full = document.fullscreenElement === watch;
    setPlaybackIcon(control, full ? "退出全屏" : "全屏", full ? "windowed" : "fullscreen");
  }
  showControls();
});
window.addEventListener("pagehide", () => {
  if (state.playing) savePosition(state.playing, state.playing.video.ended, true);
});
request("api/preferences").then(applyPreferences, () => {}).finally(() => {
  const startup = state.preferences.startup;
  if (startup === "favorites" || startup === "history") openLibrary(startup);
  else openSection(startup || "home", { focus: startup === "search" ? "query" : null });
});
