/*
 * Loading overlay (DM 20 DirectionsLoading, DM 23 RecommendationLoading; displayLoadingIndicator / animateSpinner;
 * NFR-PERF-03). A form with a data-loading attribute shows the page's .loading-overlay
 * (fragments/loading.html) when it is submitted; so does a link with data-loading (e.g. the travel-mode links
 * on the directions page). data-loading="Some text" replaces the overlay message.
 */
(function () {
  'use strict';

  function setVisible(overlay, visible) {
    overlay.classList.toggle('d-none', !visible);
    overlay.setAttribute('aria-hidden', visible ? 'false' : 'true');
  }

  function show(source) {
    var overlay = document.querySelector('.loading-overlay');
    if (!overlay) {
      return;
    }
    var text = overlay.querySelector('[data-loading-text]');
    if (text && source.dataset.loading) {
      text.textContent = source.dataset.loading;
    }
    setVisible(overlay, true);
  }

  document.addEventListener('submit', function (event) {
    var form = event.target.closest('form[data-loading]');
    if (form && !event.defaultPrevented) {
      show(form);
    }
  });

  document.addEventListener('click', function (event) {
    var link = event.target.closest('a[data-loading]');
    // Not for a new tab or window (Ctrl/Cmd/Shift-click, middle click, target=_blank): this page stays.
    if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey
        || event.shiftKey || event.altKey || link.target === '_blank') {
      return;
    }
    show(link);
  });

  // Coming back with the Back button must not leave the spinner on screen.
  window.addEventListener('pageshow', function () {
    document.querySelectorAll('.loading-overlay').forEach(function (overlay) {
      setVisible(overlay, false);
    });
  });
})();
