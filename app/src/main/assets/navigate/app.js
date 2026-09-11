// Aegis Drive - Golden Master Premium Engine (OFFLINE-FIRST NAVIGATION OVERHAUL)
// 100% FREE stack: bundled MapLibre GL JS + OpenFreeMap tiles + public OSRM + native Geocoder.

// 🗺️ MAP ENGINE
// refreshExpiredTiles:false  → cached tiles are reused as-is instead of being re-validated over
//                              the network, so previously loaded areas stay visible offline
//                              (backed by the WebView LOAD_CACHE_ELSE_NETWORK HTTP cache).
// maxTileCacheSize           → generous in-memory tile cache for smooth pan/zoom on-device.
const map = new maplibregl.Map({
    container: 'map',
    style: 'https://tiles.openfreemap.org/styles/liberty',
    center: [73.0479, 33.6844],
    zoom: 14, pitch: 45, antialias: true, attributionControl: false,
    doubleClickZoom: false,
    refreshExpiredTiles: false,
    maxTileCacheSize: 2048
});

// 🚀 ENGINE STATE
let mapReady = false;            // style + sources are ready (guards every addSource/setData)
let userMarker = null;
let currentPos = [73.0479, 33.6844];
let currentHeading = 0;
let isNavigating = false;
let isCameraFollow = true;
let isEditMode = false;
let targetPos = null;
let targetName = null;
let lastRouteData = null;
let lastUserSnapPoint = null;
let poiMarkers = [];
let lastInteractionTime = 0;

const EMPTY_LINE = () => ({ type: 'Feature', geometry: { type: 'LineString', coordinates: [] } });
const OFFLINE_ROUTE_MSG = "Offline mode: Cannot calculate new routes without network. Please check connection.";

// 🚀 NAVIGATION BEAM (single DOM element, single Marker — never duplicated)
const beamEl = document.createElement('div');
beamEl.className = 'nav-beam';
beamEl.innerHTML = '<div class="nav-arrow"></div>';

function ensureUserMarker() {
    // Markers are DOM overlays: safe to create before the style loads. Guarding here kills the
    // old race where map.on('load') created a SECOND marker on the same element and the two
    // instances fought over its CSS transform (frozen / backward-pointing arrow).
    if (!userMarker) {
        userMarker = new maplibregl.Marker({ element: beamEl, rotationAlignment: 'map' })
            .setLngLat(currentPos).addTo(map);
    }
    return userMarker;
}

map.on('load', () => {
    mapReady = true;
    ensureUserMarker();
    if (!map.getSource('route')) {
        map.addSource('route', { type: 'geojson', data: EMPTY_LINE() });
        map.addLayer({ id: 'route', type: 'line', source: 'route', layout: { 'line-join': 'round', 'line-cap': 'round' }, paint: { 'line-color': '#38BDF8', 'line-width': 8, 'line-opacity': 0.9 } });
    }
    if (!map.getSource('route-dotted')) {
        map.addSource('route-dotted', { type: 'geojson', data: EMPTY_LINE() });
        map.addLayer({ id: 'route-dotted', type: 'line', source: 'route-dotted', paint: { 'line-color': '#38BDF8', 'line-width': 5, 'line-dasharray': [1, 2] } });
    }
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    // If a route was requested before the style finished loading, draw it now.
    if (lastRouteData) {
        map.getSource('route').setData(lastRouteData.geometry);
        syncDottedLine();
    }
});

/** Safely push data into a geojson source — no-ops until the style is ready (no crashes). */
function setSourceData(id, data) {
    if (!mapReady) return;
    const src = map.getSource(id);
    if (src) src.setData(data);
}

