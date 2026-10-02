// Keeps an unsent form when the user sets a starting point (location picker on the same page).
// The picker's forms post to /location… and come back to their hidden "returnTo" URL. A form marked
// data-draft-return="/path" is copied into that URL (as a query string) whenever the user changes it, so the page
// comes back with the user's choices instead of an empty form. Without JavaScript the server's returnTo is used.
(function () {
    'use strict';
    var MAX_URL_LENGTH = 1900;   // the server accepts local paths up to 2000 characters (AuthInterceptor)

    function draftUrl(form) {
        var params = new URLSearchParams();
        new FormData(form).forEach(function (value, name) {
            // skip empty fields and Thymeleaf's "_name" checkbox markers
            if (typeof value === 'string' && value !== '' && name.charAt(0) !== '_') {
                params.append(name, value);
            }
        });
        var query = params.toString();
        return form.getAttribute('data-draft-return') + (query ? '?' + query : '');
    }

    function update(form) {
        var url = draftUrl(form);
        if (url.length > MAX_URL_LENGTH) {
            return;   // too long to carry: keep the server's returnTo
        }
        document.querySelectorAll('.location-picker input[name="returnTo"]').forEach(function (input) {
            input.value = url;
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.querySelector('form[data-draft-return]');
        if (!form) {
            return;
        }
        form.addEventListener('input', function () { update(form); });
        form.addEventListener('change', function () { update(form); });
    });
})();
