import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  BackHandler,
  FlatList,
  Image,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  useWindowDimensions,
  View,
} from "react-native";
import { BridgeProvider } from "./src/data/provider";
import { loadState, saveLibrary, saveSettings } from "./src/data/storage";
import {
  Detail,
  Library,
  Progress,
  Series,
  Settings,
  mergePage,
  normalizeBaseUrl,
  resumeTarget,
} from "./src/domain/model";
import { Button, Card, Notice, palette } from "./src/ui/components";
import { Player } from "./src/ui/Player";
type Tab = "推荐" | "搜索" | "收藏" | "最近观看" | "设置";
const tabs: Tab[] = ["推荐", "搜索", "收藏", "最近观看", "设置"];
const sample: Detail = {
  id: "test",
  title: "播放器测试 · Big Buck Bunny",
  cover: "",
  description: "",
  badge: "",
  tags: "",
  episodes: [{ id: "test", number: 1, title: "测试片" }],
};
export default function App() {
  const { width } = useWindowDimensions();
  const [ready, setReady] = useState(false);
  const [settings, setSettings] = useState<Settings>({
    baseUrl: "",
    token: "",
  });
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [library, setLibrary] = useState<Library>({
    favorites: [],
    progress: {},
  });
  const libraryRef = useRef(library);
  const [tab, setTab] = useState<Tab>("推荐");
  const [items, setItems] = useState<Series[]>([]);
  const [detail, setDetail] = useState<Detail>();
  const [detailId, setDetailId] = useState("");
  const [play, setPlay] = useState<{
    detail: Detail;
    index: number;
    position: number;
    test?: boolean;
  }>();
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [message, setMessage] = useState("");
  const [connecting, setConnecting] = useState(false);
  const [editing, setEditing] = useState<string>();
  const request = useRef(0);
  const provider = useMemo(() => new BridgeProvider(settings), [settings]);
  useEffect(() => {
    loadState()
      .then((data) => {
        setSettings(data.settings);
        setUrl(data.settings.baseUrl);
        setToken(data.settings.token);
        setLibrary(data.library);
        libraryRef.current = data.library;
        if (!data.settings.baseUrl) setTab("设置");
      })
      .catch(() => {
        setMessage("本地数据读取失败，请重新设置服务地址");
        setTab("设置");
      })
      .finally(() => setReady(true));
  }, []);
  const updateLibrary = useCallback((update: (old: Library) => Library) => {
    const next = update(libraryRef.current);
    libraryRef.current = next;
    setLibrary(next);
    saveLibrary(next).catch(() =>
      setMessage("观看记录保存失败，请检查电视存储空间")
    );
  }, []);
  const record = useCallback(
    (p: Progress) =>
      updateLibrary((old) => ({
        ...old,
        progress: Object.fromEntries(
          Object.entries({ ...old.progress, [p.series.id]: p })
            .sort((a, b) => b[1].updatedAt - a[1].updatedAt)
            .slice(0, 200)
        ),
      })),
    [updateLibrary]
  );
  useEffect(() => {
    if (
      !ready ||
      !settings.baseUrl ||
      !["推荐", "搜索"].includes(tab) ||
      detailId ||
      play
    )
      return;
    if (tab === "搜索" && !submitted) {
      setItems([]);
      setBusy(false);
      setError("");
      return;
    }
    const id = ++request.current;
    setBusy(true);
    setError("");
    const promise =
      tab === "搜索" ? provider.search(submitted, page) : provider.home(page);
    promise
      .then((rows) => {
        if (id === request.current) {
          setItems((old) => (page === 1 ? rows : mergePage(old, rows)));
          setHasMore(rows.length > 0);
        }
      })
      .catch((e) => {
        if (id === request.current) setError(e.message);
      })
      .finally(() => {
        if (id === request.current) setBusy(false);
      });
    return () => {
      request.current++;
    };
  }, [
    ready,
    settings.baseUrl,
    tab,
    submitted,
    page,
    provider,
    refresh,
    detailId,
    play,
  ]);
  useEffect(() => {
    if (!detailId) return;
    let live = true;
    setDetail(undefined);
    setBusy(true);
    setError("");
    provider
      .detail(detailId)
      .then((value) => {
        if (live) setDetail(value);
      })
      .catch((e) => {
        if (live) setError(e.message);
      })
      .finally(() => {
        if (live) setBusy(false);
      });
    return () => {
      live = false;
    };
  }, [detailId, provider, refresh]);
  useEffect(() => {
    if (play) return;
    const listener = BackHandler.addEventListener("hardwareBackPress", () => {
      if (detailId) {
        setDetailId("");
        setDetail(undefined);
        setError("");
        return true;
      }
      if (tab !== "推荐" && settings.baseUrl) {
        changeTab("推荐");
        return true;
      }
      return false;
    });
    return () => listener.remove();
  }, [detailId, tab, settings.baseUrl, play]);
  function changeTab(next: Tab) {
    if (next === tab && !detailId) return;
    request.current++;
    setTab(next);
    setDetailId("");
    setDetail(undefined);
    setError("");
    setItems([]);
    setPage(1);
    setHasMore(true);
    setBusy(false);
  }
  function open(item: Series) {
    request.current++;
    setError("");
    setDetailId(item.id);
  }
  const exitPlayer = useCallback(() => setPlay(undefined), []);
  async function connect() {
    setConnecting(true);
    setMessage("");
    try {
      const next = { baseUrl: normalizeBaseUrl(url), token: token.trim() };
      await new BridgeProvider(next).health();
      await saveSettings(next);
      setSettings(next);
      setUrl(next.baseUrl);
      setMessage("服务连接成功");
      changeTab("推荐");
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "连接失败");
    } finally {
      setConnecting(false);
    }
  }
  function favorite(series: Series) {
    updateLibrary((old) => ({
      ...old,
      favorites: old.favorites.some((i) => i.id === series.id)
        ? old.favorites.filter((i) => i.id !== series.id)
        : [series, ...old.favorites],
    }));
  }
  if (!ready)
    return (
      <View style={s.root}>
        <Notice message="正在加载…" busy />
      </View>
    );
  if (play)
    return (
      <View style={s.root}>
        <StatusBar hidden />
        <Player
          key={play.detail.id + ":" + play.index + ":" + play.position}
          detail={play.detail}
          initialIndex={play.index}
          initialPosition={play.position}
          provider={provider}
          onExit={exitPlayer}
          onProgress={record}
          test={play.test}
        />
      </View>
    );
  const cols = width >= 850 ? 5 : width >= 700 ? 4 : width >= 500 ? 3 : 2;
  const cardWidth = (width - 72) / cols - 14;
  const shown =
    tab === "收藏"
      ? library.favorites
      : tab === "最近观看"
      ? Object.values(library.progress)
          .sort((a, b) => b.updatedAt - a.updatedAt)
          .map((p) => ({
            ...p.series,
            badge: `第 ${p.episodeNumber} 集 · ${
              p.completed ? "已看完" : Math.floor(p.position / 60) + " 分钟"
            }`,
          }))
      : items;
  return (
    <View style={s.root}>
      <StatusBar hidden />
      <View style={s.header}>
        <View style={s.logo}>
          <Text style={s.logoText}>红</Text>
        </View>
        <Text style={s.brand}>红果 TV</Text>
        <Text style={s.tagline}>好故事，慢慢看。</Text>
        <View style={{ flex: 1 }} />
        <Text style={s.version}>个人影院 · 0.1</Text>
      </View>
      {!detailId && (
        <View style={s.nav}>
          {tabs.map((t) => (
            <Button
              key={t}
              label={t}
              primary={tab === t}
              preferred={t === tab}
              onPress={() => changeTab(t)}
            />
          ))}
        </View>
      )}
      {detailId ? (
        <>
          <View style={s.detailNav}>
            <Button
              label="‹ 返回"
              onPress={() => {
                setDetailId("");
                setError("");
              }}
              preferred
            />
            <Text style={s.muted}>剧集详情</Text>
          </View>
          {busy ? (
            <Notice message="正在加载剧集…" busy />
          ) : error ? (
            <Notice message={error} retry={() => setRefresh((n) => n + 1)} />
          ) : (
            detail && (
              <ScrollView contentContainerStyle={s.detailContent}>
                <View style={s.hero}>
                  <Image source={{ uri: detail.cover }} style={s.heroCover} />
                  <View style={{ width: Math.max(120, width - 250), gap: 14 }}>
                    <Text numberOfLines={2} style={s.heroTitle}>
                      {detail.title}
                    </Text>
                    <Text style={s.muted}>
                      {detail.badge} · {detail.tags}
                    </Text>
                    <Text style={s.description} numberOfLines={4}>
                      {detail.description}
                    </Text>
                    <View style={s.row}>
                      <Button
                        label={
                          library.progress[detail.id] ? "继续观看" : "开始播放"
                        }
                        primary
                        onPress={() => {
                          const target = resumeTarget(
                            detail,
                            library.progress[detail.id]
                          );
                          setPlay({ detail, ...target });
                        }}
                      />
                      <Button
                        label={
                          library.favorites.some((i) => i.id === detail.id)
                            ? "已收藏 ✓"
                            : "＋ 收藏"
                        }
                        onPress={() => {
                          const { episodes: _, ...series } = detail;
                          favorite(series);
                        }}
                      />
                    </View>
                  </View>
                </View>
                <Text style={s.section}>
                  选集 · {detail.episodes.length} 集
                </Text>
                <View style={s.episodes}>
                  {detail.episodes.map((episode, index) => (
                    <Button
                      key={episode.id}
                      label={String(episode.number)}
                      style={{ width: 72 }}
                      primary={
                        library.progress[detail.id]?.episodeId === episode.id
                      }
                      onPress={() => setPlay({ detail, index, position: 0 })}
                    />
                  ))}
                </View>
              </ScrollView>
            )
          )}
        </>
      ) : tab === "设置" ? (
        <ScrollView
          contentContainerStyle={s.settings}
          keyboardShouldPersistTaps="handled"
        >
          <Text style={s.heroTitle}>连接你的内容服务</Text>
          <Text style={s.description}>
            电视和运行服务的电脑 / NAS 连接同一网络，填入服务启动后显示的地址。
          </Text>
          <Text style={s.label}>服务地址</Text>
          <TextInput
            accessibilityLabel="服务地址"
            onFocus={() => setEditing("url")}
            onBlur={() => setEditing(undefined)}
            onSubmitEditing={connect}
            returnKeyType="done"
            value={url}
            onChangeText={setUrl}
            placeholder="http://192.168.1.10:8787"
            placeholderTextColor="#747383"
            autoCapitalize="none"
            autoCorrect={false}
            style={[
              s.input,
              editing === "url" && { borderColor: palette.accent },
            ]}
          />
          <Text style={s.label}>访问口令（服务未设置时留空）</Text>
          <TextInput
            accessibilityLabel="访问口令"
            onFocus={() => setEditing("token")}
            onBlur={() => setEditing(undefined)}
            onSubmitEditing={connect}
            returnKeyType="done"
            value={token}
            onChangeText={setToken}
            secureTextEntry
            autoCapitalize="none"
            autoCorrect={false}
            style={[
              s.input,
              editing === "token" && { borderColor: palette.accent },
            ]}
          />
          <View style={s.row}>
            <Button
              label={connecting ? "正在连接…" : "连接并保存"}
              disabled={connecting}
              primary
              onPress={connect}
            />
            <Button
              label="测试播放器"
              onPress={() =>
                setPlay({ detail: sample, index: 0, position: 0, test: true })
              }
            />
          </View>
          {!!message && (
            <Text accessibilityLiveRegion="polite" style={s.feedback}>
              {message}
            </Text>
          )}
          <Text style={s.note}>
            测试播放器使用 Blender 的 Big Buck Bunny
            公开样片。此测试不代表红果内容可用。{"\n"}
            收藏和观看记录仅保存在当前设备。{"\n"}本应用为个人非官方客户端。
          </Text>
        </ScrollView>
      ) : (
        <>
          {tab === "搜索" ? (
            <View style={s.search}>
              <TextInput
                accessibilityLabel="搜索短剧"
                onFocus={() => setEditing("search")}
                onBlur={() => setEditing(undefined)}
                placeholder="输入剧名或关键词"
                placeholderTextColor="#747383"
                value={query}
                onChangeText={setQuery}
                onSubmitEditing={() => {
                  setSubmitted(query.trim());
                  setPage(1);
                  setRefresh((n) => n + 1);
                }}
                style={[
                  s.input,
                  { flex: 1 },
                  editing === "search" && { borderColor: palette.accent },
                ]}
                returnKeyType="search"
              />
              <Button
                label="搜索"
                primary
                onPress={() => {
                  setSubmitted(query.trim());
                  setPage(1);
                  setItems([]);
                  setRefresh((n) => n + 1);
                }}
              />
            </View>
          ) : (
            <View style={s.heading}>
              <Text style={s.section}>{tab === "推荐" ? "今日热播" : tab}</Text>
              <Text style={s.muted}>
                {tab === "推荐"
                  ? "挑一部，开始今晚的故事。"
                  : `${shown.length} 部短剧`}
              </Text>
            </View>
          )}
          {!settings.baseUrl ? (
            <Notice message="请先在设置中连接内容服务" />
          ) : error ? (
            <Notice message={error} retry={() => setRefresh((n) => n + 1)} />
          ) : busy && !shown.length ? (
            <Notice message="正在寻找好故事…" busy />
          ) : (
            <FlatList
              key={cols + tab}
              data={shown}
              numColumns={cols}
              keyExtractor={(item) => item.id}
              contentContainerStyle={s.grid}
              initialNumToRender={cols * 2}
              windowSize={5}
              renderItem={({ item }) => (
                <Card
                  item={item}
                  width={cardWidth}
                  onPress={() => open(item)}
                />
              )}
              ListEmptyComponent={
                <Notice
                  message={
                    tab === "搜索"
                      ? submitted
                        ? "没有找到相关短剧，换个关键词试试"
                        : "输入关键词，发现下一部好剧"
                      : tab === "收藏"
                      ? "收藏喜欢的短剧，下次接着看"
                      : tab === "最近观看"
                      ? "开始播放后，这里会留下观看记录"
                      : "暂无内容"
                  }
                />
              }
              ListFooterComponent={
                ["推荐", "搜索"].includes(tab) && shown.length > 0 ? (
                  <View style={{ padding: 20, alignItems: "center" }}>
                    <Button
                      label={
                        busy ? "正在加载…" : hasMore ? "加载更多" : "已经到底了"
                      }
                      disabled={busy || !hasMore}
                      onPress={() => setPage((n) => n + 1)}
                    />
                  </View>
                ) : null
              }
            />
          )}
        </>
      )}
    </View>
  );
}
const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: palette.bg },
  header: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 32,
    paddingTop: 20,
    paddingBottom: 16,
    gap: 12,
  },
  logo: {
    width: 38,
    height: 38,
    borderRadius: 12,
    backgroundColor: palette.accent,
    alignItems: "center",
    justifyContent: "center",
  },
  logoText: { color: "#fff", fontWeight: "900", fontSize: 24 },
  brand: { color: palette.text, fontSize: 25, fontWeight: "800" },
  tagline: { color: palette.muted, fontSize: 14, marginLeft: 12 },
  version: { color: "#777687", fontSize: 12 },
  nav: {
    flexDirection: "row",
    paddingHorizontal: 32,
    gap: 10,
    paddingBottom: 12,
  },
  muted: { color: palette.muted, fontSize: 14 },
  heading: {
    paddingHorizontal: 38,
    paddingVertical: 14,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  section: {
    color: palette.text,
    fontSize: 23,
    fontWeight: "700",
    marginBottom: 12,
  },
  grid: { paddingHorizontal: 29, paddingBottom: 32, flexGrow: 1 },
  row: { flexDirection: "row", gap: 12, flexWrap: "wrap" },
  input: {
    color: palette.text,
    fontSize: 18,
    borderWidth: 2,
    borderColor: palette.border,
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: palette.panel,
    minHeight: 50,
  },
  search: {
    paddingHorizontal: 36,
    paddingVertical: 16,
    flexDirection: "row",
    gap: 12,
  },
  settings: {
    paddingHorizontal: 40,
    paddingVertical: 24,
    gap: 16,
    maxWidth: 900,
    width: "100%",
  },
  heroTitle: { color: palette.text, fontSize: 32, fontWeight: "700" },
  description: { color: "#C4C2CF", fontSize: 17, lineHeight: 28 },
  label: { color: palette.text, fontSize: 16, marginTop: 10 },
  note: { color: "#858494", fontSize: 13, lineHeight: 24, marginTop: 10 },
  feedback: { color: "#FFB9A8", fontSize: 16, lineHeight: 26 },
  detailNav: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 32,
    gap: 20,
    paddingBottom: 16,
  },
  detailContent: { paddingHorizontal: 36, paddingBottom: 40 },
  hero: { flexDirection: "row", gap: 28, marginBottom: 28 },
  heroCover: {
    width: 150,
    height: 200,
    borderRadius: 12,
    backgroundColor: palette.panel,
  },
  episodes: { flexDirection: "row", flexWrap: "wrap", gap: 12 },
});
