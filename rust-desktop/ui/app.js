// SPDX-License-Identifier: GPL-3.0-only
const main = document.querySelector("#main");
const search = document.querySelector("#search");
const queryInput = document.querySelector("#query");
const nav = [...document.querySelectorAll("nav [data-section]")];

const categories = [["real", "真人剧"], ["comic", "漫剧"], ["ai", "AI 剧"]];
const names = { discover: "短剧", search: "搜索结果", favorites: "收藏", history: "继续观看" };
const state = {
  section: "discover", category: "real", query: "", page: 1, scroll: 0,
  library: { favorites: [], history: [] }, detail: null, playing: null,
  navigation: null, prepare: null, autoplay: true,
};

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text) element.textContent = text;
  return element;
}

function button(label, action, className) {
  const element = node("button", className, label);
  element.type = "button";
  element.addEventListener("click", action);
  return element;
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

function cover(series, eager = false) {
  if (!series.cover) return null;
  const img = node("img");
  img.alt = "";
  if (!eager) img.loading = "lazy";
  img.addEventListener("error", () => img.remove(), { once: true });
  try {
    const url = new URL(series.cover);
    if (url.protocol !== "https:") return null;
    img.src = url.href;
  } catch {
    return null;
  }
  return img;
}

function status(message, retry) {
  const panel = node("div", "empty");
  panel.setAttribute("role", "status");
  const inner = node("div");
  inner.append(node("p", "", message));
  if (retry) inner.append(button("重试", retry));
  panel.append(inner);
  return panel;
}

function formatPosition(milliseconds) {
  const total = Math.floor(milliseconds / 1000);
  const seconds = String(total % 60).padStart(2, "0");
  return `${Math.floor(total / 60)}:${seconds}`;
}

function historyLabel(history) {
  return history.completed ? `第 ${history.number} 集试看完毕`
    : `第 ${history.number} 集试看 · ${formatPosition(history.position)}`;
}

function grid(items) {
  const result = node("div", "grid");
  for (const { series, history } of items) {
    const card = button("", () => openDetail(series.id), "card");
    const poster = node("span", "poster");
    const image = cover(series);
    if (image) poster.append(image);
    card.append(poster, node("span", "card-title", series.title));
    const metadata = history ? historyLabel(history) : series.badge;
    if (metadata) card.append(node("span", "card-meta", metadata));
    result.append(card);
  }
  return result;
}

function banner(series) {
  const hero = node("article", "hero");
  const copy = node("div", "hero-copy");
  if (series.tags.length) copy.append(node("div", "meta", series.tags.slice(0, 3).join(" / ")));
  copy.append(node("h2", "", series.title));
  if (series.description) copy.append(node("p", "summary", series.description));
  copy.append(button("查看剧集", () => openDetail(series.id), "primary"));
  const picture = node("div", "hero-image");
  const image = cover(series, true);
  if (image) picture.append(image);
  hero.append(copy, picture);
  return hero;
}

function filters() {
  const group = node("div", "filters");
  for (const [category, label] of categories) {
    const option = button(label, () => {
      state.category = category;
      openCollection("discover");
    });
    option.setAttribute("aria-pressed", String(state.category === category));
    group.append(option);
  }
  return group;
}

function collectionHeader() {
  const label = state.section === "search" ? `“${state.query}”的搜索结果` : names[state.section];
  main.replaceChildren(node("h1", "", label));
  if (state.section === "discover") main.append(filters());
}

function chooseNavigation(section) {
  const current = section === "search" ? "discover" : section;
  for (const item of nav) {
    if (item.dataset.section === current) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
}

function navigate() {
  state.navigation?.abort();
  state.navigation = new AbortController();
  return state.navigation;
}

async function openCollection(section, page = 1, restore = false) {
  const navigation = navigate();
  const pendingStop = stopPlayback();
  state.detail = null;
  state.section = section;
  state.page = page;
  chooseNavigation(section);
  if (!restore) window.scrollTo(0, 0);
  collectionHeader();
  main.append(status("正在加载…"));
  try {
    if (section === "favorites" || section === "history") {
      await pendingStop;
      const saved = await request("api/library", { signal: navigation.signal });
      if (navigation.signal.aborted) return;
      state.library = saved;
      collectionHeader();
      const items = section === "favorites" ? saved.favorites.map((series) => ({ series }))
        : saved.history.map((history) => ({ series: history.series, history }));
      main.append(items.length ? grid(items) : status(section === "favorites" ? "还没有收藏" : "播放后可在这里继续观看"));
    } else {
      const params = new URLSearchParams({ page: String(page), category: state.category });
      if (section === "search") params.set("q", state.query);
      const results = await request(`api/catalog?${params}`, { signal: navigation.signal });
      if (navigation.signal.aborted) return;
      collectionHeader();
      let items = results.items;
      if (section === "discover" && page === 1 && items.length > 1) {
        main.append(banner(items[0]));
        items = items.slice(1);
      }
      main.append(items.length ? grid(items.map((series) => ({ series }))) : status("没有匹配的短剧，换个剧名试试"));
      if (page > 1 || results.has_more) {
        const paging = node("div", "paging");
        const previous = button("上一页", () => openCollection(section, page - 1));
        const next = button("下一页", () => openCollection(section, page + 1));
        previous.disabled = page === 1;
        next.disabled = !results.has_more;
        paging.append(previous, node("span", "", `第 ${page} 页`), next);
        main.append(paging);
      }
    }
    if (restore) window.scrollTo(0, state.scroll);
  } catch (error) {
    if (navigation.signal.aborted) return;
    collectionHeader();
    main.append(status(error.message, () => openCollection(section, page, restore)));
  }
}

async function openDetail(id) {
  const navigation = navigate();
  if (!state.detail) state.scroll = window.scrollY;
  const pendingStop = stopPlayback();
  state.detail = null;
  window.scrollTo(0, 0);
  const back = () => openCollection(state.section, state.page, true);
  const frame = (body) => main.replaceChildren(button("返回", back, "back"), body);
  frame(status("正在读取剧集…"));
  try {
    const [detail, library] = await Promise.all([
      request(`api/detail/${id}`, { signal: navigation.signal }),
      pendingStop.then(() => request("api/library", { signal: navigation.signal })),
    ]);
    if (navigation.signal.aborted) return;
    state.library = library;
    state.detail = detail;
    frame(renderDetail(detail));
    main.focus({ preventScroll: true });
  } catch (error) {
    if (navigation.signal.aborted) return;
    frame(status(error.message, () => openDetail(id)));
  }
}

function renderDetail(detail) {
  const { series, episodes } = detail;
  const history = state.library.history.find((item) => item.series.id === series.id);
  const available = episodes.filter((episode) => episode.available);
  const savedIndex = available.findIndex((episode) => episode.id === history?.episode);
  const resumeEpisode = savedIndex < 0 ? available[0]
    : history.completed ? available[savedIndex + 1] || available[savedIndex] : available[savedIndex];
  const resumePosition = history && resumeEpisode?.id === history.episode && !history.completed ? history.position : 0;

  const layout = node("div", "detail");
  const copy = node("section", "detail-copy");
  if (series.tags.length) copy.append(node("div", "meta", series.tags.join(" / ")));
  copy.append(node("h1", "", series.title));
  if (series.description) copy.append(node("p", "summary", series.description));
  const actions = node("div", "actions");
  const play = button(resumePosition > 0 ? "继续试看" : resumeEpisode ? `试看第 ${resumeEpisode.number} 集` : "暂无试看", () => {
    startPlayback(resumeEpisode, resumePosition);
  }, "primary");
  play.disabled = !resumeEpisode;
  const official = node("a", "official", "在官网播放");
  official.id = "official-episode";
  official.target = "_blank";
  official.rel = "noopener noreferrer";
  official.href = officialEpisode(series.id, (resumeEpisode || episodes[0]).id);

  let isFavorite = state.library.favorites.some((item) => item.id === series.id);
  const favorite = button(isFavorite ? "已收藏" : "收藏", async () => {
    favorite.disabled = true;
    try {
      await post("api/favorite", { id: series.id, saved: !isFavorite });
      isFavorite = !isFavorite;
      favorite.textContent = isFavorite ? "已收藏" : "收藏";
      favorite.setAttribute("aria-pressed", String(isFavorite));
    } catch (error) {
      note.textContent = error.message;
    } finally {
      favorite.disabled = false;
    }
  });
  favorite.setAttribute("aria-pressed", String(isFavorite));
  actions.append(play, official, favorite);
  const surface = node("div", "player-frame");
  surface.id = "player";
  const image = cover(series, true);
  if (image) surface.append(image);
  const note = node("p", "player-note");
  note.id = "player-note";
  note.setAttribute("role", "status");
  if (history) note.textContent = historyLabel(history);
  copy.append(actions, surface, note);

  const list = node("section", "episodes");
  list.append(node("h2", "", "选集"), node("p", "", `全 ${episodes.length} 集 · 公开试看片段随官网提供`));
  const autoLabel = node("label", "autoplay");
  const auto = node("input");
  auto.type = "checkbox";
  auto.checked = state.autoplay;
  auto.addEventListener("change", () => { state.autoplay = auto.checked; });
  autoLabel.append(auto, document.createTextNode("自动试看下一集"));
  list.append(autoLabel);
  const choices = node("div", "episode-grid");
  for (const episode of episodes) {
    const choice = button(String(episode.number), () => startPlayback(episode));
    choice.dataset.episode = episode.id;
    choice.setAttribute("aria-label", `第 ${episode.number} 集${episode.available ? "" : "（官网查看）"}`);
    choice.setAttribute("aria-pressed", "false");
    if (!episode.available) choice.classList.add("external-episode");
    choices.append(choice);
  }
  list.append(choices);
  layout.append(copy, list);
  return layout;
}

function officialEpisode(series, episode) {
  return `https://hongguoduanju.com/player/${series}/${episode}`;
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
    if (state.playing === session) session.note.textContent = "续播进度暂未保存";
  });
}

