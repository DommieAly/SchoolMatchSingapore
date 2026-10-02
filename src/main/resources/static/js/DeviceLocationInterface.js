/*
 * Design class «boundary» DeviceLocationInterface (browser side, DC-39) — the device position, asked for only after
 * the user clicks "Use my current location" (NFR-SEC-06, FR-FILTER-04, FR-ROUTE-02, DC-11).
 *   requestPermission(): Promise<boolean>
 *   getCurrentPosition(): Promise<{latitude, longitude}>
 * The button [data-device-location] sits in a form (fragments/location-picker.html) with hidden latitude and
 * longitude inputs; on success the form posts to /location/device.
 * Browsers only allow geolocation on https or http://localhost.
 */
(function (global) {
  'use strict';

  /**
   * False when the browser has no geolocation, the page is not https/localhost (browsers then refuse it), or the
   * user blocked it for this site.
   */
  function requestPermission() {
    if (!('geolocation' in navigator) || global.isSecureContext === false) {
      return Promise.resolve(false);
    }
    if (!navigator.permissions || !navigator.permissions.query) {
      return Promise.resolve(true); // the browser asks during getCurrentPosition
    }
    return navigator.permissions.query({ name: 'geolocation' })
      .then(function (status) { return status.state !== 'denied'; })
      .catch(function () { return true; });
  }

  function getCurrentPosition() {
    return new Promise(function (resolve, reject) {
      navigator.geolocation.getCurrentPosition(
        function (position) {
          resolve({ latitude: position.coords.latitude, longitude: position.coords.longitude });
        },
        reject,
        { enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 });
    });
  }

  document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-device-location]');
    if (!button) {
      return;
    }
    var form = button.closest('form');
    var status = form.querySelector('[data-device-status]');
    button.disabled = true;
    if (status) {
      status.textContent = 'Asking your browser for your location…';
    }
    requestPermission()
      .then(function (allowed) {
        if (!allowed) {
          throw new Error('Location permission denied');
        }
        return getCurrentPosition();
      })
      .then(function (position) {
        form.elements.latitude.value = position.latitude;
        form.elements.longitude.value = position.longitude;
        if (status) {
          status.textContent = 'Using your location…';
        }
        form.submit();   // POST /location/device; the position is not kept anywhere else
      })
      .catch(function (error) {
        button.disabled = false;
        if (status) {
          status.textContent = error && error.code === 3
            ? 'Finding your location took too long. Try again, or type an address.'
            : 'Your location is not available. Allow location access in the browser, or type an address.';
        }
      });
  });

  global.DeviceLocationInterface = { requestPermission: requestPermission, getCurrentPosition: getCurrentPosition };
})(window);