// 🛰️ CAMERA FOLLOW / MANUAL OVERRIDE
// Only USER gestures (e.originalEvent present) break follow mode. Programmatic flyTo/jumpTo used
// to fire zoomstart too, which disabled follow for 5s right after pressing Re-center — fixed.
function onUserGesture(e) {
    if (e && !e.originalEvent) return;
    lastInteractionTime = Date.now();
    isCameraFollow = false;
    if (!isEditMode) hideHomeMenu();
}
map.on('dragstart', onUserGesture);
map.on('zoomstart', onUserGesture);
map.on('rotatestart', onUserGesture);
map.on('pitchstart', onUserGesture);

// Resets follow mode after 5 seconds of inactivity
const checkInteractionTimeout = () => {
    if (!isCameraFollow && (Date.now() - lastInteractionTime > 5000)) {
        isCameraFollow = true;
    }
};

const recenterMap = () => {
    isCameraFollow = true;
    lastInteractionTime = 0;
    map.flyTo({ center: currentPos, zoom: 19, pitch: 65, bearing: currentHeading, duration: 1000 });
};

document.getElementById('btnRecenter').onclick = recenterMap;
document.getElementById('btnRecenterNav').onclick = recenterMap;

// 🏠 HOME MENU + EDIT-HOME MODE (was dead UI — now fully wired)
const homeMenu = document.getElementById('homeMenu');
const editOverlay = document.getElementById('editOverlay');
function hideHomeMenu() { if (homeMenu) homeMenu.classList.add('hidden'); }

document.getElementById('btnHome').onclick = () => {
    if (isEditMode) return;
    homeMenu.classList.toggle('hidden');
};
document.getElementById('menuNavigateHome').onclick = () => {
    hideHomeMenu();
    if (window.Android && window.Android.triggerNavigateHome) window.Android.triggerNavigateHome();
};
document.getElementById('menuEditHome').onclick = () => {
    hideHomeMenu();
    isEditMode = true;
    editOverlay.classList.remove('hidden');
};
document.getElementById('btnCancelEdit').onclick = () => {
    isEditMode = false;
    editOverlay.classList.add('hidden');
};
map.on('click', (e) => {
    if (isEditMode) {
        if (window.Android && window.Android.saveHomeLocation) {
            window.Android.saveHomeLocation(e.lngLat.lat, e.lngLat.lng);
        }
        isEditMode = false;
        editOverlay.classList.add('hidden');
        showCustomToast("🏠 Home location saved");
    } else {
        hideHomeMenu();
    }
});

// 🛡️ Bridge target for Kotlin's syncPassiveSafetyStatus() — was missing, causing a silent JS
// ReferenceError on every score sync. Stored for any UI that wants it.
window.updateSafetyScore = function(score) {
    window.__aegisSafetyScore = score;
};

// 🔍 SEARCH — free native Geocoder online, local Aegis cache offline
const etSearch = document.getElementById('etSearch');
const suggestionsList = document.getElementById('suggestions');

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function optimizeQuery(raw) {
    if (!raw) return "";
    let q = raw.toLowerCase().trim();
    q = q.replace(/\bnarayn bhagwan\b/g, "naran kaghan")
         .replace(/\bnaran khaghan\b/g, "naran kaghan")
         .replace(/\bmurre road\b/g, "murree road rawalpindi");

    const sectorRegex = /\b([a-i])[\s\/\-]*(\d{1,2})[\s\/\-]*([1-4])?\b/i;
    let match = q.match(sectorRegex);
    if (match) {
        let letter = match[1].toUpperCase();
        let sectorNum = match[2];
        let subSector = match[3] || "";
        if (parseInt(sectorNum) <= 18) {
            let formatted = `${letter}-${sectorNum}${subSector ? '/' + subSector : ''} Islamabad`;
            q = q.replace(match[0], formatted);
        }
    }
    if (!q.includes("pakistan") && !q.includes("islamabad") && !q.includes("lahore") && !q.includes("karachi") && !q.includes("rawalpindi")) {
        q += " Pakistan";
    }
    return q;
}

