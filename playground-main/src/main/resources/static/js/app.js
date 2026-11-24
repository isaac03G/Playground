/*
 * app.js — Mejoras de interfaz de Playground.
 *
 * Es una MEJORA PROGRESIVA: no cambia el contenido ni los colores, solo agrega movimiento.
 * Si este archivo falla o el navegador no soporta algo, la página funciona igual que antes.
 * Sin dependencias. Los estilos que usa están al final de /css/styles.css.
 *
 *   - barra de progreso de lectura y barra de carga al cambiar de página
 *   - barra de navegación que se compacta al bajar, y enlace de la página actual resaltado
 *   - elementos que aparecen al hacer scroll (con escalonado) y contadores que suben de 0
 *   - tarjetas con foco de luz que sigue al mouse e inclinación 3D sutil (solo con mouse)
 *   - portada con partículas y brillo que sigue al mouse
 *   - imágenes: esqueleto de carga y letra de respaldo si la portada no carga
 *   - filtros del buscador que se aplican al cambiar un desplegable
 *   - "hace 3 horas" para <time class="rel-time">, botón de subir, avisos que se desvanecen
 *
 * Todo el movimiento se omite si el sistema pide "reducir movimiento".
 */
(function () {
  'use strict';

  var doc = document;
  var root = doc.documentElement;
  var reduced = !!(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches);
  var finePointer = !!(window.matchMedia && matchMedia('(hover: hover) and (pointer: fine)').matches);

  // Idioma de la página: lo pone el servidor en <html lang="..."> (cookie "idioma"; ver IdiomaLocaleResolver).
  var LANG = ((root.getAttribute('lang') || 'es').slice(0, 2) === 'en') ? 'en' : 'es';
  var TXT = {
    es: { arriba: 'Volver arriba', ahora: 'justo ahora', locale: 'es-CO' },
    en: { arriba: 'Back to top',   ahora: 'just now',    locale: 'en-US' }
  }[LANG];

  function qsa(sel, ctx) { return Array.prototype.slice.call((ctx || doc).querySelectorAll(sel)); }
  function make(tag, cls, text) {
    var n = doc.createElement(tag);
    if (cls) n.className = cls;
    if (text) n.textContent = text;
    return n;
  }
  // Cada módulo corre aislado: si uno falla, los demás siguen funcionando.
  function safe(fn) {
    try { fn(); } catch (e) { if (window.console) console.warn('[app.js]', e); }
  }

  /* ---------------------------------------------------------- navegación */
  function navbar() {
    var nav = qsa('.navbar')[0];
    if (!nav) return;
    function update() { nav.classList.toggle('scrolled', window.scrollY > 8); }
    update();
    addEventListener('scroll', update, { passive: true });

    var aqui = location.pathname.replace(/\/+$/, '') || '/';
    qsa('.nav-links a[href]', nav).forEach(function (a) {
      if (a.hash) return;                                   // enlaces a secciones (#accion...) no cuentan
      var p = a.pathname.replace(/\/+$/, '') || '/';
      var inicio = (p === '/' || p === '/dashboard') && (aqui === '/' || aqui === '/dashboard');
      if (p === aqui || inicio) a.classList.add('active');
    });
  }

  function progress() {
    var bar = make('div', 'scroll-progress');
    bar.setAttribute('aria-hidden', 'true');
    doc.body.appendChild(bar);
    var pendiente = false;
    function dibujar() {
      var alto = root.scrollHeight - innerHeight;
      bar.style.transform = 'scaleX(' + (alto > 0 ? Math.min(1, scrollY / alto) : 0) + ')';
      pendiente = false;
    }
    addEventListener('scroll', function () {
      if (!pendiente) { pendiente = true; requestAnimationFrame(dibujar); }
    }, { passive: true });
    addEventListener('resize', dibujar);
    dibujar();
  }

  function navLoader() {
    doc.body.appendChild(make('div', 'nav-loader'));
    function iniciar() {
      root.classList.add('is-navigating');
      setTimeout(function () { root.classList.remove('is-navigating'); }, 15000);   // por si no hubo navegación (descarga...)
    }
    doc.addEventListener('click', function (e) {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      var a = e.target.closest && e.target.closest('a[href]');
      if (!a || a.target === '_blank' || a.hasAttribute('download') || a.origin !== location.origin) return;
      if (a.pathname === location.pathname && a.search === location.search) return;   // ancla en la misma página
      iniciar();
    });
    doc.addEventListener('submit', function (e) { if (!e.defaultPrevented) iniciar(); });
    addEventListener('pageshow', function (e) { if (e.persisted) root.classList.remove('is-navigating'); });
  }

  function toTop() {
    var b = make('button', 'to-top', '↑');
    b.type = 'button';
    b.title = TXT.arriba;
    b.setAttribute('aria-label', TXT.arriba);
    b.addEventListener('click', function () { scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' }); });
    doc.body.appendChild(b);
    function f() { b.classList.toggle('show', window.scrollY > 700); }
    f();
    addEventListener('scroll', f, { passive: true });
  }

  /* --------------------------------------------------- aparecer al scroll */
  var REVEAL = [
    '.hero-content > *', '.section-header', '.card', '.wish-card',
    '.stat-card', '.top-juegos', '.perfil-card', '.pedido-card',
    '.detalle-cover', '.detalle-info > *', '.precio-plataforma',
    '.relacionados-section > h3', '.error-page > *', '.admin-wrap .section-title'
  ].join(',');

  function reveal() {
    if (reduced || !('IntersectionObserver' in window)) return;
    var nodos = qsa(REVEAL);
    if (!nodos.length) return;

    var io = new IntersectionObserver(function (entradas) {
      var k = 0;                                            // escalonado entre los que aparecen a la vez
      entradas.forEach(function (en) {
        if (!en.isIntersecting) return;
        var n = en.target;
        io.unobserve(n);
        n.style.setProperty('--i', Math.min(k++, 8));
        n.classList.add('in');
        var limpiado = false;
        function limpiar() {                                // al terminar, la tarjeta recupera sus transiciones propias (hover)
          if (limpiado) return;
          limpiado = true;
          n.classList.remove('reveal', 'in');
          n.style.removeProperty('--i');
        }
        n.addEventListener('transitionend', function (ev) {
          if (ev.target === n && ev.propertyName === 'opacity') limpiar();
        });
        setTimeout(limpiar, 1800);                          // red de seguridad
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -5% 0px' });

    nodos.forEach(function (n) { n.classList.add('reveal'); io.observe(n); });
  }

  function animar(nodo, destino, ms) {
    var fmt = new Intl.NumberFormat('es-CO');
    var t0 = performance.now();
    (function cuadro(t) {
      var p = Math.min(1, (t - t0) / ms);
      nodo.textContent = fmt.format(Math.round(destino * (1 - Math.pow(1 - p, 3))));
      if (p < 1) requestAnimationFrame(cuadro);
    })(t0);
  }

  function counters() {
    if (reduced || !('IntersectionObserver' in window)) return;
    var items = [];
    qsa('[data-count], .stat-valor').forEach(function (n) {
      var v = n.hasAttribute('data-count') ? n.getAttribute('data-count') : n.textContent.trim();
      if (!/^\d+$/.test(v)) return;
      items.push({ nodo: n, valor: parseInt(v, 10) });
    });
    if (!items.length) return;
    var io = new IntersectionObserver(function (entradas) {
      entradas.forEach(function (en) {
        if (!en.isIntersecting) return;
        io.unobserve(en.target);
        var it = items.filter(function (x) { return x.nodo === en.target; })[0];
        if (it) animar(it.nodo, it.valor, 1100);
      });
    }, { threshold: 0.6 });
    items.forEach(function (it) { it.nodo.textContent = '0'; io.observe(it.nodo); });
  }

  /* ------------------------------------------------------------ tarjetas */
  function tilt() {
    if (!finePointer) return;
    var FOCO = '.card, .wish-card, .stat-card, .perfil-card';
    var INCLINA = '.card, .wish-card';
    var raf = 0, ultimo = null;

    function aplicar() {
      raf = 0;
      if (!ultimo) return;
      var c = ultimo.card, r = c.getBoundingClientRect();
      var x = ultimo.x - r.left, y = ultimo.y - r.top;
      c.style.setProperty('--cx', x + 'px');
      c.style.setProperty('--cy', y + 'px');
      if (!reduced && c.matches(INCLINA)) {
        c.style.setProperty('--ry', ((x / r.width - 0.5) * 10).toFixed(2) + 'deg');
        c.style.setProperty('--rx', ((0.5 - y / r.height) * 8).toFixed(2) + 'deg');
      }
    }
    doc.addEventListener('pointermove', function (e) {
      if (e.pointerType && e.pointerType !== 'mouse') return;
      var c = e.target.closest && e.target.closest(FOCO);
      if (!c) return;
      ultimo = { card: c, x: e.clientX, y: e.clientY };
      if (!raf) raf = requestAnimationFrame(aplicar);
    }, { passive: true });
    doc.addEventListener('pointerout', function (e) {
      var c = e.target.closest && e.target.closest(FOCO);
      if (!c || c.contains(e.relatedTarget)) return;
      c.style.removeProperty('--rx');
      c.style.removeProperty('--ry');
    });
  }

  function images() {
    qsa('.card-img, .detalle-cover').forEach(function (caja) {
      var img = caja.querySelector('img');
      if (!img && caja.querySelector('.detalle-cover-placeholder')) return;   // la ficha ya trae su letra de respaldo

      var tarjeta = caja.closest('.card, .wish-card');
      var titulo = tarjeta && tarjeta.querySelector('h3');
      var nombre = (img && img.alt) || (titulo && titulo.textContent) || (qsa('.detalle-nombre')[0] || {}).textContent || '';
      var letra = (nombre.trim().charAt(0) || '🎮').toUpperCase();

      function respaldo() {
        if (caja.classList.contains('sin-img')) return;
        caja.classList.remove('loading');
        caja.classList.add('sin-img');
        if (img && img.parentNode) img.parentNode.removeChild(img);
        caja.appendChild(make(caja.classList.contains('detalle-cover') ? 'div' : 'span',
                              caja.classList.contains('detalle-cover') ? 'detalle-cover-placeholder' : 'ph', letra));
      }
      if (!img) return respaldo();
      // Las imágenes diferidas (loading="lazy") quizá todavía no se han pedido: no se juzgan por
      // naturalWidth, sino por su evento 'error'. Solo las no diferidas se revisan al instante.
      if (img.complete && img.loading !== 'lazy') { if (img.naturalWidth === 0) respaldo(); return; }
      if (img.complete && img.naturalWidth > 0) return;                         // diferida que ya cargó
      caja.classList.add('loading');
      img.addEventListener('load', function () { caja.classList.remove('loading'); }, { once: true });
      img.addEventListener('error', respaldo, { once: true });
    });
  }

  /* -------------------------------------------------------------- portada */
  function hero() {
    var h = qsa('.hero')[0];
    if (!h || reduced) return;
    var n = innerWidth < 700 ? 14 : 26;
    var frag = doc.createDocumentFragment();
    for (var i = 0; i < n; i++) {
      var p = make('i', 'particle');
      p.setAttribute('aria-hidden', 'true');
      p.style.cssText = '--x:' + (Math.random() * 100).toFixed(1) + '%;--s:' + (Math.random() * 3 + 2).toFixed(1) + 'px;' +
        '--d:' + (Math.random() * 9 + 9).toFixed(1) + 's;--delay:-' + (Math.random() * 16).toFixed(1) + 's;' +
        '--dx:' + ((Math.random() - 0.5) * 160).toFixed(0) + 'px';
      frag.appendChild(p);
    }
    h.appendChild(frag);

    if (!finePointer) return;                               // el brillo que sigue al mouse
    var tx = 50, ty = 42, cx = 50, cy = 42, raf = 0;
    function paso() {
      cx += (tx - cx) * 0.08;
      cy += (ty - cy) * 0.08;
      h.style.setProperty('--mx', cx.toFixed(2) + '%');
      h.style.setProperty('--my', cy.toFixed(2) + '%');
      raf = (Math.abs(tx - cx) + Math.abs(ty - cy) > 0.05) ? requestAnimationFrame(paso) : 0;
    }
    h.addEventListener('pointermove', function (e) {
      var r = h.getBoundingClientRect();
      tx = (e.clientX - r.left) / r.width * 100;
      ty = (e.clientY - r.top) / r.height * 100;
      if (!raf) raf = requestAnimationFrame(paso);
    });
  }

  /* ---------------------------------------------------------------- varios */
  function autoSubmit() {
    qsa('.filtros-form').forEach(function (form) {
      qsa('select', form).forEach(function (s) {
        s.addEventListener('change', function () {
          qsa('.cards').forEach(function (c) { c.classList.add('is-loading'); });
          if (form.requestSubmit) form.requestSubmit(); else form.submit();
        });
      });
    });
  }

  function relTime() {
    if (!(window.Intl && Intl.RelativeTimeFormat)) return;
    var rtf = new Intl.RelativeTimeFormat(TXT.locale, { numeric: 'auto' });
    var unidades = [['year', 31536000], ['month', 2592000], ['day', 86400], ['hour', 3600], ['minute', 60]];
    qsa('time.rel-time[datetime]').forEach(function (t) {
      var d = new Date(t.getAttribute('datetime'));
      if (isNaN(d.getTime())) return;
      var seg = Math.round((d.getTime() - Date.now()) / 1000), texto = TXT.ahora;
      for (var i = 0; i < unidades.length; i++) {
        if (Math.abs(seg) >= unidades[i][1]) { texto = rtf.format(Math.round(seg / unidades[i][1]), unidades[i][0]); break; }
      }
      var prefijo = (t.getAttribute('data-prefijo') || '').trim();   // el servidor lo manda traducido ("Verificado" / "Verified")
      t.textContent = (prefijo ? prefijo + ' ' : '') + texto;
      t.title = d.toLocaleString(TXT.locale);
    });
  }

  function avisos() {
    qsa('.alerta').forEach(function (a) { a.setAttribute('role', a.classList.contains('err') ? 'alert' : 'status'); });
    qsa('.alerta.ok').forEach(function (a) {                // los de éxito se van solos; los de error se quedan
      setTimeout(function () {
        a.classList.add('out');
        setTimeout(function () { if (a.parentNode) a.parentNode.removeChild(a); }, 600);
      }, 6500);
    });
  }

  function iniciar() {
    [navbar, progress, navLoader, toTop, images, hero, tilt, autoSubmit, relTime, avisos, counters, reveal].forEach(safe);
  }
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', iniciar); else iniciar();
})();
