/**
 * Declarative RoughNotation for MkDocs
 *
 * Standalone annotation (animates when scrolled into view):
 *   <span data-rn="highlight" data-rn-color="#00968855">text</span>
 *
 * Grouped annotations (animate sequentially when parent scrolls into view):
 *   <div data-rn-group>
 *     <span data-rn="highlight" data-rn-color="#ef535055">first</span>
 *     <span data-rn="box" data-rn-color="#ef5350">second</span>
 *   </div>
 *
 * Supported attributes:
 *   data-rn          - type: highlight, box, underline, circle,
 *                       strike-through, crossed-off, bracket
 *   data-rn-color    - color (default: current theme accent)
 *   data-rn-stroke   - strokeWidth (default: 2)
 *   data-rn-padding  - padding in px
 *   data-rn-duration - animation duration in ms (default: 600)
 *   data-rn-group    - place on a parent element to group child [data-rn] spans
 */
(function () {
  'use strict';

  var DEFAULTS = {
    color: '#009688',
    strokeWidth: 2,
    animationDuration: 600,
    multiline: true
  };

  var CODE_DEFAULTS = {
    type: 'highlight',
    color: '#00968830',
    strokeWidth: 1,
    animationDuration: 400,
    multiline: true,
    padding: 0
  };

  function parseOpts(el) {
    var opts = {
      type: el.dataset.rn,
      color: el.dataset.rnColor || DEFAULTS.color,
      strokeWidth: parseInt(el.dataset.rnStroke) || DEFAULTS.strokeWidth,
      animationDuration: parseInt(el.dataset.rnDuration) || DEFAULTS.animationDuration,
      multiline: DEFAULTS.multiline
    };
    if (el.dataset.rnPadding !== undefined) {
      opts.padding = parseInt(el.dataset.rnPadding);
    }
    return opts;
  }

  function observe(target, threshold, callback) {
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          callback();
          observer.disconnect();
        }
      });
    }, { threshold: threshold });
    observer.observe(target);
  }

  function init() {
    var RN = window.RoughNotation;
    if (!RN) return;

    // Grouped annotations: animate sequentially when parent scrolls into view
    document.querySelectorAll('[data-rn-group]').forEach(function (groupEl) {
      if (groupEl.dataset.rnInit) return;
      groupEl.dataset.rnInit = '1';

      var anns = [];
      groupEl.querySelectorAll('[data-rn]').forEach(function (el) {
        if (el.dataset.rnInit) return;
        el.dataset.rnInit = '1';
        anns.push(RN.annotate(el, parseOpts(el)));
      });

      if (!anns.length) return;
      var group = RN.annotationGroup(anns);
      observe(groupEl, 0.2, function () { group.show(); });
    });

    // Standalone annotations: animate individually on scroll
    document.querySelectorAll('[data-rn]').forEach(function (el) {
      if (el.dataset.rnInit) return;
      el.dataset.rnInit = '1';

      var ann = RN.annotate(el, parseOpts(el));
      observe(el, 0.5, function () { ann.show(); });
    });

    // Code block hl_lines -> RoughNotation highlights
    document.querySelectorAll('.highlight pre, pre').forEach(function (pre) {
      if (pre.dataset.rnCodeInit) return;
      var hlls = pre.querySelectorAll('.hll');
      if (!hlls.length) return;
      pre.dataset.rnCodeInit = '1';

      var anns = [];
      hlls.forEach(function (hll) {
        hll.style.backgroundColor = 'transparent';
        anns.push(RN.annotate(hll, {
          type: CODE_DEFAULTS.type,
          color: CODE_DEFAULTS.color,
          strokeWidth: CODE_DEFAULTS.strokeWidth,
          animationDuration: CODE_DEFAULTS.animationDuration,
          multiline: CODE_DEFAULTS.multiline,
          padding: CODE_DEFAULTS.padding
        }));
      });

      if (anns.length) {
        var group = RN.annotationGroup(anns);
        observe(pre, 0.1, function () { group.show(); });
      }
    });
  }

  // MkDocs Material instant loading support
  if (typeof document$ !== 'undefined') {
    document$.subscribe(function () { init(); });
  }

  // Initial page load
  if (document.readyState !== 'loading') {
    init();
  } else {
    document.addEventListener('DOMContentLoaded', init);
  }
})();
