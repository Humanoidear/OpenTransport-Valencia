// Geo / dead-reckoning math, ported from the web app (static/motion.js + app.py).

export type LatLon = [number, number];
export type Shape = LatLon[]; // [[lat, lon], ...]
export interface PreparedRoute {
  pts: [number, number][]; // [lon, lat]
  cum: number[];
  total: number;
}

const R_EARTH = 6371000;
const M_PER_LAT = 111320;

const rad = (d: number) => (d * Math.PI) / 180;

export function haversineM(a: [number, number], b: [number, number]) {
  // a, b = [lon, lat]
  const dLat = rad(b[1] - a[1]);
  const dLon = rad(b[0] - a[0]);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLon / 2) ** 2;
  return 2 * R_EARTH * Math.asin(Math.sqrt(h));
}

export function prepareRoute(shape: Shape): PreparedRoute {
  const pts = shape.map(([lat, lon]) => [lon, lat] as [number, number]);
  const cum = [0];
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + haversineM(pts[i - 1], pts[i]));
  return { pts, cum, total: cum[cum.length - 1] || 0 };
}

export function projectOnRoute(route: PreparedRoute, lon: number, lat: number) {
  const mPerLon = M_PER_LAT * Math.cos(rad(lat)) || 1e-6;
  let best = { s: 0, dist: Infinity };
  for (let i = 1; i < route.pts.length; i++) {
    const ax = (route.pts[i - 1][0] - lon) * mPerLon;
    const ay = (route.pts[i - 1][1] - lat) * M_PER_LAT;
    const bx = (route.pts[i][0] - lon) * mPerLon;
    const by = (route.pts[i][1] - lat) * M_PER_LAT;
    const dx = bx - ax;
    const dy = by - ay;
    const len2 = dx * dx + dy * dy;
    let t = len2 ? -(ax * dx + ay * dy) / len2 : 0;
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    const d = Math.hypot(ax + dx * t, ay + dy * t);
    if (d < best.dist) {
      best = {
        dist: d,
        s: route.cum[i - 1] + haversineM(route.pts[i - 1], route.pts[i]) * t,
      };
    }
  }
  return best;
}

export function pointAt(route: PreparedRoute, s: number): [number, number] {
  // returns [lon, lat]
  const sc = s < 0 ? 0 : s > route.total ? route.total : s;
  let lo = 0;
  let hi = route.cum.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (route.cum[mid] <= sc) lo = mid;
    else hi = mid;
  }
  const seg = route.cum[hi] - route.cum[lo];
  const t = seg ? (sc - route.cum[lo]) / seg : 0;
  return [
    route.pts[lo][0] + (route.pts[hi][0] - route.pts[lo][0]) * t,
    route.pts[lo][1] + (route.pts[hi][1] - route.pts[lo][1]) * t,
  ];
}

export function clampSpeed(mps: number, maxSpeedMps = 15) {
  if (mps < 0.3) return 0;
  return Math.min(mps, maxSpeedMps);
}

export function predictArc(sObs: number, spd: number, dtS: number, total: number) {
  const s = sObs + spd * dtS;
  return s < 0 ? 0 : s > total ? total : s;
}

export function routeBearing(route: PreparedRoute, s: number) {
  const ahead = 15;
  let a = pointAt(route, s);
  let b = pointAt(route, Math.min(route.total, s + ahead));
  if (s + ahead > route.total) {
    a = pointAt(route, Math.max(0, s - ahead));
    b = pointAt(route, s);
  }
  const dLon = (b[0] - a[0]) * Math.cos(rad(a[1]));
  const dLat = b[1] - a[1];
  return (Math.atan2(dLon, dLat) * 180) / Math.PI + 360 % 360;
}

export interface Hold {
  id: string;
  s: number;
}

export function stopHold(hold: Hold | null, sProj: number, pSig: string | null, sStop: number | null, arriveM = 35, leaveM = 30): Hold | null {
  const id = pSig == null ? null : String(pSig);
  if (hold) {
    const gone = sProj > hold.s + leaveM || (id !== hold.id && sProj > hold.s - 10);
    return gone ? null : hold;
  }
  if (sStop == null || id == null) return null;
  return Math.abs(sProj - sStop) <= arriveM ? { id, s: sStop } : null;
}

// ---- direction matching against GTFS itineraries ----
export function itinerary(
  routes: Record<string, Record<string, { stops: string[] }>>,
  line: string,
  dir: string,
) {
  return routes[line]?.[dir]?.stops || [];
}

export function busDirection(
  routes: Record<string, Record<string, { stops: string[] }>>,
  line: string,
  pUlt: unknown,
  pSig: unknown,
): string | null {
  const pu = String(pUlt);
  const ps = String(pSig);
  for (const exactOnly of [true, false]) {
    for (const d of ["ida", "vuelta"]) {
      const it = routes[line]?.[d]?.stops || [];
      if (!it.includes(ps)) continue;
      if (!exactOnly) return d;
      if (it.includes(pu) && it.indexOf(ps) === it.indexOf(pu) + 1) return d;
    }
  }
  return null;
}

export interface Upcoming {
  id: number;
  nombre: string;
  minutes: number;
}

export function upcomingStops(
  routes: Record<string, Record<string, { stops: string[] }>>,
  stopsById: Map<string, { id: number; nombre: string; lat: number; lon: number }>,
  line: string,
  direction: string,
  pSig: unknown,
  lat: number,
  lon: number,
  limit = 12,
) {
  const it = routes[line]?.[direction]?.stops || [];
  const start = it.indexOf(String(pSig));
  if (start < 0) return [];
  const out: { id: number; nombre: string; minutes: number }[] = [];
  let prev: [number, number] = [lon, lat];
  let total = 0;
  for (let i = start; i < Math.min(start + limit, it.length); i++) {
    const s = stopsById.get(it[i]);
    if (!s) continue;
    total += haversineM(prev, [s.lon, s.lat]);
    prev = [s.lon, s.lat];
    out.push({ id: s.id, nombre: s.nombre, minutes: Math.max(1, Math.round(total / 18 * 60)) });
  }
  return out;
}

export function fmtDist(m: number) {
  return m < 950 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}