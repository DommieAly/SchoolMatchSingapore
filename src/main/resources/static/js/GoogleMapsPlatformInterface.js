/*
 * Design class «boundary» GoogleMapsPlatformInterface (browser side, DC-39) — loadMap(places): Boolean
 * (UC Display Interactive Map; FR-MAP-02, FR-MAP-04, FR-MAP-05, FR-MAP-06, FR-MAP-07, FR-FACMAP-02, FR-ROUTE-07).
 * DC-16: the server decides what to show; this file only draws.
 *
 *   loadMap(places)              → true when the map is being drawn; false (and "Map unavailable") otherwise
 *   toggleDistrictLayer(visible) → Promise<boolean>: shows/hides the planning areas from GET /api/districts;
 *                                  the markers stay (FR-MAP-07). false when the districts cannot be loaded.
 *   drawRoute(encodedPolyline)   → Promise<boolean>: draws a route line (Routes API encoded polyline)
 *
 * The browser key and optional Map ID come from data attributes on #map (fragments/map-canvas.html). The key is
 * never logged. Checked against Google's documentation on 2026-10-02:
 *   - loader: https://maps.googleapis.com/maps/api/js?key=…&v=weekly&loading=async&libraries=marker,geometry&callback=…
 *     then google.maps.importLibrary('maps' | 'marker' | 'core' | 'geometry')
 *   - with a Map ID: AdvancedMarkerElement({map, position, title, gmpClickable: true, content: PinElement}) and the
 *     'gmp-click' event (PinElement options background, borderColor, glyphColor, glyphText);
 *     without a Map ID, or when Google refuses the advanced marker: the classic google.maps.Marker (deprecated
 *     since Feb 2024 but still available)
 *   - Data layer: map.data.addGeoJson(json), map.data.setStyle({visible, fillColor, …}), feature.getProperty(name)
 *   - geometry library: encoding.decodePath(encoded)
 *   - window.gm_authFailure() is called by Google when the key is rejected
 */
