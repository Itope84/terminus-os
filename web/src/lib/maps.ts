// ```map cards (skills/location): Leaflet with tiles through the Mac (/terminus/_tiles), so the
// phone never talks to the tile servers. "Show my location" uses the phone's GPS, on tap only.
import 'leaflet/dist/leaflet.css';

interface MapCfg {
  places?: { name?: string; lat: number; lng: number }[];
  me?: boolean;
  line?: boolean;
  zoom?: number;
  path?: [number, number][];
  pathLabel?: string;
}

const km = (m: number) => (m >= 1000 ? `${(m / 1000).toFixed(m >= 10000 ? 0 : 1)} km` : `${Math.round(m / 10) * 10} m`);

export async function drawMaps(root: HTMLElement) {
  const cards = [...root.querySelectorAll<HTMLElement>('.mapcard')].filter((el) => !el.dataset.drawn);
  if (!cards.length) return;
  const L = (await import('leaflet')).default;
  for (const el of cards) {
    el.dataset.drawn = '1';
    let cfg: MapCfg;
    try {
      cfg = JSON.parse(el.dataset.map || '{}');
    } catch {
      continue;
    }
    const map = L.map(el, { zoomControl: true, attributionControl: true, scrollWheelZoom: false });
    L.tileLayer('/terminus/_tiles/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(map);
    const pts: [number, number][] = [];
    if (cfg.path && cfg.path.length > 1) {
      const route = L.polyline(cfg.path, { color: '#2f80ed', weight: 5, opacity: 0.8 }).addTo(map);
      if (cfg.pathLabel) route.bindTooltip(cfg.pathLabel, { permanent: true, direction: 'center' });
      pts.push(...cfg.path);
    }
    for (const p of (cfg.places ?? []).slice(0, 25)) {
      if (typeof p.lat !== 'number' || typeof p.lng !== 'number') continue;
      const m = L.circleMarker([p.lat, p.lng], { radius: 8, color: '#b5402a', weight: 2, fillColor: '#d97757', fillOpacity: 0.9 }).addTo(map);
      if (p.name) m.bindTooltip(p.name);
      m.on('click', () => window.open(`https://maps.apple.com/?q=${encodeURIComponent(p.name || '')}&ll=${p.lat},${p.lng}`, '_blank'));
      pts.push([p.lat, p.lng]);
    }
    if (pts.length > 1) map.fitBounds(pts, { padding: [28, 28], maxZoom: cfg.zoom || 16 });
    else if (pts.length === 1) map.setView(pts[0], cfg.zoom || 15);
    else map.setView([51.5, -0.12], 10);
    if (cfg.me && 'geolocation' in navigator) {
      const ask = document.createElement('button');
      ask.className = 'mapask';
      ask.type = 'button';
      ask.textContent = 'Show my location';
      el.after(ask);
      ask.onclick = () => {
        ask.textContent = 'Finding you…';
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            ask.remove();
            const me: [number, number] = [pos.coords.latitude, pos.coords.longitude];
            L.circle(me, { radius: pos.coords.accuracy || 30, stroke: false, fillColor: '#2f80ed', fillOpacity: 0.15 }).addTo(map);
            L.circleMarker(me, { radius: 7, color: '#fff', weight: 2, fillColor: '#2f80ed', fillOpacity: 1 }).addTo(map).bindTooltip('You');
            if (cfg.line) {
              for (const p of cfg.places ?? []) {
                const d = map.distance(me, [p.lat, p.lng]);
                L.polyline([me, [p.lat, p.lng]], { color: '#2f80ed', weight: 2, dashArray: '6 6', opacity: 0.9 })
                  .addTo(map)
                  .bindTooltip(`${km(d)} straight line`, { permanent: true, direction: 'center' });
              }
            }
            map.fitBounds([...pts, me], { padding: [28, 28], maxZoom: cfg.zoom || 16 });
          },
          () => {
            ask.textContent = 'Location is off for Terminus';
          },
          { enableHighAccuracy: true, timeout: 15_000, maximumAge: 60_000 },
        );
      };
    }
  }
}
