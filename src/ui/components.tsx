import React, { useState } from "react";
import {
  ActivityIndicator,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from "react-native";
import { Series } from "../domain/model";
export const palette = {
  bg: "#101016",
  panel: "#1C1C26",
  muted: "#A8A7B7",
  text: "#FAF8F6",
  accent: "#FF694F",
  border: "#32313F",
};
export function Button({
  label,
  onPress,
  primary = false,
  preferred = false,
  disabled = false,
  style,
}: {
  label: string;
  onPress: () => void;
  primary?: boolean;
  preferred?: boolean;
  disabled?: boolean;
  style?: ViewStyle;
}) {
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      hasTVPreferredFocus={preferred}
      disabled={disabled}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onPress={onPress}
      style={[
        s.button,
        primary && s.primary,
        focused && s.focused,
        disabled && { opacity: 0.35 },
        style,
      ]}
    >
      <Text style={[s.buttonText, focused && { color: palette.bg }]}>
        {label}
      </Text>
    </Pressable>
  );
}
export function Card({
  item,
  width,
  onPress,
}: {
  item: Series;
  width: number;
  onPress: () => void;
}) {
  const [focused, setFocused] = useState(false);
  const [broken, setBroken] = useState(false);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${item.title}，${item.badge}`}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onPress={onPress}
      style={[s.card, { width }, focused && s.cardFocus]}
    >
      <View style={[s.cover, { height: width * 1.28 }]}>
        {item.cover && !broken ? (
          <Image
            source={{ uri: item.cover }}
            resizeMode="cover"
            onError={() => setBroken(true)}
            style={StyleSheet.absoluteFill}
          />
        ) : (
          <Text style={s.placeholder}>红果</Text>
        )}
        <View style={s.badge}>
          <Text style={s.badgeText}>{item.badge || "短剧"}</Text>
        </View>
      </View>
      <Text numberOfLines={1} style={s.title}>
        {item.title}
      </Text>
      <Text numberOfLines={1} style={s.caption}>
        {item.tags || "点开选集"}
      </Text>
    </Pressable>
  );
}
export function Notice({
  message,
  busy = false,
  retry,
}: {
  message: string;
  busy?: boolean;
  retry?: () => void;
}) {
  return (
    <View style={s.notice}>
      {busy && <ActivityIndicator color={palette.accent} size="large" />}
      <Text style={s.noticeText}>{message}</Text>
      {retry && <Button label="重试" onPress={retry} primary preferred />}
    </View>
  );
}
const s = StyleSheet.create({
  button: {
    backgroundColor: palette.panel,
    borderWidth: 2,
    borderColor: "transparent",
    borderRadius: 12,
    paddingHorizontal: 18,
    paddingVertical: 11,
    alignItems: "center",
    justifyContent: "center",
    minHeight: 46,
  },
  primary: { backgroundColor: "#BA3E2A" },
  focused: { backgroundColor: "#FFE5DC", borderColor: palette.accent },
  buttonText: { color: palette.text, fontSize: 16, fontWeight: "600" },
  card: {
    margin: 7,
    padding: 5,
    borderWidth: 3,
    borderColor: "transparent",
    borderRadius: 14,
  },
  cardFocus: { borderColor: palette.accent, backgroundColor: "#29212A" },
  cover: {
    borderRadius: 9,
    overflow: "hidden",
    backgroundColor: palette.panel,
    alignItems: "center",
    justifyContent: "center",
  },
  placeholder: { color: "#7D4350", fontSize: 28, fontWeight: "bold" },
  badge: {
    position: "absolute",
    bottom: 0,
    right: 0,
    left: 0,
    backgroundColor: "#000000A0",
    padding: 8,
  },
  badgeText: { color: "#fff", fontSize: 12 },
  title: {
    color: palette.text,
    fontSize: 17,
    fontWeight: "600",
    marginTop: 10,
  },
  caption: { color: palette.muted, fontSize: 12, marginTop: 5 },
  notice: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 36,
    gap: 22,
  },
  noticeText: {
    color: palette.muted,
    fontSize: 19,
    textAlign: "center",
    maxWidth: 600,
    lineHeight: 30,
  },
});
