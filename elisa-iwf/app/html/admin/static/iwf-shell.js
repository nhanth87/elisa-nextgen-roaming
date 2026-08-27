/* iwf-shell.js — IWF admin shell: theme toggle, nav active state, HTMX config */
(function () {
  'use strict';
  var root = document.documentElement;
  var toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      try { localStorage.setItem('iwf-theme', next); } catch (e) {}
    });
  }
  try {
    var stored = localStorage.getItem('iwf-theme');
    if (stored === 'light' || stored === 'dark') root.setAttribute('data-theme', stored);
  } catch (e) {}
})();