function isOnline() {
    try {
        if (window.Android && window.Android.isNetworkAvailable) return window.Android.isNetworkAvailable();
    } catch (e) { /* bridge gone (fragment destroyed) — treat as offline */ }
    return navigator.onLine;
}

/** Parse one "name|lat|lon" cache entry — crash-proof against malformed records. */
function parseCacheEntry(entry) {
    if (!entry) return null;
    const p = entry.split("|");
    if (p.length < 3) return null;
    const lat = parseFloat(p[1]), lon = parseFloat(p[2]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    return { display_name: p[0], lat: lat, lon: lon, isOffline: true };
}

/** Instant offline lookups from the local Aegis search cache (SharedPreferences-backed). */
function offlineMatches(query) {
    if (!window.Android || !window.Android.getSearchHistory) return [];
    let history = "";
    try { history = window.Android.getSearchHistory() || ""; } catch (e) { return []; }
    const q = (query || "").toLowerCase().trim();
    if (!q) return [];
    return history.split(";")
        .filter(h => h && h.toLowerCase().includes(q))
        .map(parseCacheEntry)
        .filter(Boolean)
        .slice(0, 6);
}

function performSearch(query) {
    if (!query) return;

    // Offline: resolve instantly from the local Aegis search cache — zero network, zero cost.
    if (!isOnline()) {
        const matches = offlineMatches(query);
        if (matches.length > 0) {
            showCustomToast("📶 Offline — found in local Aegis Cache");
            if (matches.length === 1) {
                handleDestinationSelected([matches[0].lon, matches[0].lat], matches[0].display_name);
            } else {
                renderSuggestions(matches);   // let the user pick among cached hits
            }
        } else {
            showCustomToast("❌ Destination not in local cache.");
        }
        return;
    }

    // Online: native Android Geocoder returns the exact best match; Kotlin then calls
    // handleDestinationSelected() to pinpoint it. Errors surface as a native Toast.
    if (window.Android && window.Android.geocodeSearch) {
        window.Android.geocodeSearch(optimizeQuery(query));
    }
}

// 🔍 FREE autocomplete: native Geocoder online (debounced 500ms on the native side),
// instant local-cache suggestions offline.
etSearch.oninput = (e) => {
    const rawVal = e.target.value;
    if (rawVal.length < 2) { suggestionsList.classList.add('hidden'); return; }

    if (!isOnline()) {
        renderSuggestions(offlineMatches(rawVal));
        return;
    }
    if (window.Android && window.Android.geocodeSuggest) {
        window.Android.geocodeSuggest(optimizeQuery(rawVal));
    }
};

// Callback target invoked by Kotlin with the top native Geocoder results (JSON array).
window.renderNativeSuggestions = function(data) {
    renderSuggestions(data);
};

function renderSuggestions(data) {
    suggestionsList.innerHTML = '';
    if (data && data.length > 0) {
        data.forEach(item => {
            if (!item || !item.display_name) return;
            const lat = parseFloat(item.lat), lon = parseFloat(item.lon);
            if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
            const div = document.createElement('div');
            div.className = 'suggestion-item';
            const name = String(item.display_name).split(',')[0];
            const addr = String(item.display_name);
            const icon = item.isOffline ? '💾' : '📍';
            div.innerHTML = `<div class="sugg-icon">${icon}</div><div class="sugg-text"><span class="sugg-name">${escapeHtml(name)}</span><span class="sugg-addr">${escapeHtml(addr)}</span></div>`;
            div.onclick = () => {
                etSearch.value = name;
                suggestionsList.classList.add('hidden');
                handleDestinationSelected([lon, lat], name);
            };
            suggestionsList.appendChild(div);
        });
        if (suggestionsList.children.length > 0) {
            suggestionsList.classList.remove('hidden');
            return;
        }
    }
    renderEmptyState();
}

function renderEmptyState() {
    suggestionsList.innerHTML = `
        <div class="suggestion-item empty-state">
            <div class="sugg-icon">🔍</div>
            <div class="sugg-text">
                <span class="sugg-name">Location not found</span>
                <span class="sugg-addr">Try a different term or check spelling</span>
            </div>
        </div>
    `;
    suggestionsList.classList.remove('hidden');
}

// Input Interactions
etSearch.onkeydown = (e) => { if (e.key === 'Enter') { performSearch(etSearch.value); suggestionsList.classList.add('hidden'); etSearch.blur(); } };
etSearch.addEventListener('search', () => { performSearch(etSearch.value); suggestionsList.classList.add('hidden'); });
document.getElementById('btnSearchSubmit').onclick = () => { performSearch(etSearch.value); suggestionsList.classList.add('hidden'); };
document.getElementById('btnVoice').onclick = () => { if (window.Android) window.Android.startVoiceRecognition(); };

window.onVoiceResult = (text) => {
    if (!text) return;
    // Pipe recognized speech into the search bar and show live suggestions automatically.
    etSearch.value = text;
    etSearch.focus();

    if (!isOnline()) {
        renderSuggestions(offlineMatches(text));
        return;
    }
    if (window.Android && window.Android.geocodeSuggest) {
        window.Android.geocodeSuggest(optimizeQuery(text));
    }
};

// 🗺️ ROUTING & CORE ENGINE (public OSRM — 100% free)
let routingAbortController = null;

function nativeToast(msg) {
    try {
        if (window.Android && window.Android.showNativeToast) { window.Android.showNativeToast(msg); return; }
    } catch (e) { /* fall through */ }
    showCustomToast(msg);
}

function handleDestinationSelected(pos, name) {
    if (!pos || pos.length < 2) return;
    targetPos = pos;
    targetName = name || "Destination";
    clearPOIs();
    const label = document.createElement('div');
    label.className = 'marker-bubble';
    label.innerText = `📍 ${targetName.split(',')[0]}`;
    const m = new maplibregl.Marker({ element: label }).setLngLat(pos).addTo(map);
    poiMarkers.push(m);
    document.getElementById('navStreet').innerText = targetName.split(',')[0];
    document.getElementById('routingCard').classList.remove('hidden');
    // 🎯 EXACT PINPOINT: tight street/building-level zoom on the precise coordinates.
    map.flyTo({ center: pos, zoom: 18, pitch: 0, duration: 1500 });
    if (window.Android && window.Android.saveSearchHistory) {
        window.Android.saveSearchHistory(targetName, pos[1], pos[0]);
    }
    // 🔋 Route is now active — keep the screen awake for driving.
    if (window.Android && window.Android.setKeepScreenOn) window.Android.setKeepScreenOn(true);
    planRoute(currentPos, pos);
}
window.handleDestinationSelected = handleDestinationSelected;

async function planRoute(start, dest) {
    // 🛡️ Guard against invalid coordinates so OSRM never receives bad input (no crashes).
    const valid = (c) => Array.isArray(c) && c.length >= 2 &&
        Number.isFinite(c[0]) && Number.isFinite(c[1]) &&
        Math.abs(c[0]) <= 180 && Math.abs(c[1]) <= 90 && !(c[0] === 0 && c[1] === 0);
    if (!valid(start)) {
        showCustomToast("📍 Waiting for your GPS location…");
        return;
    }
    if (!valid(dest)) return;

    if (!isOnline()) {
        nativeToast(OFFLINE_ROUTE_MSG);
        return;
    }

    if (routingAbortController) routingAbortController.abort();
    routingAbortController = new AbortController();

    try {
        // alternatives=false + continue_straight → OSRM returns exactly ONE optimal (fastest)
        // path with no alternate-route payload; geometries=geojson gives lossless coordinates.
        const url = `https://router.project-osrm.org/route/v1/driving/${start[0]},${start[1]};${dest[0]},${dest[1]}?alternatives=false&overview=full&geometries=geojson&steps=true&continue_straight=true`;
        const res = await fetch(url, { signal: routingAbortController.signal });
        if (!res.ok) { showCustomToast("❌ Route calculation failed."); return; }
        const data = await res.json();

        if (data.code === 'Ok' && data.routes && data.routes.length > 0) {
            lastRouteData = data.routes[0];
            prepareRouteProgress(lastRouteData);
            setSourceData('route', lastRouteData.geometry);
            lastUserSnapPoint = lastRouteData.geometry.coordinates[0];
            syncDottedLine();
            updateETADisplay(lastRouteData);
            updateRouteProgress(true);
            // NOTE: intentionally NOT auto-fitting bounds here. fitBounds() would zoom out to frame
            // the whole route and undo the exact destination pinpoint (zoom 18). The user can tap
            // "🛣️ Directions" (btnDirections) to frame the full route on demand.
        } else {
            showCustomToast("❌ Route calculation failed.");
        }
    } catch (e) {
        if (e.name === 'AbortError') return;
        // fetch throws TypeError when the network drops mid-request — same offline guidance.
        if (!isOnline() || e.name === 'TypeError') nativeToast(OFFLINE_ROUTE_MSG);
        else showCustomToast("❌ Routing error.");
    }
}

// ─────────────────────────────────────────────────────────────────────────
// 📐 LIVE ROUTE PROGRESS ENGINE
// Snaps the driver to the nearest route vertex (windowed, monotonic search), keeps the dotted
// line pointing FORWARD onto the route (it used to point back at the route START for the whole
// drive), updates remaining distance/ETA/next-turn live, detects off-route and re-plans.
// ─────────────────────────────────────────────────────────────────────────
let routeCoords = [];
let routeCumDist = [];       // meters from route start at each vertex
let routeSteps = [];
let stepEndDists = [];       // cumulative meters at the END of each step
let lastSnapIndex = 0;
let offRouteStrikes = 0;
let lastRerouteTime = 0;
let lastProgressTime = 0;

function haversine(a, b) {
    const R = 6371000, toRad = Math.PI / 180;
    const dLat = (b[1] - a[1]) * toRad, dLon = (b[0] - a[0]) * toRad;
    const s = Math.sin(dLat / 2) ** 2 +
        Math.cos(a[1] * toRad) * Math.cos(b[1] * toRad) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(s));
}

