import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActivityIndicator,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import {
  Camera,
  GeoJSONSource,
  Layer,
  Map as MapLibreMap,
  Marker,
  type CameraRef,
  type CircleLayerStyle,
  type LineLayerStyle,
} from "@maplibre/maplibre-react-native";
import { SafeAreaProvider, useSafeAreaInsets } from "react-native-safe-area-context";
import {
  Chip,
  Divider,
  FAB,
  IconButton,
  List,
  Modal,
  PaperProvider,
  Portal,
  Searchbar,
  Surface,
  useTheme,
} from "react-native-paper";
import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Location from "expo-location";

import stopsData from "./src/data/stops.json";
import routesData from "./src/data/routes.json";
import { darkTheme, idaColor, lightTheme, lineColor, vueltaColor } from "./src/theme";
import * as geo from "./src/motion";
import {
  getBuses,
  getIncidents,
  getStop,
  lineIconUrl,
  type Incident,
  type Routes,
  type Stop,
  type StopInfo,
} from "./src/emt";

const STOPS = stopsData as Stop[];
const ROUTES = routesData as unknown as Routes;
const STOP_BY_ID = new Map(STOPS.map((s) => [String(s.id), s]));

const prepared = new Map<string, geo.PreparedRoute[]>();
const stopArcCache = new WeakMap<geo.PreparedRoute, Map<string, number>>();

const STYLES = {
  dark: "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json",
  light: "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json",
};

function shapeForDir(line: string, dir: "ida" | "vuelta") {
  return ROUTES[line]?.[dir]?.shapes?.[0] || null;
}
function shapesFor(line: string) {
  const r = ROUTES[line];
  if (!r) return [];
  return [...(r.ida?.shapes || []), ...(r.vuelta?.shapes || [])];
}
function preparedRoutes(line: string, dir: string): geo.PreparedRoute[] {
  const key = `${line}:${dir}`;
  if (!prepared.has(key)) {
    prepared.set(
      key,
      (ROUTES[line]?.[dir as "ida" | "vuelta"]?.shapes || [])
        .map(geo.prepareRoute)
        .filter((r) => r.total > 5),
    );
  }
  return prepared.get(key)!;
}
function nearestRoute(line: string, dir: string | null, lon: number, lat: number) {
  if (!dir) return null;
  let best: { route: geo.PreparedRoute; p: { s: number; dist: number } } | null = null;
  for (const r of preparedRoutes(line, dir)) {
    const p = geo.projectOnRoute(r, lon, lat);
    if (!best || p.dist < best.p.dist) best = { route: r, p };
  }
  return best;
}
function stopArc(route: geo.PreparedRoute, stop: Stop) {
  let m = stopArcCache.get(route);
  if (!m) {
    m = new Map();
    stopArcCache.set(route, m);
  }
  const k = String(stop.id);
  if (!m.has(k)) m.set(k, geo.projectOnRoute(route, stop.lon, stop.lat).s);
  return m.get(k)!;
}

const OFF_ROUTE = 150;
const POLL_MS = 5000;
const CORRECT_MS = 400;
const VALENCIA: [number, number] = [-0.3763, 39.4699];

interface BusState {
  num: number;
  line: string;
  tra: string;
  pSig: number;
  dir: string | null;
  ts: string;
  obs: [number, number];
  obsT: number;
  vel: [number, number];
  render: [number, number];
  route: geo.PreparedRoute | null;
  s: number;
  sObs: number;
  spd: number;
  hold: geo.Hold | null;
}

function fc(features: object[]): GeoJSON.FeatureCollection {
  return { type: "FeatureCollection", features: features as GeoJSON.Feature[] };
}
function pt(lon: number, lat: number, props: object = {}): GeoJSON.Feature {
  return { type: "Feature", properties: props, geometry: { type: "Point", coordinates: [lon, lat] } };
}
function line(coords: number[][], props: object = {}): GeoJSON.Feature {
  return {
    type: "Feature",
    properties: props,
    geometry: { type: "LineString", coordinates: coords },
  };
}

export default function App() {
  return (
    <SafeAreaProvider>
      <Shell />
    </SafeAreaProvider>
  );
}

