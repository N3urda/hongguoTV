// SPDX-License-Identifier: GPL-3.0-only
export const speedSteps = [75, 100, 125, 150, 175, 200, 300];
export const frameOptions = [["fit", "完整画面"], ["crop", "等比铺满"], ["stretch", "拉伸铺满"]];
export const rotationOptions = [0, 90, 180, 270].map(degrees => [degrees, degrees + "°"]);

export function settingsPage(signal, ui) {
  const { node, button } = ui;
  const layout = node("div", "settings-layout");
  const navigation = node("nav", "settings-nav");
  navigation.setAttribute("aria-label", "设置分类");
  const panel = node("section", "settings-panel");
  const choices = [
    ["updates", "检查更新", updatePanel],
    ["playback", "播放", (pane, scope) => preferencePanel(pane, scope, ui, "playback")],
    ["appearance", "外观与操作", (pane, scope) => preferencePanel(pane, scope, ui, "appearance")],
    ["account", "账户", accountPanel],
    ["backup", "资料备份", backupPanel],
    ["about", "关于应用", aboutPanel],
  ];
  let section;
  const buttons = choices.map(([, label], index) => button(label, () => select(index), "settings-nav-item"));
  function select(index) {
    section?.abort();
    section = new AbortController();
    buttons.forEach((item, indexOfItem) => item.setAttribute("aria-pressed", String(index === indexOfItem)));
    panel.replaceChildren();
    choices[index][2](panel, section.signal, ui);
  }
  signal.addEventListener("abort", () => section?.abort(), { once: true });
  navigation.append(...buttons);
  layout.append(navigation, panel);
  select(Math.max(0, choices.findIndex(([key]) => key === ui.initialTab)));
  return layout;
}

function externalLink(node, label, href) {
  const link = node("a", "action-link", label);
  link.href = href;
  link.target = "_blank";
  link.rel = "noopener noreferrer";
  return link;
}

function updatePanel(panel, signal, ui) {
  const { node, button, request } = ui;
  const version = node("p", "meta settings-version");
  const notice = node("p", "settings-note");
  notice.setAttribute("role", "status");
  const download = node("div");
  const check = button("检查更新", async () => {
    check.disabled = true;
    notice.textContent = "正在检查…";
    download.replaceChildren();
    try {
      const update = await request("api/update", { signal });
      if (signal.aborted) return;
      notice.textContent = update ? "发现桌面版 " + update.version : "当前版本已是最新";
      if (update) download.append(externalLink(node, "查看更新", update.page));
    } catch (error) {
      if (!signal.aborted) notice.textContent = error.message;
    } finally {
      check.disabled = false;
    }
  }, "primary");
  panel.append(node("h2", "", "检查更新"), version, check, notice, download);
  request("api/app", { signal }).then(app => {
    if (!signal.aborted) version.textContent = "当前版本 · " + app.version;
  }).catch(() => {});
}

function aboutPanel(panel, signal, ui) {
  const { node, request } = ui;
  panel.append(node("h2", "", "关于 hongguoTV"), node("p", "meta settings-version", "红果短剧非官方桌面客户端"));
  request("api/app", { signal }).then(app => {
    if (!signal.aborted) {
      panel.append(node("p", "meta settings-version", "版本 " + app.version + " · GPL-3.0"),
        externalLink(node, "项目与开源许可", app.project));
    }
  }).catch(() => {});
}