(function (global) {
  'use strict';

  var SINGAPORE = { lat: 1.3521, lng: 103.8198 };

  /** Pin colour and letter per MapMarker.kind; facility types can be told apart (UC View Facilities on Map). */
  var PINS = {
    school: { color: '#0d6efd', glyph: 'S' },
    library: { color: '#198754', glyph: 'L' },
    'tuition-centre': { color: '#fd7e14', glyph: 'T' },
    start: { color: '#dc3545', glyph: 'A' }
  };

  var DISTRICT_STYLE = {
    fillColor: '#6f42c1', fillOpacity: 0.08, strokeColor: '#6f42c1', strokeOpacity: 0.7, strokeWeight: 1.5,
    clickable: true, zIndex: 0
  };

  var loader = null;     // Promise of the Maps JavaScript API script
  var mapReady = null;   // Promise of the google.maps.Map once loadMap has drawn it
  var districtsLoaded = null;   // Promise of true/false once /api/districts has been added

  function showUnavailable() {
    var canvas = document.getElementById('map');
    var box = document.getElementById('map-unavailable');
    if (canvas) {
      canvas.classList.add('d-none');
    }
    if (box) {
      box.classList.remove('d-none');
    }
    var districtSwitch = document.getElementById('district-layer');   // school map page only
    if (districtSwitch) {
      districtSwitch.checked = false;
      districtSwitch.disabled = true;
      var status = document.getElementById('district-status');
      if (status) {
        status.textContent = 'Needs the interactive map.';
      }
    }
    return false;
  }

  // Google calls this when the key is not valid for this site (UC Display Interactive Map EX-1).
  global.gm_authFailure = showUnavailable;

  /** Loads the Maps JavaScript API once. */
  function loadScript(key) {
    if (global.google && global.google.maps && global.google.maps.importLibrary) {
      return Promise.resolve();
    }
    if (!loader) {
      loader = new Promise(function (resolve, reject) {
        global.__schoolMatchMapsReady = resolve;
        var script = document.createElement('script');
        script.src = 'https://maps.googleapis.com/maps/api/js?key=' + encodeURIComponent(key)
          + '&v=weekly&loading=async&libraries=marker,geometry&callback=__schoolMatchMapsReady';
        script.async = true;
        script.onerror = function () { reject(new Error('Maps JavaScript API did not load')); };
        document.head.appendChild(script);
      });
    }
    return loader;
  }

  /** Info window content built with DOM nodes (never innerHTML), so data cannot inject markup. */
  function infoContent(place) {
    var box = document.createElement('div');
    var title;
    if (place.href) {
      title = document.createElement('a');
      title.href = place.href;
    } else {
      title = document.createElement('div');
    }
    title.textContent = place.name;
    title.className = 'fw-semibold';
    box.appendChild(title);
    if (place.address) {
      var address = document.createElement('div');
      address.textContent = place.address;
      box.appendChild(address);
    }
    return box;
  }

  /** An AdvancedMarkerElement (needs a Map ID); null when Google refuses it (e.g. the Map ID is not valid). */
  function addAdvancedMarker(markerLib, map, info, place, pin, position) {
    try {
      var pinElement = new markerLib.PinElement({
        background: pin.color, borderColor: '#ffffff', glyphColor: '#ffffff', glyphText: pin.glyph
      });
      var marker = new markerLib.AdvancedMarkerElement({
        map: map, position: position, title: place.name, gmpClickable: true,
        content: pinElement instanceof HTMLElement ? pinElement : pinElement.element
      });
      marker.addEventListener('gmp-click', function () {
        info.setContent(infoContent(place));
        info.open({ map: map, anchor: marker });
      });
      return marker;
    } catch (e) {
      return null;
    }
  }

  function addMarker(markerLib, map, info, place, useAdvanced) {
    var pin = PINS[place.kind] || PINS.school;
    var position = { lat: place.lat, lng: place.lng };
    var marker = useAdvanced ? addAdvancedMarker(markerLib, map, info, place, pin, position) : null;
    if (!marker) {   // no Map ID, or the advanced marker failed: the classic marker always works
      marker = new markerLib.Marker({
        map: map, position: position, title: place.name,
        label: { text: pin.glyph, color: '#ffffff', fontWeight: '600' },
        icon: {
          path: google.maps.SymbolPath.CIRCLE, scale: 11, fillColor: pin.color, fillOpacity: 1,
          strokeColor: '#ffffff', strokeWeight: 2
        }
      });
      marker.addListener('click', function () {
        info.setContent(infoContent(place));
        info.open({ map: map, anchor: marker });
      });
    }
    return marker;
  }

  async function draw(canvas, places) {
    var mapId = canvas.dataset.mapId;
    var maps = await google.maps.importLibrary('maps');
    var markerLib = await google.maps.importLibrary('marker');
    var core = await google.maps.importLibrary('core');
    var map = new maps.Map(canvas, {
      center: SINGAPORE, zoom: 11, mapId: mapId || undefined,
      streetViewControl: false, mapTypeControl: false
    });
    var info = new maps.InfoWindow();
    var bounds = new core.LatLngBounds();

    places.forEach(function (place) {
      addMarker(markerLib, map, info, place, Boolean(mapId));
      bounds.extend({ lat: place.lat, lng: place.lng });
    });

    if (places.length > 1) {
      map.fitBounds(bounds, 48);
    } else {
      map.setCenter({ lat: places[0].lat, lng: places[0].lng });
      map.setZoom(15);
    }
    return map;
  }

  /**
   * Draws the places as markers. Returns false (and shows "Map unavailable") when there is no map element,
   * no browser key or no place; the list on the page still works. A failure while loading also shows
   * "Map unavailable", so no half-drawn map is left on the page (UC Display Interactive Map EX-2).
   */
  function loadMap(places) {
    var canvas = document.getElementById('map');
    var key = canvas ? canvas.dataset.mapsKey : null;
    if (!canvas || !key || !Array.isArray(places) || places.length === 0) {
      return showUnavailable();
    }
    mapReady = loadScript(key).then(function () { return draw(canvas, places); });
    mapReady.catch(showUnavailable);
    return true;
  }

  /** Adds /api/districts to the map's data layer once; true when it worked. */
  function loadDistricts(map) {
    if (!districtsLoaded) {
      districtsLoaded = fetch('/api/districts', { headers: { Accept: 'application/geo+json' } })
        .then(function (response) {
          if (!response.ok) {
            throw new Error('HTTP ' + response.status);
          }
          return response.json();
        })
        .then(function (geoJson) {
          map.data.addGeoJson(geoJson);
          map.data.addListener('mouseover', function (event) {
            map.data.overrideStyle(event.feature, { fillOpacity: 0.2, strokeWeight: 2.5 });
            announceDistrict(event.feature.getProperty('name'));
          });
          map.data.addListener('mouseout', function () {
            map.data.revertStyle();
          });
          map.data.addListener('click', function (event) {   // touch screens have no hover
            announceDistrict(event.feature.getProperty('name'));
          });
          return true;
        })
        .catch(function () {
          districtsLoaded = null;   // allow another try later
          return false;
        });
    }
    return districtsLoaded;
  }

  function announceDistrict(name) {
    var status = document.getElementById('district-status');
    if (status) {
      status.textContent = name ? 'Planning area: ' + name : 'Planning area: not available';
    }
  }

  /**
   * View School's Districts (FR-MAP-06, FR-MAP-07): shows or hides the planning areas. Only the data layer
   * changes, so the school markers stay. Resolves false when there is no map or the districts cannot be loaded
   * (EX-1: the map stays as it was).
   */
  function toggleDistrictLayer(visible) {
    if (!mapReady) {
      return Promise.resolve(false);
    }
    return mapReady.then(function (map) {
      if (!visible) {
        map.data.setStyle({ visible: false });
        return true;
      }
      return loadDistricts(map).then(function (loaded) {
        if (loaded) {
          map.data.setStyle(DISTRICT_STYLE);
        }
        return loaded;
      });
    }).catch(function () { return false; });
  }

  /** Draws the route line (FR-ROUTE-07, DC-05) and zooms to it. Resolves false when it cannot. */
  function drawRoute(encodedPolyline) {
    if (!mapReady || !encodedPolyline) {
      return Promise.resolve(false);
    }
    return mapReady.then(async function (map) {
      var geometry = await google.maps.importLibrary('geometry');
      var maps = await google.maps.importLibrary('maps');
      var core = await google.maps.importLibrary('core');
      var path = geometry.encoding.decodePath(encodedPolyline);
      if (!path || path.length < 2) {
        return false;
      }
      new maps.Polyline({ map: map, path: path, strokeColor: '#0d6efd', strokeOpacity: 0.85, strokeWeight: 5 });
      var bounds = new core.LatLngBounds();
      path.forEach(function (point) { bounds.extend(point); });
      map.fitBounds(bounds, 48);
      return true;
    }).catch(function () { return false; });
  }

  global.GoogleMapsPlatformInterface = {
    loadMap: loadMap,
    toggleDistrictLayer: toggleDistrictLayer,
    drawRoute: drawRoute
  };
})(window);
