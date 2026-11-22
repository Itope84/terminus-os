---
name: location
description: The owner's current location from their phone, distance to a place, "tell me when I'm near X" watchers, and map cards in chat.
---

# Location — where the phone is, on request

The phone answers when asked; nothing tracks it in between. Use it whenever a task depends on where the owner is: "near me", "how far am I from…", "parking around here", "tell me when I'm close to…".

## Get it

- `python3 skills/location/where.py` → `{lat, lng, accuracy, at, ageSec, fresh}`. `fresh:false` = the phone didn't answer in time, this is its last fix (`ageSec` old); say so if it matters.
- `--max-age 300` reuses a recent fix without waking the phone.
- `where.py distance --lat .. --lng ..` → metres from here.
- Exit 3 / `permission_needed`: the phone has shown "Allow location" to the owner. Tell them in one line; don't retry in a loop.
- Exit 2: phone offline and nothing known. Ask the owner where they are.
- Place names to coordinates: geocode as in `skills/commute` (Nominatim), then confirm the match when ambiguous.

## Watch for a place ("tell me when I'm near the station")

Freehand, with the schedule skill: an `exec` job running `near` every few minutes, retiring when it fires.

```
python3 skills/schedule/schedule.py add --name near-station --every 5m --until "<date>" --type exec \
  --payload "python3 <terminus>/skills/location/where.py near --lat 48.8584 --lng 2.2945 --radius 500 --name "Example Station"" \
  --retire-on-alert --title "Near station"
```

- `near` prints `SILENT` until inside the radius, then one line ("You're 300 m from Example Station."). No model runs per tick.
- Watchers need "Allow all the time" (the phone asks the first time a background request arrives). Without it they only work while the app is open: say so when creating one.
- Always set `--until` (a day or the trip's end): a forgotten watcher must not run forever. Radius 300–800 m on foot, 1–3 km by car.

## Show a map in the reply

A fenced block with language `map` renders as an interactive map card in the app (Leaflet, OpenStreetMap tiles via the Mac):

````
```map
{"places":[{"name":"Example Station","lat":48.8584,"lng":2.2945}],"me":true}
```
````

- `places`: up to 25 `{name, lat, lng}`. `me:true` adds the user's blue dot, drawn on the phone from its own GPS. `zoom` (3–19) optional; otherwise it fits everything.
- `"line":true` (with `me`): a dashed line from the user's dot to each place, labelled with the straight-line distance. For "plot my distance to X".
- `"path":[[lat,lng],...]` + `"pathLabel":"6.1 km · 18 min"`: draw an actual route. Get it from OSRM with full geometry: `router.project-osrm.org/route/v1/<driving|foot>/<lng>,<lat>;<lng>,<lat>?overview=full&geometries=geojson` → `routes[0].geometry.coordinates` are **[lng,lat]**: swap to [lat,lng]. Thin to ≤ 300 points. OSRM has no traffic: say so; use the commute skill's TomTom figure for the label when timing matters.
- Tapping a pin opens it in the maps app. Still give a clickable Maps link in text for anything they'll navigate to.
- Invalid JSON renders as a plain code block: check the numbers.

## Privacy

- Ask only when the task needs it; never on a timer except an explicit watcher.
- In replies prefer distances and place names over raw coordinates. Don't file the user's position to the notebook unless asked.
- The position goes to this chat's model like any message; nothing else stores it (the server keeps only the last fix, in memory).