function accountPanel(panel, signal, ui) {
  const { node, button, request, post } = ui;
  const body = node("div", "account-body");
  const notice = node("p", "settings-note");
  notice.setAttribute("role", "status");
  panel.append(node("h2", "", "红果账号"), body, notice);
  let clock;
  let challenge;
  let operation = 0;
  let remember = true;
  signal.addEventListener("abort", () => clearTimeout(clock), { once: true });

  async function action(run) {
    clearTimeout(clock);
    const ticket = ++operation;
    for (const item of body.querySelectorAll("button, input")) item.disabled = true;
    try {
      const next = await run();
      if (!signal.aborted && ticket === operation) show(next);
    } catch (error) {
      if (signal.aborted || ticket !== operation) return;
      for (const item of body.querySelectorAll("button, input")) item.disabled = false;
      notice.textContent = error.message;
      if (challenge) schedulePoll(challenge, 2600);
    }
  }

  function updateNotice(data) {
    if (data.notice) notice.textContent = data.notice;
    else if (data.challenge) {
      const label = data.challenge.scanned ? "已扫码，请在手机确认登录" : "请使用手机红果 App 扫码";
      notice.textContent = `${label} · 剩余 ${data.challenge.remaining} 秒`;
    } else notice.textContent = "";
  }

  function show(data) {
    if (signal.aborted) return;
    if (data.restoring) {
      body.replaceChildren(node("p", "meta", "正在检查本机登录态…"));
      clock = setTimeout(refresh, 500);
      return;
    }
    const id = data.challenge?.id;
    if (id && id === challenge) {
      for (const item of body.querySelectorAll("button")) item.disabled = false;
      updateNotice(data);
      schedulePoll(id);
      return;
    }
    challenge = id;
    if (data.profile) {
      const identity = node("div", "settings-stack");
      identity.append(node("strong", "account-name", data.profile.nickname),
        node("p", "meta", data.remembered ? "已记住官网登录" : "本次官网登录已生效"));
      const logout = button("退出登录", () => action(() => request("api/account", { method: "DELETE", signal })));
      body.replaceChildren(identity, logout);
    } else if (id) {
      const qr = node("img", "account-qr");
      qr.src = `api/account/qr/${id}`;
      qr.alt = "红果登录二维码";
      qr.width = 248;
      qr.height = 248;
      const actions = node("div", "actions");
      actions.append(button("取消扫码", () => action(() => request("api/account/qr", { method: "DELETE", signal }))));
      body.replaceChildren(qr, actions);
      schedulePoll(id);
    } else {
      const saved = node("label", "settings-toggle");
      const checkbox = node("input");
      checkbox.type = "checkbox";
      checkbox.checked = remember;
      checkbox.addEventListener("change", () => { remember = checkbox.checked; });
      saved.append(checkbox, document.createTextNode("记住登录"));
      const start = button("红果 App 扫码登录", () => action(() => post("api/account/qr", { remember }, { signal })), "primary");
      body.replaceChildren(start, saved);
    }
    updateNotice(data);
  }

  function schedulePoll(id, delay = 2000) {
    clearTimeout(clock);
    const ticket = operation;
    clock = setTimeout(async () => {
      if (signal.aborted || ticket !== operation) return;
      try {
        const next = await request(`api/account/qr/${id}`, { method: "POST", signal });
        if (!signal.aborted && ticket === operation) show(next);
      } catch (error) {
        if (signal.aborted || ticket !== operation) return;
        notice.textContent = error.message;
        schedulePoll(id, 3800);
      }
    }, delay);
  }

  async function refresh() {
    try {
      show(await request("api/account", { signal }));
    } catch {
      if (!signal.aborted) body.replaceChildren(button("重新读取账号", refresh));
    }
  }
  refresh();
}