function prepareRouteProgress(route) {
    routeCoords = (route.geometry && route.geometry.coordinates) || [];
    routeCumDist = new Array(routeCoords.length).fill(0);
    for (let i = 1; i < routeCoords.length; i++) {
        routeCumDist[i] = routeCumDist[i - 1] + haversine(routeCoords[i - 1], routeCoords[i]);
    }
    routeSteps = (route.legs && route.legs[0] && route.legs[0].steps) || [];
    stepEndDists = [];
    let acc = 0;
    for (const s of routeSteps) { acc += (s.distance || 0); stepEndDists.push(acc); }
    lastSnapIndex = 0;
    offRouteStrikes = 0;
}

function updateRouteProgress(force) {
    if (!lastRouteData || routeCoords.length < 2) return;
    const now = Date.now();
    if (!force && now - lastProgressTime < 1000) return;   // 1Hz is plenty for UI math
    lastProgressTime = now;

    // Windowed nearest-vertex search (drivers move forward; a small back-window absorbs GPS noise)
    const from = Math.max(0, lastSnapIndex - 5);
    const to = Math.min(routeCoords.length - 1, lastSnapIndex + 80);
    let bestI = lastSnapIndex, bestD = Infinity;
    for (let i = from; i <= to; i++) {
        const d = haversine(currentPos, routeCoords[i]);
        if (d < bestD) { bestD = d; bestI = i; }
    }
    // If the window lost the driver (looped road, big jump), fall back to a full scan once.
    if (bestD > 120) {
        for (let i = 0; i < routeCoords.length; i++) {
            const d = haversine(currentPos, routeCoords[i]);
            if (d < bestD) { bestD = d; bestI = i; }
        }
    }
    lastSnapIndex = bestI;
    lastUserSnapPoint = routeCoords[bestI];
    syncDottedLine();

    if (!isNavigating) return;

    const total = lastRouteData.distance || routeCumDist[routeCumDist.length - 1];
    const progress = routeCumDist[bestI];
    const remaining = Math.max(0, total - progress);
    const remainingSec = total > 0 ? (lastRouteData.duration || 0) * (remaining / total) : 0;

    // 🏁 Arrival
    if (remaining < 25 && bestI >= routeCoords.length - 3) {
        showCustomToast("🏁 You have arrived!");
        stopNavigation();
        return;
    }

    renderNavStats(remaining, remainingSec);
    renderNextTurn(progress, remaining);

    // 🔄 Off-route detection: 3 consecutive strikes >60m from the route → re-plan (throttled
    // to 15s so the free public OSRM server is never hammered).
    if (bestD > 60) {
        offRouteStrikes++;
        if (offRouteStrikes >= 3 && targetPos && isOnline() && now - lastRerouteTime > 15000) {
            lastRerouteTime = now;
            offRouteStrikes = 0;
            showCustomToast("🔄 Rerouting…");
            planRoute(currentPos, targetPos);
        }
    } else {
        offRouteStrikes = 0;
    }
}

