import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  AppState,
  BackHandler,
  StyleSheet,
  Text,
  View,
  useTVEventHandler,
} from "react-native";
import Video, { VideoRef } from "react-native-video";
import { ContentProvider, Detail, Playback, Progress } from "../domain/model";
import { Button, Notice, palette } from "./components";
const clock = (n: number) =>
  `${Math.floor(n / 60)}:${String(Math.floor(n % 60)).padStart(2, "0")}`;
export function Player({
  detail,
  initialIndex,
  initialPosition,
  provider,
  onExit,
  onProgress,
  test = false,
}: {
  detail: Detail;
  initialIndex: number;
  initialPosition: number;
  provider: ContentProvider;
  onExit: () => void;
  onProgress: (p: Progress) => void;
  test?: boolean;
}) {
  const [index, setIndex] = useState(initialIndex);
  const [source, setSource] = useState<Playback>();
  const [paused, setPaused] = useState(false);
  const [buffering, setBuffering] = useState(false);
  const [error, setError] = useState("");
  const [position, setPosition] = useState(initialPosition);
  const [duration, setDuration] = useState(0);
  const [retry, setRetry] = useState(0);
  const [finished, setFinished] = useState(false);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [interaction, setInteraction] = useState(0);
  const showControls = controlsVisible || paused || !!error || !source;
  const video = useRef<VideoRef>(null);
  const progress = useRef({
    position: initialPosition,
    duration: 0,
    completed: false,
  });
  const seekStart = useRef(initialPosition);
  const retried = useRef(false);
  const lastSaved = useRef(0);
  const onProgressRef = useRef(onProgress);
  onProgressRef.current = onProgress;
  const episode = detail.episodes[index];
  const save = useCallback(() => {
    if (test) return;
    const { episodes: _, ...series } = detail;
    onProgressRef.current({
      series,
      episodeId: episode.id,
      episodeNumber: episode.number,
      ...progress.current,
      updatedAt: Date.now(),
    });
  }, [detail, episode, test]);
  useEffect(() => {
    let live = true;
    setSource(undefined);
    setError("");
    setBuffering(false);
    const request = test
      ? Promise.resolve({
          uri: "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
          headers: {},
        })
      : provider.resolve(episode.id, retry > 0);
    request
      .then((value) => {
        if (live) setSource(value);
      })
      .catch((e) => {
        if (live) setError(e.message);
      });
    return () => {
      live = false;
    };
  }, [episode.id, provider, retry, test]);
  useEffect(() => () => save(), [save]);
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (state) => {
      if (state !== "active") {
        setPaused(true);
        save();
      }
    });
    const back = BackHandler.addEventListener("hardwareBackPress", () => {
      save();
      onExit();
      return true;
    });
    return () => {
      subscription.remove();
      back.remove();
    };
  }, [save, onExit]);
  useTVEventHandler((event) => {
    if (
      event.eventKeyAction === 0 ||
      ["focus", "blur"].includes(event.eventType)
    )
      return;
    setControlsVisible(true);
    setInteraction((n) => n + 1);
    if (event.eventType === "playPause") setPaused((value) => !value);
  });
  useEffect(() => {
    if (!source || paused || error || buffering) return;
    const timer = setTimeout(() => setControlsVisible(false), 5000);
    return () => clearTimeout(timer);
  }, [source, paused, error, buffering, interaction]);
  function switchEpisode(next: number) {
    if (next < 0 || next >= detail.episodes.length) return;
    save();
    setSource(undefined);
    seekStart.current = 0;
    retried.current = false;
    lastSaved.current = 0;
    setFinished(false);
    setPaused(false);
    setRetry(0);
    setIndex(next);
  }
  useEffect(() => {
    progress.current = {
      position: seekStart.current,
      duration: 0,
      completed: false,
    };
    setPosition(seekStart.current);
    setDuration(0);
  }, [index]);
  function refresh() {
    seekStart.current = progress.current.position;
    setFinished(false);
    setPaused(false);
    setRetry((n) => n + 1);
  }
  function seek(delta: number) {
    const value = Math.max(
      0,
      Math.min(duration || Infinity, progress.current.position + delta)
    );
    video.current?.seek(value);
    progress.current.position = value;
    setPosition(value);
  }
  return (
    <View
      style={s.screen}
      focusable={!showControls}
      hasTVPreferredFocus={!showControls}
      onTouchStart={() => {
        setControlsVisible(true);
        setInteraction((n) => n + 1);
      }}
    >
      {source && !error && (
        <Video
          key={`${episode.id}:${retry}`}
          ref={video}
          source={source}
          style={StyleSheet.absoluteFill}
          resizeMode="contain"
          paused={paused}
          controls={false}
          playInBackground={false}
          playWhenInactive={false}
          progressUpdateInterval={1000}
          onLoad={(data) => {
            setDuration(data.duration);
            progress.current.duration = data.duration;
            if (seekStart.current > 0)
              video.current?.seek(
                Math.min(seekStart.current, Math.max(0, data.duration - 1))
              );
          }}
          onBuffer={(data) => setBuffering(data.isBuffering)}
          onProgress={(data) => {
            progress.current.position = data.currentTime;
            setPosition(data.currentTime);
            if (Date.now() - lastSaved.current > 5000) {
              lastSaved.current = Date.now();
              save();
            }
          }}
          onEnd={() => {
            progress.current.completed = true;
            save();
            if (index + 1 < detail.episodes.length) switchEpisode(index + 1);
            else {
              setFinished(true);
              setPaused(true);
            }
          }}
          onError={() => {
            if (!retried.current && !test) {
              retried.current = true;
              refresh();
            } else setError("视频暂时无法播放，请重试或返回选择其他分集");
          }}
        />
      )}
      {showControls && (
        <View style={s.top}>
          <Button
            label="‹ 返回选集"
            onPress={() => {
              save();
              onExit();
            }}
          />
          <View style={{ flex: 1 }}>
            <Text style={s.title} numberOfLines={1}>
              {detail.title}
            </Text>
            <Text style={s.subtitle}>
              {episode.title}
              {test ? " · 公开测试片" : ` / 共 ${detail.episodes.length} 集`}
            </Text>
          </View>
          <Text style={s.brand}>红果 TV</Text>
        </View>
      )}
      {error ? (
        <Notice message={error} retry={refresh} />
      ) : !source ? (
        <Notice message="正在准备播放…" busy />
      ) : (
        <View
          style={{ flex: 1, justifyContent: "center", alignItems: "center" }}
        >
          {buffering && <Text style={s.buffer}>正在缓冲…</Text>}
          {finished && <Text style={s.buffer}>本剧已播放完</Text>}
        </View>
      )}
      {showControls && (
        <View style={s.bottom}>
          <View style={s.track}>
            <View
              style={[
                s.fill,
                {
                  width: `${
                    duration ? Math.min(100, (position / duration) * 100) : 0
                  }%`,
                },
              ]}
            />
          </View>
          <View style={s.time}>
            <Text style={s.subtitle}>
              {clock(position)} / {clock(duration)}
            </Text>
            <Text style={s.subtitle}>竖屏原比例 · 自动连播</Text>
          </View>
          <View style={s.controls}>
            <Button
              label="上一集"
              disabled={index === 0 || test}
              onPress={() => switchEpisode(index - 1)}
            />
            <Button
              label="−10 秒"
              disabled={!source || !!error}
              onPress={() => seek(-10)}
            />
            <Button
              label={finished ? "重新播放" : paused ? "继续播放" : "暂停"}
              primary
              preferred
              onPress={() => {
                if (finished) {
                  seekStart.current = 0;
                  progress.current.completed = false;
                  progress.current.position = 0;
                  refresh();
                } else setPaused((v) => !v);
              }}
            />
            <Button
              label="+10 秒"
              disabled={!source || !!error}
              onPress={() => seek(10)}
            />
            <Button
              label="下一集"
              disabled={index === detail.episodes.length - 1 || test}
              onPress={() => switchEpisode(index + 1)}
            />
          </View>
        </View>
      )}
    </View>
  );
}
const s = StyleSheet.create({
  screen: { flex: 1, backgroundColor: "#050507" },
  top: {
    flexDirection: "row",
    alignItems: "center",
    gap: 20,
    padding: 20,
    backgroundColor: "#101016D0",
  },
  title: { color: palette.text, fontSize: 22, fontWeight: "600" },
  subtitle: { color: palette.muted, fontSize: 13 },
  brand: { color: palette.accent, fontSize: 20, fontWeight: "bold" },
  bottom: { backgroundColor: "#101016E0", padding: 20 },
  track: { height: 3, backgroundColor: palette.border, borderRadius: 3 },
  fill: { height: 3, backgroundColor: palette.accent },
  time: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginVertical: 12,
  },
  controls: { flexDirection: "row", gap: 12, justifyContent: "center" },
  buffer: {
    color: palette.text,
    fontSize: 20,
    backgroundColor: "#101016D0",
    padding: 20,
    borderRadius: 14,
  },
});
