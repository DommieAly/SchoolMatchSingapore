/*
 * Reads what the page embedded in #map (fragments/map-canvas.html) and hands it to GoogleMapsPlatformInterface
 * (FR-MAP-01, FR-MAP-03, FR-MAP-06, FR-MAP-07, FR-FACMAP-01, FR-FACMAP-03, FR-ROUTE-07):
 *   data-markers — JSON array of sg.schoolmatch.boundary.ui.support.MapMarker → loadMap
 *   data-route   — encoded route polyline (directions page only) → drawRoute
 *   #district-layer checkbox (school map page only) → toggleDistrictLayer; the markers stay (FR-MAP-07)
 * Loaded only when the page can show an interactive map.
 */
document.addEventListener('DOMContentLoaded', function () {
  'use strict';
  var canvas = document.getElementById('map');
  var maps = window.GoogleMapsPlatformInterface;
  if (!canvas || !maps) {
    return;
  }
  var places = [];
  try {
    places = JSON.parse(canvas.dataset.markers || '[]');
  } catch (e) {
    places = [];
  }
  if (!maps.loadMap(places)) {
    return;
  }

  if (canvas.dataset.route) {
    maps.drawRoute(canvas.dataset.route);
  }

  var districtSwitch = document.getElementById('district-layer');
  var districtStatus = document.getElementById('district-status');
  if (districtSwitch) {
    districtSwitch.addEventListener('change', function () {
      var visible = districtSwitch.checked;
      districtSwitch.disabled = true;
      maps.toggleDistrictLayer(visible).then(function (ok) {
        districtSwitch.disabled = false;
        if (!ok && visible) {
          // UC View School's Districts EX-1: say so; the school map stays as it was.
          districtSwitch.checked = false;
          if (districtStatus) {
            districtStatus.textContent = 'Planning areas are not available right now.';
          }
        }
      });
    });
  }
});
