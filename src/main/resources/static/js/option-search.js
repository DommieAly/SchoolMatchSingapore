/*
 * Type-to-narrow box for long checkbox lists on the filter page (school-filter.html). With the full dataset the
 * programme list has over 800 values and the CCA list over 100. An input with data-option-search hides the options
 * of the list right after it (.filter-options) whose label does not contain the typed text (case-insensitive). Ticked options always
 * stay visible, so a hidden option is never submitted by surprise. Without JavaScript the full list still works.
 */
(function () {
  'use strict';

  function narrow(input) {
    var list = input.nextElementSibling;
    if (!list || !list.classList.contains('filter-options')) {
      return;
    }
    var text = input.value.trim().toLowerCase();
    var options = list.querySelectorAll('.form-check');
    for (var i = 0; i < options.length; i++) {
      var box = options[i].querySelector('input');
      var label = options[i].textContent.toLowerCase();
      var show = text === '' || label.indexOf(text) !== -1 || (box && box.checked);
      options[i].classList.toggle('d-none', !show);
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    var inputs = document.querySelectorAll('[data-option-search]');
    for (var i = 0; i < inputs.length; i++) {
      inputs[i].classList.remove('d-none');
      inputs[i].addEventListener('input', function (event) {
        narrow(event.target);
      });
    }
  });
})();
