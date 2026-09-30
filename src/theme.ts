// Material 3 themes (Expressive-style rounded shapes) — light & dark.
import React from "react";
import { MD3LightTheme, MD3DarkTheme, type MD3Theme } from "react-native-paper";
import { MaterialCommunityIcons } from "@expo/vector-icons";

const paperSettings = {
  icon: (props: any) => React.createElement(MaterialCommunityIcons, props),
};

const lineColors = [
  "#f97316", "#0ea5e9", "#e11d48", "#8b5cf6", "#14b8a6", "#f43f5e",
  "#6366f1", "#22c55e", "#f59e0b", "#06b6d4", "#a855f7", "#84cc16",
];

export const idaColor = "#3b82f6";
export const vueltaColor = "#ef4444";

export function lineColor(line: string, linesInStop: string[]) {
  const i = linesInStop.indexOf(String(line));
  return i >= 0 ? lineColors[i % lineColors.length] : "#f97316";
}

export const lightTheme = {
  ...MD3LightTheme,
  roundness: 3,
  settings: paperSettings,
  colors: {
    ...MD3LightTheme.colors,
    primary: "#B3261E",
    onPrimary: "#FFFFFF",
    primaryContainer: "#FFDAD6",
    onPrimaryContainer: "#410002",
    secondary: "#8B5000",
    secondaryContainer: "#FFDCC2",
    onSecondaryContainer: "#2B1600",
    tertiary: "#7A5900",
    error: "#BA1A1A",
    surface: "#FFF8F6",
    surfaceVariant: "#F5DEDB",
    onSurface: "#201A19",
    onSurfaceVariant: "#52443D",
    outline: "#85736C",
    outlineVariant: "#D8C2BC",
  },
} as MD3Theme;

export const darkTheme = {
  ...MD3DarkTheme,
  roundness: 3,
  settings: paperSettings,
  colors: {
    ...MD3DarkTheme.colors,
    primary: "#FFB4AB",
    onPrimary: "#690005",
    primaryContainer: "#93000A",
    onPrimaryContainer: "#FFDAD6",
    secondary: "#FFB77C",
    secondaryContainer: "#663F00",
    onSecondaryContainer: "#FFDCC2",
    tertiary: "#E9C368",
    error: "#FFB4AB",
    surface: "#141313",
    surfaceVariant: "#52443D",
    onSurface: "#F5DEDB",
    onSurfaceVariant: "#D8C2BC",
    outline: "#A08C85",
    outlineVariant: "#52443D",
  },
} as MD3Theme;

export const warnColor = "#F59E0B";

export { lineColors };