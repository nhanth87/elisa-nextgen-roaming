/* Nextgen STP admin shell — theme + CSRF double-submit + HTMX auth forwarding.
 * Shares the theme (admin.css) with the GMLC admin but keeps STP-local
 * cookie/header names (see SignedSessionCookie in the STP admin package).
 */
(function () {
  'use strict';

  var THEME_KEY = 'stp-theme';
  var CSRF_COOKIE = 'stp_admin_csrf';
  var CSRF_HEADER = 'X-STP-CSRF';

  function csrf() {
    try {
      var hit = document.cookie
        .split(';')
        .map(function (c) { return c.trim(); })
        .find(function (c) { return c.indexOf(CSRF_COOKIE + '=') === 0; });
      return hit ? decodeURIComponent(hit.substring(CSRF_COOKIE.length + 1)) : '';
    } catch (e) {
      return '';
    }
  }

  function applyTheme(t) {
    if (t !== 'light' && t !== 'dark') return;
    document.documentElement.setAttribute('data-theme', t);
    try { localStorage.setItem(THEME_KEY, t); } catch (e) { /* ignore */ }
  }

  function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    applyTheme(cur);
  }

  /* Echo the non-HttpOnly session-derived CSRF cookie into hidden _csrf fields. */
  function fillCsrf() {
    var v = csrf();
    if (!v) return;
    document.querySelectorAll('input[name="_csrf"]').forEach(function (el) { el.value = v; });
  }

  document.addEventListener('DOMContentLoaded', fillCsrf);

  /* HTMX polls/forms inherit CSRF for state-changing verbs. */
  document.addEventListener('htmx:configRequest', function (ev) {
    var verb = (ev.detail && ev.detail.verb ? ev.detail.verb : '').toUpperCase();
    if (verb !== 'GET' && verb !== 'HEAD') ev.detail.headers[CSRF_HEADER] = csrf();
  });

  window.StpAdmin = { csrf: csrf, applyTheme: applyTheme, toggleTheme: toggleTheme };
})();