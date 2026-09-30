// EMT Valencia API client (same auth + endpoints as the web app).
import { XMLParser } from "fast-xml-parser";
import * as geo from "./motion";

const HOST = "https://servicios.emtvalencia.es";
const USER = "7gH8m45w7A";
// Replayed static WSSE token from a captured request.
const WSSE =
  'UsernameToken Username="7gH8m45w7A", ' +
  'PasswordDigest="ODdmMzU1OWU4ZDEwZGE0MDllM2E5NzhlZTg3Y2UxMmRjYTQ2N2VmYQ==", ' +
  'Nonce="MjZlNTNjMWIxZmZhMmU4NjE5N2QyYjhkMjgyMGU2YjU=", ' +
  'Created="1790762921"';

const HEADERS = {
  "X-WSSE": WSSE,
  "Accept-Charset": "UTF-8",
  "Content-Type": "application/json; charset=UTF-8",
  "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 17; Pixel 8a Build/CP41.260831.007)",
};

async function get(path: string, params: Record<string, string>, timeoutMs = 10000) {
  const qs = new URLSearchParams(params).toString();
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${HOST}${path}?${qs}`, { headers: HEADERS, signal: ctrl.signal });
    return await res.text();
  } finally {
    clearTimeout(t);
  }
}

export interface LiveBus {
  num: number;
  lin: string;
  tra: string;
  pUlt: number;
  pSig: number;
  lon: number;
  lat: number;
  ts: string;
  dir: string | null;
}

export async function getBuses(line: string): Promise<LiveBus[]> {
  const body = await get("/buses/linea.php", { usuario: USER, linea: line });
  try {
    const data = JSON.parse(body);
    if (data && Array.isArray(data.buses)) return data.buses;
  } catch {}
  return [];
}

export interface Arrival {
  linea: string;
  destino: string;
  minutos: string;
  horaLlegada: string;
}

export interface NearBus {
  linea: string;
  num: number;
  lat: number;
  lon: number;
  dir: string | null;
  minutos: number;
  dist: number;
}

export interface StopInfo {
  stop: { id: number; nombre: string; lat: number; lon: number; lineas: string[] };
  arrivals: Arrival[];
  buses: NearBus[];
}

export interface Stop {
  id: number;
  nombre: string;
  lat: number;
  lon: number;
  lineas: string[];
}
export interface RouteLine {
  shapes: [number, number][][];
  stops: string[];
}
export type Routes = Record<string, { ida: RouteLine; vuelta: RouteLine }>;

function parseEstimaciones(xml: string): Arrival[] {
  const parser = new XMLParser();
  let doc: any;
  try {
    doc = parser.parse(xml);
  } catch {
    return [];
  }
  const root = doc?.estimacion?.solo_parada;
  const buses = Array.isArray(root?.bus) ? root.bus : root?.bus ? [root.bus] : [];
  const out: Arrival[] = [];
  for (const b of buses) {
    const row: Arrival = {
      linea: String(b.linea ?? "").trim(),
      destino: String(b.destino ?? "").trim(),
      minutos: String(b.minutos ?? "").trim(),
      horaLlegada: String(b.horaLlegada ?? "").trim(),
    };
    if (row.linea || row.destino || row.minutos) out.push(row);
  }
  return out;
}

export async function getStop(parada: number, stopsById: Map<string, Stop>, routes: Routes): Promise<StopInfo | null> {
  const stop = stopsById.get(String(parada));
  if (!stop) return null;
  const lines = stop.lineas;
  const arrivals: Arrival[] = [];
  const buses: NearBus[] = [];

  for (const line of lines) {
    const live = await getBuses(line);
    let best: { d: number; b: LiveBus } | null = null;
    for (const b of live) {
      const dir = geo.busDirection(routes, line, b.pUlt, b.pSig);
      const it = dir ? geo.itinerary(routes, line, dir) : [];
      const iBus = it.indexOf(String(b.pSig));
      const iStop = it.indexOf(String(parada));
      if (iBus < 0 || iStop < 0 || iBus >= iStop) continue;
      const d = geo.haversineM([b.lon, b.lat], [stop.lon, stop.lat]);
      if (!best || d < best.d) best = { d, b };
    }
    if (best) {
      const minutes = Math.max(1, Math.round((best.d / 18) * 60));
      buses.push({
        linea: line,
        num: best.b.num,
        lat: best.b.lat,
        lon: best.b.lon,
        dir: best.b.dir,
        minutos: minutes,
        dist: Math.round(best.d * 100) / 100,
      });
      arrivals.push({ linea: line, destino: best.b.tra, minutos: `${minutes} min.`, horaLlegada: "" });
    }
  }

  // authoritative ETAs when the token is fresh
  try {
    const xml = await get("/estimaciones/estimacion.php", {
      idioma: "en",
      parada: String(parada),
      adaptados: "false",
    });
    const est = parseEstimaciones(xml);
    if (est.length) {
      arrivals.length = 0;
      for (const a of est) arrivals.push(a);
    }
  } catch {}

  const key = (a: Arrival) => {
    const s = a.minutos.toLowerCase();
    if (s.startsWith("next")) return 0;
    const m = /^(\d+)/.exec(s);
    if (m) return parseInt(m[1], 10);
    const tm = /^(\d+):(\d+)/.exec(a.horaLlegada);
    if (tm) {
      const now = new Date();
      const nowS = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();
      const target = parseInt(tm[1], 10) * 3600 + parseInt(tm[2], 10) * 60;
      return target >= nowS ? Math.floor((target - nowS) / 60) : Math.floor((target + 86400 - nowS) / 60);
    }
    return 10 ** 9;
  };
  arrivals.sort((a, b) => key(a) - key(b));
  buses.sort((a, b) => a.minutos - b.minutos);
  return { stop, arrivals, buses };
}

export interface Incident {
  title: string;
  date: string;
  lines: string[];
}

const normLine = (x: string) => {
  const v = (x || "").trim().toUpperCase();
  return /^\d+$/.test(v) ? String(parseInt(v, 10)) : v;
};

export async function getIncidents(): Promise<Incident[]> {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 20000);
    const res = await fetch("https://www.emtvalencia.es/wp/estado-del-servicio/", {
      headers: { "User-Agent": "Mozilla/5.0" },
      signal: ctrl.signal,
    });
    clearTimeout(t);
    return parseIncidentsHtml(await res.text());
  } catch {
    return [];
  }
}

export function parseIncidentsHtml(html: string): Incident[] {
  const cards: Incident[] = [];
  const secRe = /<section class="estado-servicio">(.*?)<\/section>/gs;
  let m: RegExpExecArray | null;
  while ((m = secRe.exec(html))) {
    const sec = m[1];
    const text = sec.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    const dateM = /Desde:\s*([0-9/]+)/.exec(text);
    const lines = [...sec.matchAll(/<img[^>]*alt="L[ií]nea\s+([A-Za-z0-9]+)/g)].map((x) =>
      normLine(x[1]),
    );
    const titleM = /<h2[^>]*>\s*<a[^>]*>(.*?)<\/a>/s.exec(sec);
    const title = titleM ? titleM[1].replace(/<[^>]+>/g, "").trim() : text.slice(0, 160);
    cards.push({ title, date: dateM ? dateM[1] : "", lines });
  }
  return cards;
}

export const lineIconUrl = (line: string, size = 50) =>
  `https://geoportal.emtvalencia.es/ciudadano/icongenerator/create-line-image.php?size=${size}&type=normal&lineNumber=${encodeURIComponent(line)}&showBorder=false&borderColor=white`;