function fmtDist(m) { return m < 1000 ? Math.round(m) + " m" : (m / 1000).toFixed(1) + " km"; }

function renderNavStats(remaining, remainingSec) {
    const timeMin = Math.max(1, Math.round(remainingSec / 60));
    const distText = fmtDist(remaining);
    const elTimeCount = document.getElementById('navTimeCountdown');
    const elDistPrecise = document.getElementById('navDistancePrecise');
    const elArrival = document.getElementById('navArrivalTime');
    if (elTimeCount) elTimeCount.innerText = timeMin + " min";
    if (elDistPrecise) elDistPrecise.innerText = distText;
    if (elArrival) {
        const arrival = new Date();
        arrival.setSeconds(arrival.getSeconds() + remainingSec);
        elArrival.innerText = arrival.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }
}

function renderNextTurn(progress, remaining) {
    if (!routeSteps.length) return;
    // Find the step the driver is currently inside…
    let k = 0;
    while (k < routeSteps.length - 1 && stepEndDists[k] <= progress) k++;
    const elNextStreet = document.getElementById('nextStreetName');
    const elDistTurn = document.getElementById('distanceToTurn');
    const elTurnIcon = document.getElementById('nextTurnIcon');
    // …the upcoming maneuver is the one that STARTS the next step.
    const next = routeSteps[k + 1];
    if (next) {
        const distToTurn = Math.max(0, stepEndDists[k] - progress);
        if (elNextStreet) elNextStreet.innerText = next.name || "Toward Destination";
        if (elDistTurn) elDistTurn.innerText = "In " + fmtDist(distToTurn);
        if (elTurnIcon) elTurnIcon.innerText = getManeuverIcon(next.maneuver && next.maneuver.modifier);
    } else {
        if (elNextStreet) elNextStreet.innerText = "Arrive at destination";
        if (elDistTurn) elDistTurn.innerText = "In " + fmtDist(remaining);
        if (elTurnIcon) elTurnIcon.innerText = '🏁';
    }
}