function release(token) {
  return request(`api/play/${token}`, { method: "DELETE", keepalive: true,
    signal: AbortSignal.timeout(3500),
  }).catch(() => {});
}

function stopPlayback() {
  state.prepare?.abort();
  state.prepare = null;
  const session = state.playing;
  state.playing = null;
  if (!session) return Promise.resolve();
  savePosition(session, session.video.ended);
  session.video.pause();
  session.video.removeAttribute("src");
  session.video.load();
  return session.queue.then(() => release(session.token));
}

async function startPlayback(episode, position = 0) {
  const current = state.detail;
  if (!current || !episode) return;
  const pendingStop = stopPlayback();
  const controller = new AbortController();
  state.prepare = controller;
  const surface = document.querySelector("#player");
  const note = document.querySelector("#player-note");
  const official = document.querySelector("#official-episode");
  if (!surface || !note) return;
  if (official) official.href = officialEpisode(current.series.id, episode.id);
  for (const choice of main.querySelectorAll("[data-episode]")) {
    choice.setAttribute("aria-pressed", String(choice.dataset.episode === episode.id));
  }
  if (!episode.available) {
    surface.replaceChildren(status("本集可在官网查看"));
    note.textContent = `已选择第 ${episode.number} 集`;
    return;
  }
  surface.replaceChildren(node("span", "loading", "正在打开试看…"));
  note.textContent = `第 ${episode.number} 集`;
  try {
    await pendingStop;
    if (controller.signal.aborted) return;
    // Let the service finish preparing an abandoned request so its server-side token can be released.
    const playback = await post("api/play", { series: current.series.id, episode: episode.id });
    if (controller.signal.aborted) {
      release(playback.token);
      return;
    }
    const video = node("video");
    video.controls = true;
    video.playsInline = true;
    video.preload = "auto";
    video.autoplay = true;
    video.setAttribute("aria-label", `第 ${episode.number} 集视频`);
    const session = { video, token: playback.token, sequence: 0, savedAt: 0, queue: Promise.resolve(), note };
    state.playing = session;
    video.addEventListener("loadedmetadata", () => {
      if (state.playing === session && position > 0 && Number.isFinite(video.duration)) {
        video.currentTime = Math.min(position / 1000, Math.max(0, video.duration - 1));
      }
    }, { once: true });
    video.addEventListener("timeupdate", () => {
      if (state.playing === session && !video.ended && performance.now() - session.savedAt > 4000) savePosition(session);
    });
    for (const event of ["pause", "seeked"]) {
      video.addEventListener(event, () => {
        if (state.playing === session && !video.ended) savePosition(session);
      });
    }
    video.addEventListener("ended", () => {
      if (state.playing !== session) return;
      savePosition(session, true);
      const next = current.episodes[episode.number];
      if (state.autoplay && next?.available) startPlayback(next);
    });
    video.addEventListener("error", () => {
      if (state.playing !== session) return;
      note.textContent = "视频读取失败，可以重新打开本集";
      surface.replaceChildren(status("视频读取失败", () => startPlayback(episode, Math.round(video.currentTime * 1000))));
    });
    surface.replaceChildren(video);
    note.textContent = `第 ${episode.number} 集试看 · ${playback.quality}`;
    video.src = `media/${playback.token}`;
    video.play().catch(() => {});
  } catch (error) {
    if (controller.signal.aborted) return;
    surface.replaceChildren(status(error.message, error.status === 404 ? null : () => startPlayback(episode, position)));
    note.textContent = `第 ${episode.number} 集`;
  }
}

for (const item of nav) {
  item.addEventListener("click", () => {
    queryInput.value = "";
    openCollection(item.dataset.section);
  });
}
search.addEventListener("submit", (event) => {
  event.preventDefault();
  const keyword = queryInput.value.trim();
  if (!keyword) return;
  state.query = keyword;
  openCollection("search");
});
document.addEventListener("keydown", (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    queryInput.focus();
    queryInput.select();
  }
});
window.addEventListener("pagehide", () => {
  if (state.playing) savePosition(state.playing, state.playing.video.ended);
});
openCollection("discover");