function Shell() {
  const [dark, setDark] = useState(false);
  return (
    <PaperProvider theme={dark ? darkTheme : lightTheme}>
      <MainScreen dark={dark} onToggleDark={() => setDark((d) => !d)} />
    </PaperProvider>
  );
}

function MainScreen({ dark, onToggleDark }: { dark: boolean; onToggleDark: () => void }) {
  const insets = useSafeAreaInsets();
  const theme = useTheme();

  const [currentLine, setCurrentLine] = useState<string | null>(null);
  const [buses, setBuses] = useState<BusState[]>([]);
  const [selectedStop, setSelectedStop] = useState<Stop | null>(null);
  const [stopInfo, setStopInfo] = useState<StopInfo | null>(null);
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [follow, setFollow] = useState<BusState | null>(null);
  const [followInfo, setFollowInfo] = useState<geo.Upcoming[]>([]);
  const [viewport, setViewport] = useState<{ zoom: number; b: [number, number, number, number] }>({
    zoom: 13,
    b: [-0.43, 39.42, -0.32, 39.52],
  });
  const [search, setSearch] = useState("");
  const [searchOpen, setSearchOpen] = useState(false);
  const [favorites, setFavorites] = useState<number[]>([]);
  const [locating, setLocating] = useState(false);
  const [userLoc, setUserLoc] = useState<[number, number] | null>(null);
  const [incidentsOpen, setIncidentsOpen] = useState(true);
  const [loading, setLoading] = useState(false);

  const cameraRef = useRef<CameraRef>(null);
  const busesRef = useRef<BusState[]>([]);
  busesRef.current = buses;
  const followRef = useRef<BusState | null>(null);
  followRef.current = follow;

  // ---------- prediction render loop ----------
  useEffect(() => {
    const iv = setInterval(() => {
      const now = Date.now();
      setBuses((prev) =>
        prev.map((a) => {
          const k = 1 - Math.exp(-16 / CORRECT_MS);
          if (a.route) {
            const age = now - a.obsT;
            const spd = age > 25000 ? 0 : a.spd;
            const target = a.hold ? a.hold.s : geo.predictArc(a.sObs, spd, age / 1000, a.route.total);
            const err = Math.abs(target - a.s);
            const kk = err > 25 ? 1 - Math.exp(-16 / 2500) : k;
            a.s += (target - a.s) * kk;
            a.render = geo.pointAt(a.route, a.s);
          } else {
            const dt = (now - a.obsT) / 1000;
            const px = a.obs[0] + a.vel[0] * dt;
            const py = a.obs[1] + a.vel[1] * dt;
            const kk = 1 - Math.exp(-16 / 2500);
            a.render[0] += (px - a.render[0]) * kk;
            a.render[1] += (py - a.render[1]) * kk;
          }
          return a;
        }),
      );
    }, 33);
    return () => clearInterval(iv);
  }, []);

  // follow camera rides the followed bus (jump each frame; marker is at center)
  useEffect(() => {
    const f = followRef.current;
    if (!f) return;
    const cur = busesRef.current.find((b) => b.num === f.num);
    if (!cur) return;
    const heading =
      cur.route && cur.spd > 1 && cur.s < cur.route.total - 5
        ? geo.routeBearing(cur.route, cur.s)
        : 0;
    cameraRef.current?.jumpTo({ center: [cur.render[0], cur.render[1]], bearing: heading });
  }, [buses]);

  // ---------- polling live buses for the revealed line ----------
  useEffect(() => {
    if (!currentLine) {
      setBuses([]);
      setFollow(null);
      return;
    }
    let alive = true;
    const poll = async () => {
      try {
        const live = await getBuses(currentLine);
        if (!alive) return;
        const now = Date.now();
        setBuses((prev) => {
          const map = new Map(prev.map((b) => [b.num, b]));
          const seen = new Set<number>();
          for (const b of live) {
            seen.add(b.num);
            const pos: [number, number] = [b.lon, b.lat];
            const a = map.get(b.num);
            if (!a) {
              const cand = nearestRoute(currentLine, b.dir, pos[0], pos[1]);
              const onRoute = cand && cand.p.dist <= OFF_ROUTE;
              const render = onRoute ? geo.pointAt(cand.route, cand.p.s) : pos;
              map.set(b.num, {
                num: b.num, line: b.lin, tra: b.tra, pSig: b.pSig, dir: b.dir, ts: b.ts,
                obs: pos, obsT: now, vel: [0, 0], render,
                route: onRoute ? cand.route : null,
                s: onRoute ? cand.p.s : 0, sObs: onRoute ? cand.p.s : 0, spd: 0, hold: null,
              });
              continue;
            }
            const fresh = b.ts !== a.ts;
            const moved =
              Math.abs(pos[0] - a.obs[0]) > 1e-6 || Math.abs(pos[1] - a.obs[1]) > 1e-6;
            if (fresh) {
              if (moved) {
                const dtS = a.ts ? (Date.parse(b.ts) - Date.parse(a.ts)) / 1000 : 0;
                const cand = nearestRoute(currentLine, b.dir, pos[0], pos[1]);
                if (cand && cand.p.dist <= OFF_ROUTE) {
                  if (a.route !== cand.route) {
                    a.route = cand.route;
                    const p0 = geo.projectOnRoute(a.route, a.render[0], a.render[1]);
                    a.s = p0.dist <= OFF_ROUTE ? p0.s : cand.p.s;
                  } else if (dtS > 0.5) {
                    a.spd = (a.spd + geo.clampSpeed((cand.p.s - a.sObs) / dtS)) / 2;
                  }
                  a.sObs = cand.p.s;
                } else {
                  a.route = null;
                  if (dtS > 0.5) {
                    a.vel = [
                      a.vel[0] * 0.5 + ((pos[0] - a.obs[0]) / dtS) * 0.5,
                      a.vel[1] * 0.5 + ((pos[1] - a.obs[1]) / dtS) * 0.5,
                    ];
                  }
                }
                a.obs = pos;
                a.obsT = now;
              } else {
                a.spd *= 0.5;
                a.obsT = now;
              }
              a.ts = b.ts;
              a.pSig = b.pSig;
              if (a.route) {
                const stop = STOP_BY_ID.get(String(b.pSig));
                a.hold = geo.stopHold(
                  a.hold, a.sObs, String(b.pSig), stop ? stopArc(a.route, stop) : null,
                );
              }
            }
            map.set(b.num, a);
          }
          for (const [num] of map) if (!seen.has(num)) map.delete(num);
          return [...map.values()];
        });
      } catch {}
    };
    poll();
    const iv = setInterval(poll, POLL_MS);
    return () => {
      alive = false;
      clearInterval(iv);
    };
  }, [currentLine]);

  // refresh follow ETAs whenever buses update
  useEffect(() => {
    const f = followRef.current;
    if (!f) return;
    const cur = busesRef.current.find((b) => b.num === f.num);
    if (!cur?.route) return;
    setFollowInfo(
      geo.upcomingStops(ROUTES, STOP_BY_ID, cur.line, cur.dir ?? "ida", cur.pSig, cur.render[1], cur.render[0]),
    );
  }, [buses]);

  useEffect(() => {
    getIncidents().then(setIncidents);
  }, []);

  useEffect(() => {
    AsyncStorage.getItem("emt.favs").then((v) => {
      if (v) setFavorites(JSON.parse(v));
    });
  }, []);
  const toggleFav = useCallback((id: number) => {
    setFavorites((prev) => {
      const next = prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id];
      AsyncStorage.setItem("emt.favs", JSON.stringify(next));
      return next;
    });
  }, []);

  const selectStop = useCallback(
    async (stop: Stop, fit: boolean) => {
      setSelectedStop(stop);
      setStopInfo(null);
      setLoading(true);
      setCurrentLine(null); // a selected stop clears any line focus
      setFollow(null);
      try {
        const info = await getStop(stop.id, STOP_BY_ID, ROUTES);
        setStopInfo(info);
      } catch {
        setStopInfo(null);
      } finally {
        setLoading(false);
        if (fit) {
          let w = stop.lon, e = stop.lon, s = stop.lat, n = stop.lat;
          for (const l of stop.lineas)
            for (const sh of shapesFor(l))
              for (const [lat, lon] of sh) {
                w = Math.min(w, lon); e = Math.max(e, lon);
                s = Math.min(s, lat); n = Math.max(n, lat);
              }
          cameraRef.current?.fitBounds(
            [w, s, e, n],
            { padding: { top: 120, left: 60, right: 60, bottom: 360 }, duration: 800 },
          );
        }
      }
    },
    [],
  );

  const toggleLine = useCallback(
    (line: string) => {
      setFollow(null);
      if (currentLine === line) setCurrentLine(null);
      else setCurrentLine(line);
    },
    [currentLine],
  );

  const followBus = useCallback((b: BusState) => {
    setFollow(b);
    if (b.route) {
      setFollowInfo(
        geo.upcomingStops(ROUTES, STOP_BY_ID, b.line, b.dir ?? "ida", b.pSig, b.render[1], b.render[0]),
      );
    } else {
      setFollowInfo([]);
    }
  }, []);

  // ---------- map data (GeoJSON) ----------
  const stopSource = useMemo(() => {
    const [w, s, e, n] = viewport.b;
    if (viewport.zoom < 12) return fc([]);
    const feats: GeoJSON.Feature[] = [];
    for (const st of STOPS) {
      if (st.lat >= s && st.lat <= n && st.lon >= w && st.lon <= e) {
        feats.push(pt(st.lon, st.lat, { id: st.id }));
        if (feats.length >= 400) break;
      }
    }
    return fc(feats);
  }, [viewport]);

  const ctxSource = useMemo(() => {
    if (!selectedStop) return fc([]);
    return fc(
      selectedStop.lineas.flatMap((l) =>
        shapesFor(l).map((sh) => line(sh.map(([lat, lon]) => [lon, lat]), { linea: l })),
      ),
    );
  }, [selectedStop]);

  const mainSource = useMemo(() => {
    if (!currentLine) return fc([]);
    return fc(
      (["ida", "vuelta"] as const)
        .map((dir) => {
          const sh = shapeForDir(currentLine, dir);
          return sh ? line(sh.map(([lat, lon]) => [lon, lat]), { dir }) : null;
        })
        .filter(Boolean) as GeoJSON.Feature[],
    );
  }, [currentLine]);

  const ctxPaint = useMemo(() => {
    const expr: any = ["match", ["get", "linea"]];
    selectedStop?.lineas.forEach((l, i) => expr.push(l, lineColor(l, selectedStop.lineas)));
    expr.push("#888");
    return { lineColor: expr, lineWidth: 4, lineOpacity: 0.85, lineJoin: "round", lineCap: "round" } as LineLayerStyle;
  }, [selectedStop]);

  const stopPaint: CircleLayerStyle = useMemo(
    () => ({
      circleRadius: ["interpolate", ["linear"], ["zoom"], 12, 3, 16, 6],
      circleColor: theme.dark ? "#e4e4e7" : "#3f3f46",
      circleOpacity: 0.85,
      circleStrokeColor: theme.dark ? "#18181b" : "#ffffff",
      circleStrokeWidth: 1,
    }),
    [theme.dark],
  );

  const selectedLines = selectedStop ? selectedStop.lineas : [];
  const incidentsFor = (line: string) =>
    incidents.filter((i) => i.lines.includes(line.toUpperCase()));

  return (
    <View style={{ flex: 1, backgroundColor: theme.colors.surface }}>
      <MapLibreMap
        style={StyleSheet.absoluteFill}
        mapStyle={dark ? STYLES.dark : STYLES.light}
        attribution
        logo
        onRegionDidChange={(e) => {
          const [w, s, east, north] = e.nativeEvent.bounds;
          setViewport({ zoom: e.nativeEvent.zoom, b: [w, s, east, north] });
        }}
      >
        <Camera ref={cameraRef} initialViewState={{ center: VALENCIA, zoom: 13 }} />

        <GeoJSONSource id="ctx" data={ctxSource}>
          <Layer id="ctx-layer" type="line" source="ctx" style={ctxPaint} />
        </GeoJSONSource>

        <GeoJSONSource id="main" data={mainSource}>
          <Layer
            id="main-ida"
            type="line"
            source="main"
            filter={["==", ["get", "dir"], "ida"]}
            style={{ lineColor: idaColor, lineWidth: 5, lineOpacity: 0.9, lineJoin: "round", lineCap: "round" } as LineLayerStyle}
          />
          <Layer
            id="main-vuelta"
            type="line"
            source="main"
            filter={["==", ["get", "dir"], "vuelta"]}
            style={{ lineColor: vueltaColor, lineWidth: 5, lineOpacity: 0.9, lineJoin: "round", lineCap: "round" } as LineLayerStyle}
          />
        </GeoJSONSource>

        <GeoJSONSource
          id="stops"
          data={stopSource}
          onPress={(e) => {
            const id = e.nativeEvent.features?.[0]?.properties?.id;
            const st = id != null ? STOP_BY_ID.get(String(id)) : null;
            if (st) selectStop(st, false);
          }}
        >
          <Layer id="stops-layer" type="circle" source="stops" style={stopPaint} />
        </GeoJSONSource>

        {userLoc ? (
          <Marker id="user" lngLat={userLoc} anchor="center">
            <View style={styles.userDot} />
          </Marker>
        ) : null}

        {buses.map((b) => (
          <Marker
            key={b.num}
            id={`bus-${b.num}`}
            lngLat={b.render}
            anchor="center"
            onPress={() => followBus(b)}
          >
            <View style={[styles.busChip, follow?.num === b.num && styles.busChipActive]}>
              <Text style={styles.busText}>{b.line}</Text>
            </View>
          </Marker>
        ))}
      </MapLibreMap>

      {/* top bar */}
      <View style={[styles.topBar, { paddingTop: insets.top + 8 }]}>
        <Searchbar
          placeholder="Search a stop…"
          value={search}
          onChangeText={setSearch}
          onFocus={() => setSearchOpen(true)}
          style={styles.search}
          inputStyle={{ minHeight: 40 }}
        />
        <Surface style={styles.roundBtn} elevation={2}>
          <IconButton icon="theme-light-dark" onPress={onToggleDark} />
        </Surface>
        <Surface style={styles.roundBtn} elevation={2}>
          <IconButton icon="star" onPress={() => setSearchOpen(true)} />
        </Surface>
      </View>

      {/* follow panel */}
      {follow ? (
        <Surface
          style={[styles.followPanel, { marginBottom: selectedStop ? 330 : insets.bottom + 12 }]}
          elevation={3}
        >
          <View style={styles.followHead}>
            <Chip icon="bus" textStyle={{ fontWeight: "700" }} onClose={() => setFollow(null)}>
              Line {follow.line} · bus {follow.num}
            </Chip>
            <Text style={styles.followSub} numberOfLines={1}>
              {follow.dir} · {follow.tra}
            </Text>
          </View>
          {followInfo.length ? (
            <ScrollView style={{ maxHeight: 130 }}>
              {followInfo.map((s, i) => (
                <View key={s.id} style={styles.followRow}>
                  <Text style={styles.followStop}>{i === 0 ? "next" : `${i + 1}.`}</Text>
                  <Text style={[styles.followName, { flex: 1 }]} numberOfLines={1}>
                    {s.nombre}
                  </Text>
                  <Text style={styles.followMin}>{s.minutes} min</Text>
                </View>
              ))}
            </ScrollView>
          ) : (
            <ActivityIndicator style={{ padding: 8 }} />
          )}
        </Surface>
      ) : null}

      {/* stop sheet */}
      {selectedStop ? (
        <Surface style={[styles.sheet, { paddingBottom: insets.bottom + 8 }]} elevation={5}>
          <View style={styles.sheetHead}>
            <Text style={styles.sheetTitle} numberOfLines={1}>
              {stopName(selectedStop.nombre)}
            </Text>
            <IconButton
              icon={favorites.includes(selectedStop.id) ? "star" : "star-outline"}
              onPress={() => toggleFav(selectedStop.id)}
            />
            <IconButton icon="close" onPress={() => setSelectedStop(null)} />
          </View>
          <Text style={styles.sheetSub}>
            stop {selectedStop.id} · lines {selectedStop.lineas.join(", ")}
          </Text>
          {loading ? (
            <ActivityIndicator style={{ padding: 16 }} />
          ) : stopInfo ? (
            <ScrollView style={{ maxHeight: 320 }}>
              {(() => {
                const ents = selectedLines
                  .map((l) => ({ l, inc: incidentsFor(l) }))
                  .filter((e) => e.inc.length > 0);
                return ents.length ? (
                  <View style={{ marginBottom: 4 }}>
                    <Pressable onPress={() => setIncidentsOpen((o) => !o)} style={styles.incidentHead}>
                      <Text style={styles.incidentHeadText}>⚠ Service updates ({ents.length})</Text>
                      <Text style={styles.incidentHeadText}>{incidentsOpen ? "▾" : "▸"}</Text>
                    </Pressable>
                    {incidentsOpen
                      ? ents.map((e) => (
                          <View key={e.l} style={styles.incidentRow}>
                            <Image source={{ uri: lineIconUrl(e.l, 40) }} style={styles.incLineImg} />
                            <Text style={styles.incidentText} numberOfLines={2}>
                              {e.inc[0].title}
                            </Text>
                          </View>
                        ))
                      : null}
                  </View>
                ) : null;
              })()}
              {stopInfo.arrivals.map((a, i) => {
                const nb = stopInfo.buses.find((b) => b.linea === a.linea);
                return (
                  <Pressable
                    key={i}
                    onPress={() => toggleLine(a.linea)}
                    style={[styles.arrivalRow, currentLine === a.linea && styles.arrivalActive]}
                  >
                    <View style={[styles.dot, { backgroundColor: lineColor(a.linea, selectedLines) }]} />
                    <Image source={{ uri: lineIconUrl(a.linea, 40) }} style={styles.lineImg} />
                    <View style={{ flex: 1, paddingHorizontal: 8 }}>
                      <Text style={styles.arrDest} numberOfLines={1}>
                        {a.destino || a.linea}
                      </Text>
                      <Text style={styles.arrBus}>
                        {nb ? `bus ${nb.num} · ${geo.fmtDist(nb.dist * 1000)}` : ""}
                      </Text>
                    </View>
                    <Text style={styles.arrTime}>
                      {a.minutos || (a.horaLlegada ? a.horaLlegada.slice(0, 5) : "—")}
                    </Text>
                  </Pressable>
                );
              })}
            </ScrollView>
          ) : null}
        </Surface>
      ) : null}

      {/* search + favorites */}
      <Portal>
        <Modal
          visible={searchOpen}
          onDismiss={() => setSearchOpen(false)}
          contentContainerStyle={{ paddingTop: insets.top + 8 }}
        >
          <Surface style={styles.modalSurface} elevation={4}>
            <Searchbar
              placeholder="Stop name or number…"
              value={search}
              onChangeText={setSearch}
              autoFocus
            />
            <ScrollView style={{ maxHeight: 500 }} keyboardShouldPersistTaps="handled">
              {favorites.length > 0 ? (
                <>
                  <Text style={styles.listTitle}>★ Favorites</Text>
                  {favorites.map((id) => {
                    const s = STOP_BY_ID.get(String(id));
                    if (!s) return null;
                    return (
                      <List.Item
                        key={id}
                        title={stopName(s.nombre)}
                        description={s.lineas.join(" · ")}
                        left={(p) => <List.Icon {...p} icon="star" />}
                        right={(p) => <IconButton {...p} icon="close" onPress={() => toggleFav(id)} />}
                        onPress={() => {
                          setSearchOpen(false);
                          selectStop(s, true);
                        }}
                      />
                    );
                  })}
                  <Divider style={{ marginVertical: 6 }} />
                </>
              ) : null}
              <Text style={styles.listTitle}>Stops</Text>
              {searchStops(search).map((s) => (
                <List.Item
                  key={s.id}
                  title={stopName(s.nombre)}
                  description={s.lineas.join(" · ")}
                  left={(p) => <List.Icon {...p} icon="map-marker" />}
                  onPress={() => {
                    setSearchOpen(false);
                    selectStop(s, true);
                  }}
                />
              ))}
            </ScrollView>
          </Surface>
        </Modal>
      </Portal>

      <FAB
        style={[styles.fabLocate, { bottom: selectedStop ? 340 : insets.bottom + 24 }]}
        icon={locating ? "progress-clock" : "crosshairs-gps"}
        onPress={async () => {
          setLocating(true);
          const { status } = await Location.requestForegroundPermissionsAsync();
          if (status === "granted") {
            const pos = await Location.getCurrentPositionAsync({});
            const c: [number, number] = [pos.coords.longitude, pos.coords.latitude];
            setUserLoc(c);
            cameraRef.current?.easeTo({ center: c, zoom: 15, duration: 800 });
          }
          setLocating(false);
        }}
        small
      />
    </View>
  );
}