function syncDottedLine() {
    if (!lastUserSnapPoint || !currentPos) return;
    setSourceData('route-dotted', { type: 'Feature', geometry: { type: 'LineString', coordinates: [currentPos, lastUserSnapPoint] } });
}

function updateETADisplay(r = null) {
    const route = r || lastRouteData;
    if (!route) return;

    const distKm = route.distance / 1000;
    const timeMin = Math.round(route.duration / 60);
    const distText = distKm < 1.0 ? Math.round(route.distance) + " m" : distKm.toFixed(1) + " km";

    const elDist = document.getElementById('navDistance');
    const elTime = document.getElementById('navTime');
    if (elDist) elDist.innerText = distText;
    if (elTime) elTime.innerText = timeMin + " min";
}

function getManeuverIcon(m) {
    switch(m) {
        case 'left': return '⬅️'; case 'right': return '➡️'; case 'slight left': return '↖️'; case 'slight right': return '↗️';
        case 'sharp left': return '↩️'; case 'sharp right': return '↪️'; case 'uturn': return '🔄'; default: return '⬆️';
    }
}

// 🗺️ NAVIGATION SYSTEM WINDOWS
let mapInitialized = false;

window.initializeMapCenter = function(lat, lng) {
    if (!mapInitialized && Number.isFinite(lat) && Number.isFinite(lng)) {
        currentPos = [lng, lat];
        ensureUserMarker().setLngLat(currentPos);
        map.jumpTo({ center: currentPos, zoom: 15 });
        mapInitialized = true;
    }
};

