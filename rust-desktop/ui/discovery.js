// SPDX-License-Identifier: GPL-3.0-only
export function discoveryState(filters = {}) {
  return { filters, groups: [], items: [], next: null, loaded: false, expanded: true, openGroup: null };
}

export async function discoveryPage(outlet, state, signal, ui) {
  const { node, button, post, card, grid, status, scroller } = ui;
  const layout = node("section", "discovery");
  const toolbar = node("div", "discovery-toolbar");
  const types = node("div", "discovery-types");
  const actions = node("div", "discovery-actions");
  const groups = node("div", "discovery-groups");
  groups.id = "discovery-filters";
  const summary = node("div", "discovery-summary");
  const message = node("p", "meta");
  message.setAttribute("role", "status");
  const results = node("div", "discovery-results");
  const paging = node("div", "discovery-paging");
  let pending;
  let pageFailed = false;
  let started = false;
  const stripSizes = new ResizeObserver(entries => requestAnimationFrame(() => {
    if (signal.aborted) return;
    for (const { target: strip } of entries) {
      const moreOptions = strip.nextElementSibling;
      if (!moreOptions || !strip.isConnected || !strip.clientWidth) continue;
      const gap = parseFloat(getComputedStyle(strip.parentElement).columnGap) || 0;
      const available = strip.clientWidth + (moreOptions.hidden ? 0 : moreOptions.offsetWidth + gap);
      moreOptions.hidden = !strip.classList.contains("expanded") && strip.scrollWidth <= available + 1;
    }
  }));

  const toggle = button("", () => {
    state.expanded = !state.expanded;
    syncPanel();
  }, "quiet");
  toggle.setAttribute("aria-controls", groups.id);
  const reset = button("重置", () => choose({}), "quiet");
  const refresh = button("刷新", () => load(), "quiet");
  const more = button("加载更多", () => load(state.next), "quiet");
  paging.append(more);
  actions.append(toggle, reset, refresh);
  toolbar.append(types, actions);
  layout.append(toolbar, groups, summary, message, results, paging);
  outlet.replaceChildren(layout);

  function syncPanel() {
    const available = state.groups.some(group => group.type !== "genre" && group.options.length);
    toggle.hidden = !available;
    groups.hidden = !available || !state.expanded;
    toggle.textContent = state.expanded ? "收起筛选" : "展开筛选";
    toggle.setAttribute("aria-expanded", String(state.expanded));
  }

  function reveal(list, item) {
    if (!item || !list.clientWidth || list.scrollWidth <= list.clientWidth) return;
    const track = list.getBoundingClientRect();
    const selected = item.getBoundingClientRect();
    if (selected.left < track.left) list.scrollLeft += selected.left - track.left - 4;
    else if (selected.right > track.right) list.scrollLeft += selected.right - track.right + 4;
  }

  function renderFilters(focus = document.activeElement?.dataset.key) {
    const trackOffsets = new Map([...layout.querySelectorAll("[data-shelf]")].map(list => [list.dataset.shelf, list.scrollLeft]));
    stripSizes.disconnect();
    types.replaceChildren();
    groups.replaceChildren();
    for (const group of state.groups) {
      const genre = group.type === "genre";
      if (!genre && !group.options.length) continue;
      const row = node("div", "discovery-row");
      if (!genre) row.append(node("span", "discovery-label", group.label));
      const strip = node("div", "discovery-choices");
      strip.dataset.shelf = `filter:${group.type}`;
      strip.setAttribute("role", "group");
      strip.setAttribute("aria-label", group.label);
      const current = state.filters[group.type] ?? "";
      const options = [{id:"", label:genre ? "全部类型" : "全部"}, ...group.options.filter(option => option.id !== "")];
      const expanded = state.openGroup === group.type;
      strip.classList.toggle("expanded", expanded);
      let selected;
      for (const { id, label } of options) {
        const chip = button(label, () => {
          if ((state.filters[group.type] ?? "") === id) return;
          const filters = genre ? {} : { ...state.filters };
          if (id) filters[group.type] = id;
          else delete filters[group.type];
          if (state.openGroup === group.type) state.openGroup = null;
          choose(filters, chip.dataset.key, genre);
        }, "discovery-chip");
        chip.dataset.key = `filter:${group.type}:${id}`;
        chip.setAttribute("aria-pressed", String(current === id));
        if (current === id) selected = chip;
        strip.append(chip);
      }
      row.append(strip);
      if (!genre) {
        const expand = button("", () => {
          state.openGroup = expanded ? null : group.type;
          renderFilters(expand.dataset.key);
        }, "quiet discovery-more");
        expand.dataset.key = `filter-expand:${group.type}`;
        expand.hidden = !expanded && focus !== expand.dataset.key;
        const label = `${expanded ? "收起" : "展开"}${group.label}选项`;
        expand.setAttribute("aria-label", label);
        expand.title = label;
        expand.setAttribute("aria-expanded", String(expanded));
        row.append(expand);
        stripSizes.observe(strip);
      }
      (genre ? types : groups).append(row);
      strip.scrollLeft = trackOffsets.get(strip.dataset.shelf) || 0;
      requestAnimationFrame(() => {
        if (strip.isConnected && !signal.aborted) reveal(strip, selected);
      });
    }
    reset.disabled = Object.keys(state.filters).length === 0;
    syncPanel();
    summary.replaceChildren();
    for (const [type, id] of Object.entries(state.filters)) {
      const group = state.groups.find(row => row.type === type);
      const match = group?.options.find(option => option.id === id);
      if (match) summary.append(node("span", "discovery-summary-item", `${group.label} ${match.label}`));
    }
    summary.hidden = !summary.childElementCount;
    const remembered = [...layout.querySelectorAll("[data-key]")].find(item => item.dataset.key === focus);
    remembered?.focus({ preventScroll: true });
  }

  function showItems() {
    if (!state.loaded) return;
    const cards = state.items.map(series => card(series, "browse"));
    results.replaceChildren(cards.length ? grid(cards) : status("没有符合当前条件的剧集"));
    syncPaging();
  }

  function syncPaging() {
    paging.hidden = !state.next;
    more.disabled = Boolean(pending);
    more.textContent = pending ? "加载中…" : pageFailed ? "重试加载更多" : "加载更多";
  }

  function choose(filters, focus, resetGroup = true) {
    state.filters = filters;
    state.items = [];
    state.next = null;
    state.loaded = false;
    state.expanded = true;
    if (resetGroup) {
      state.groups = state.groups.filter(group => group.type === "genre");
      state.openGroup = null;
    }
    pageFailed = false;
    renderFilters(focus);
    load();
  }

  async function load(cursor = null) {
    if (signal.aborted || cursor && pending) return;
    pending?.abort();
    const controller = new AbortController();
    pending = controller;
    const filters = { ...state.filters };
    message.textContent = cursor ? "正在加载更多…" : "正在按当前条件加载…";
    refresh.disabled = true;
    syncPaging();
    if (!cursor && !state.loaded) results.replaceChildren();

    try {
      const page = await post("api/discovery", { filters, cursor }, { signal: controller.signal });
      if (signal.aborted || pending !== controller) return;
      if (cursor) {
        const seen = new Set(state.items.map(series => series.id));
        const added = page.items.filter(series => !seen.has(series.id) && seen.add(series.id));
        state.items.push(...added);
        const visibleGrid = results.querySelector(".grid");
        if (visibleGrid) visibleGrid.append(...added.map(series => card(series, "browse")));
        else showItems();
      } else {
        state.items = page.items;
        state.groups = page.groups;
        state.loaded = true;
        renderFilters();
        showItems();
      }
      state.next = page.next;
      pageFailed = false;
      message.textContent = "";
      if (cursor && !state.next && document.activeElement === more) results.querySelector(".card:last-child")?.focus({ preventScroll: true });
    } catch (error) {
      if (signal.aborted || pending !== controller) return;
      if (state.loaded) {
        if (cursor) pageFailed = true;
        message.textContent = error.message;
      } else {
        message.textContent = "";
        results.replaceChildren(status(error.message, () => load()));
      }
    } finally {
      if (!signal.aborted && pending === controller) {
        pending = null;
        refresh.disabled = false;
        syncPaging();
      }
    }
  }

  scroller.addEventListener("scroll", () => {
    if (!started || !state.next || pending || pageFailed || !state.items.length) return;
    if (scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 200) load(state.next);
  }, { signal, passive: true });
  signal.addEventListener("abort", () => {
    pending?.abort();
    stripSizes.disconnect();
  }, { once: true });
  renderFilters();
  if (state.loaded) showItems();
  else await load();
  requestAnimationFrame(() => { started = true; });
}