function stopName(nombre: string) {
  return nombre.replace(/\s*\(\d+\)\s*$/, "").trim();
}
function searchStops(q: string) {
  const n = q.trim().toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
  if (!n) return [];
  if (/^\d+$/.test(n)) return STOPS.filter((s) => String(s.id).includes(n)).slice(0, 30);
  const words = n.split(/\s+/);
  return STOPS.filter((s) => {
    const name = s.nombre.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
    return words.every((w) => name.includes(w));
  }).slice(0, 30);
}

const styles = StyleSheet.create({
  topBar: { position: "absolute", top: 0, left: 12, right: 12, flexDirection: "row", alignItems: "center", gap: 8 },
  search: { flex: 1 },
  roundBtn: { borderRadius: 28 },
  userDot: { width: 14, height: 14, borderRadius: 7, backgroundColor: "#22c55e", borderWidth: 2, borderColor: "#fff" },
  busChip: {
    minWidth: 34, height: 22, borderRadius: 11, alignItems: "center", justifyContent: "center",
    backgroundColor: "#111", borderWidth: 1, borderColor: "#333", paddingHorizontal: 6,
  },
  busChipActive: { borderColor: "#fff", borderWidth: 2 },
  busText: { color: "#fff", fontSize: 12, fontWeight: "800" },
  followPanel: { position: "absolute", left: 12, right: 12, borderRadius: 16, padding: 12 },
  followHead: { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 6 },
  followSub: { fontSize: 12, opacity: 0.7, flex: 1 },
  followRow: { flexDirection: "row", gap: 8, paddingVertical: 3, alignItems: "center" },
  followStop: { fontSize: 12, fontWeight: "700", width: 30 },
  followName: { fontSize: 13 },
  followMin: { fontSize: 12, fontWeight: "600" },
  sheet: { position: "absolute", left: 8, right: 8, borderRadius: 20, padding: 12 },
  sheetHead: { flexDirection: "row", alignItems: "center", gap: 4 },
  sheetTitle: { fontSize: 16, fontWeight: "700", flex: 1 },
  sheetSub: { fontSize: 12, opacity: 0.6, marginBottom: 4 },
  arrivalRow: { flexDirection: "row", alignItems: "center", paddingVertical: 8 },
  arrivalActive: { backgroundColor: "rgba(59,130,246,0.12)", borderRadius: 8 },
  dot: { width: 8, height: 8, borderRadius: 4, marginRight: 6 },
  lineImg: { width: 34, height: 34 },
  incLineImg: { width: 24, height: 24, marginRight: 6 },
  arrDest: { fontSize: 14, fontWeight: "600" },
  arrBus: { fontSize: 12, opacity: 0.6 },
  arrTime: { fontSize: 13, fontWeight: "700" },
  incidentHead: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", paddingVertical: 8 },
  incidentHeadText: { fontWeight: "700", color: "#F59E0B" },
  incidentRow: { flexDirection: "row", alignItems: "center", paddingVertical: 4 },
  incidentText: { flex: 1, fontSize: 12, color: "#B45309" },
  listTitle: { fontSize: 13, fontWeight: "700", opacity: 0.6, marginVertical: 6 },
  modalSurface: { margin: 12, borderRadius: 20, padding: 8, minHeight: 200 },
  fabLocate: { position: "absolute", right: 16 },
});