window.updateRealTimeTracking = function(lat, lng, heading) {
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
    currentPos = [lng, lat];
    if (Number.isFinite(heading)) currentHeading = heading;

    const marker = ensureUserMarker();
    marker.setLngLat(currentPos);
    marker.setRotation(currentHeading);

    updateRouteProgress(false);   // snap + dotted line + live nav stats (self-throttled to 1Hz)
    if (!lastRouteData) syncDottedLine();
    checkInteractionTimeout();

    // 🚀 SMOOTH CAMERA LERP (only while navigating with follow active)
    if (isNavigating && isCameraFollow) {
        map.easeTo({
            center: currentPos,
            bearing: currentHeading,
            duration: 800,
            easing: (t) => t
        });
    }
};

// 🚀 NAVIGATION ACTIONS
document.getElementById('btnStartDrive').onclick = () => {
    isNavigating = true; isCameraFollow = true;
    document.getElementById('searchContainer').classList.add('hidden');
    document.getElementById('routingCard').classList.add('hidden');
    document.getElementById('navDashboard').classList.remove('hidden');
    map.jumpTo({ center: currentPos, zoom: 19, pitch: 65, bearing: currentHeading });
    updateETADisplay();
    updateRouteProgress(true);
};

document.getElementById('btnCloseRouting').onclick = stopNavigation;
document.getElementById('btnStopNav').onclick = stopNavigation;
document.getElementById('btnDirections').onclick = () => { if (targetPos) { const b = [currentPos, targetPos].reduce((acc, c) => acc.extend(c), new maplibregl.LngLatBounds(currentPos, currentPos)); map.fitBounds(b, { padding: 100, duration: 1500 }); } };

function stopNavigation() {
    isNavigating = false; targetPos = null; targetName = null; clearPOIs();
    lastRouteData = null; lastUserSnapPoint = null;
    routeCoords = []; routeCumDist = []; routeSteps = []; stepEndDists = [];
    lastSnapIndex = 0; offRouteStrikes = 0;
    if (routingAbortController) routingAbortController.abort();
    // 🔋 Route ended — allow the screen to sleep normally again.
    if (window.Android && window.Android.setKeepScreenOn) window.Android.setKeepScreenOn(false);
    setSourceData('route', EMPTY_LINE());
    setSourceData('route-dotted', EMPTY_LINE());
    document.getElementById('navDashboard').classList.add('hidden');
    document.getElementById('routingCard').classList.add('hidden');
    document.getElementById('searchContainer').classList.remove('hidden');
    map.easeTo({ pitch: 45, zoom: 14, bearing: 0, duration: 1000 });
}

function clearPOIs() { poiMarkers.forEach(m => m.remove()); poiMarkers = []; }
function showCustomToast(msg) { const t = document.getElementById('customToast'); if (!t) return; t.innerText = msg; t.classList.add('visible'); setTimeout(() => t.classList.remove('visible'), 3000); }