function preferencePanel(panel, signal, ui, section) {
  const { node, preferences, savePreference } = ui;
  const appearance = section === "appearance";
  panel.append(node("h2", "", appearance ? "外观与操作" : "播放设置"));
  const rows = node("div", "settings-rows");
  const notice = node("p", "settings-note");
  notice.setAttribute("role", "status");
  panel.append(rows, notice);
  let saving = false;

  async function change(key, value) {
    if (saving || signal.aborted) return;
    saving = true;
    const controls = [...rows.querySelectorAll("input, select")];
    for (const control of controls) control.disabled = true;
    try {
      await savePreference(key, value);
      if (!signal.aborted) notice.textContent = "已保存";
    } catch (error) {
      if (!signal.aborted) notice.textContent = error.message;
    } finally {
      saving = false;
      for (const control of controls) {
        const value = preferences()[control.name];
        if (control.type === "checkbox") control.checked = value;
        else control.value = String(value);
        control.disabled = false;
      }
    }
  }

  function row(key, label, options) {
    const line = node("label", "settings-row");
    const control = node(options ? "select" : "input");
    const value = preferences()[key];
    control.name = key;
    if (options) {
      for (const [id, title] of options) {
        const option = node("option", "", title);
        option.value = String(id);
        control.append(option);
      }
      control.value = String(value);
      control.addEventListener("change", () => change(key, typeof value === "number" ? Number(control.value) : control.value));
    } else {
      control.type = "checkbox";
      control.checked = value;
      control.addEventListener("change", () => change(key, control.checked));
    }
    line.append(node("span", "", label), control);
    rows.append(line);
  }

  if (appearance) {
    row("theme", "界面主题", [["dark", "经典黑"], ["light", "纯净白"], ["sand", "暖米色"], ["forest", "森林绿"], ["ocean", "深海蓝"]]);
    row("startup", "启动页面", [["home", "首页"], ["browse", "找剧"], ["rank", "排行榜"], ["favorites", "收藏"],
      ["history", "观看历史"], ["settings", "设置"], ["search", "搜索"], ["feed", "随心看"]]);
    row("feed_autoplay", "随心看自动播放");
    row("clock", "播放时钟", [["off", "关闭"], ["controls", "操作时显示"], ["always", "始终显示"]]);
  } else {
    row("quality", "默认清晰度", [480, 720, 1080].map(quality => [quality, quality + "P"]));
    rows.append(node("p", "meta quality-hint", "播放器根据官网为该分集提供的档位选流。"));
    row("speed", "默认播放倍速", speedSteps.map(rate => [rate, `${rate / 100}×`]));
    row("frame", "画面模式", frameOptions);
    row("rotation", "画面旋转", rotationOptions);
    row("show_progress", "常驻播放进度");
    row("auto_next", "自动连播");
  }
}

function backupPanel(panel, signal, ui) {
  const { node, button, request, applyPreferences } = ui;
  const download = node("a", "action-link", "导出备份");
  download.href = "api/library/backup";
  download.download = "hongguotv-library.json";
  const filePicker = node("input");
  filePicker.type = "file";
  filePicker.accept = ".json,application/json";
  filePicker.hidden = true;
  const upload = button("导入并合并备份", () => filePicker.click(), "primary");
  const restore = node("label", "settings-toggle backup-options");
  const restoreSettings = node("input");
  restoreSettings.type = "checkbox";
  restore.append(restoreSettings, document.createTextNode("同时恢复电视端的播放设置"));
  const notice = node("p", "settings-note");
  notice.setAttribute("role", "status");
  filePicker.addEventListener("change", async () => {
    const file = filePicker.files[0];
    filePicker.value = "";
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      notice.textContent = "备份超过 2 MB 限制";
      return;
    }
    upload.disabled = true;
    notice.textContent = "正在合并备份…";
    try {
      const query = restoreSettings.checked ? "?restore_settings=true" : "";
      const counts = await request("api/library/backup" + query, { method: "POST", body: file, signal });
      if (counts.preferences) applyPreferences(counts.preferences);
      notice.textContent = `备份已处理 · ${counts.favorites} 部收藏 · ${counts.history} 条历史 · ${counts.later} 部稍后看`;
    } catch (error) {
      if (!signal.aborted) notice.textContent = error.message;
    } finally {
      upload.disabled = false;
    }
  });
  const actions = node("div", "actions");
  actions.append(upload, download);
  panel.append(node("h2", "", "资料备份"),
    node("p", "meta backup-description", "电视端和桌面端可通过备份文件迁移本机收藏、观看记录和稍后看。"),
    actions, restore, filePicker, notice);
}
