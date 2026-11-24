/* ==========================================================================
   historial-precios.js — Gráfica de bajadas y subidas de precio (últimos 12 meses)
   Se usa en juego-detalle.html. Lee los datos de los atributos data-* de #historial-precios
   (los pone Thymeleaf). Dibuja un SVG propio: no depende de ninguna librería externa.
   Las series son «escalonadas»: el precio se mantiene hasta el siguiente cambio.
   ========================================================================== */
(function () {
  'use strict';

  var SIN_DECIMALES = ['COP', 'CLP', 'JPY', 'KRW', 'VND', 'PYG', 'ISK', 'HUF', 'UGX'];
  var COLORES = { 'Steam': '#1b9bd8', 'Epic Games': '#f59e0b', 'GOG': '#a855f7' };
  var EXTRAS = ['#14b8a6', '#ec4899', '#84cc16'];
  var SVG_NS = 'http://www.w3.org/2000/svg';
  var DIA = 86400000;

  function iniciar() {
    var raiz = document.getElementById('historial-precios');
    if (!raiz) return;
    var grafica = raiz.querySelector('[data-grafica]');
    if (!grafica) return;                                // no hay datos: la plantilla muestra el mensaje vacío

    var datos;
    try { datos = JSON.parse(raiz.dataset.json || '{}'); } catch (e) { return; }
    if (!datos.series || !datos.series.length) return;

    var moneda = raiz.dataset.moneda || 'COP';
    var factor = parseFloat(raiz.dataset.factor);
    if (!isFinite(factor) || factor <= 0) factor = 1;
    var idioma = raiz.dataset.lang === 'en' ? 'en' : 'es';
    var t = {
      bajo: raiz.dataset.tBajo, subio: raiz.dataset.tSubio, base: raiz.dataset.tBase,
      hoy: raiz.dataset.tHoy, gratis: raiz.dataset.tGratis, desde: raiz.dataset.tDesde
    };

    var nf = new Intl.NumberFormat(idioma === 'en' ? 'en-US' : 'es-CO', {
      minimumFractionDigits: SIN_DECIMALES.indexOf(moneda) >= 0 ? 0 : 2,
      maximumFractionDigits: SIN_DECIMALES.indexOf(moneda) >= 0 ? 0 : 2
    });
    var fechaCorta = new Intl.DateTimeFormat(idioma === 'en' ? 'en-US' : 'es-CO', { day: 'numeric', month: 'short', year: 'numeric' });
    var mesCorto = new Intl.DateTimeFormat(idioma === 'en' ? 'en-US' : 'es-CO', { month: 'short' });

    function esc(txt) {
      return String(txt).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; });
    }

    function dinero(cop) {
      return cop === 0 ? t.gratis : nf.format(cop * factor) + ' ' + moneda;
    }

    // ------------------------------------------------------------ series visibles + colores
    var extra = 0;
    var series = datos.series.map(function (s) {
      return { tienda: s.tienda, puntos: s.puntos, visible: true, color: COLORES[s.tienda] || EXTRAS[extra++ % EXTRAS.length] };
    });

    // ------------------------------------------------------------ tarjetas de resumen
    function ponerStat(clave, extremo) {
      var valor = raiz.querySelector('[data-stat="' + clave + '"]');
      var sub = raiz.querySelector('[data-stat="' + clave + '-sub"]');
      if (!valor || !extremo) return;
      valor.textContent = dinero(extremo.precio);
      if (sub) sub.textContent = extremo.tienda + ' · ' + fechaCorta.format(new Date(extremo.t));
    }
    ponerStat('min', datos.minimo);
    ponerStat('max', datos.maximo);

    // ------------------------------------------------------------ leyenda (clic = mostrar/ocultar tienda)
    var leyenda = raiz.querySelector('[data-leyenda]');
    if (leyenda) {
      series.forEach(function (s) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'hist-chip activo';
        b.setAttribute('aria-pressed', 'true');
        var punto = document.createElement('i');
        punto.style.background = s.color;
        b.appendChild(punto);
        b.appendChild(document.createTextNode(s.tienda));
        b.addEventListener('click', function () {
          s.visible = !s.visible;
          b.classList.toggle('activo', s.visible);
          b.setAttribute('aria-pressed', String(s.visible));
          dibujar();
        });
        leyenda.appendChild(b);
      });
    }

    // ------------------------------------------------------------ dibujo
    var tooltip = grafica.querySelector('[data-tooltip]');
    var svg = null;

    function el(nombre, attrs, padre) {
      var e = document.createElementNS(SVG_NS, nombre);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      if (padre) padre.appendChild(e);
      return e;
    }

    /** Precio vigente de una serie en el instante x (los puntos están ordenados por fecha). */
    function precioEn(serie, x) {
      var p = null;
      for (var i = 0; i < serie.puntos.length; i++) {
        if (serie.puntos[i].t <= x) p = serie.puntos[i]; else break;
      }
      return p;
    }

    /** Escala «bonita» para el eje Y. */
    function ticks(min, max, n) {
      var rango = max - min || 1;
      var paso = Math.pow(10, Math.floor(Math.log10(rango / n)));
      var mult = [1, 2, 2.5, 5, 10];
      for (var i = 0; i < mult.length; i++) { if (rango / (paso * mult[i]) <= n) { paso *= mult[i]; break; } }
      var ini = Math.floor(min / paso) * paso, res = [];
      for (var v = ini; v <= max + paso * 0.001; v += paso) res.push(v);
      return res;
    }

    function dibujar() {
      var ancho = Math.max(grafica.clientWidth, 280);
      var movil = ancho < 520;
      var alto = movil ? 250 : 330;
      var m = { i: movil ? 58 : 78, d: 16, s: 14, b: 30 };
      var w = ancho - m.i - m.d, h = alto - m.s - m.b;

      if (svg) grafica.removeChild(svg);
      svg = el('svg', { viewBox: '0 0 ' + ancho + ' ' + alto, width: ancho, height: alto, role: 'img', 'class': 'hist-svg' });
      grafica.insertBefore(svg, grafica.firstChild);

      var visibles = series.filter(function (s) { return s.visible; });
      var todos = (visibles.length ? visibles : series);
      var min = Infinity, max = -Infinity;
      todos.forEach(function (s) { s.puntos.forEach(function (p) { if (p.precio < min) min = p.precio; if (p.precio > max) max = p.precio; }); });
      var pad = (max - min) * 0.12 || max * 0.1 || 1;
      var yMin = Math.max(0, min - pad), yMax = max + pad;

      var x = function (ms) { return m.i + (ms - datos.desde) / (datos.hasta - datos.desde) * w; };
      var y = function (cop) { return m.s + h - (cop - yMin) / (yMax - yMin) * h; };

      // rejilla + etiquetas del eje Y
      ticks(yMin, yMax, movil ? 4 : 5).forEach(function (v) {
        if (v < yMin || v > yMax) return;
        el('line', { x1: m.i, x2: m.i + w, y1: y(v), y2: y(v), 'class': 'hist-grid' }, svg);
        var tx = el('text', { x: m.i - 8, y: y(v) + 4, 'text-anchor': 'end', 'class': 'hist-eje' }, svg);
        tx.textContent = v === 0 ? '0' : nf.format(v * factor);
      });

      // eje X: un rótulo por mes
      var d = new Date(datos.desde); d.setDate(1); d.setHours(0, 0, 0, 0); d.setMonth(d.getMonth() + 1);
      var saltar = movil ? 2 : 1, n = 0;
      for (; d.getTime() < datos.hasta; d.setMonth(d.getMonth() + 1), n++) {
        var px = x(d.getTime());
        el('line', { x1: px, x2: px, y1: m.s + h, y2: m.s + h + 4, 'class': 'hist-grid-eje' }, svg);
        if (n % saltar === 0) {
          var lbl = el('text', { x: px, y: m.s + h + 19, 'text-anchor': 'middle', 'class': 'hist-eje' }, svg);
          lbl.textContent = mesCorto.format(d).replace('.', '') + (d.getMonth() === 0 ? ' ’' + String(d.getFullYear()).slice(2) : '');
        }
      }

      // líneas escalonadas
      visibles.forEach(function (s) {
        var path = '';
        s.puntos.forEach(function (p, i) {
          var px = x(p.t), py = y(p.precio);
          path += i === 0 ? 'M' + px + ',' + py : 'H' + px + 'V' + py;
        });
        el('path', { d: path, fill: 'none', stroke: s.color, 'stroke-width': 2.2, 'stroke-linejoin': 'round', 'class': 'hist-linea' }, svg);
      });

      // puntos de cambio (verde = bajó, rojo = subió)
      visibles.forEach(function (s) {
        s.puntos.forEach(function (p) {
          if (p.motivo !== 'bajo' && p.motivo !== 'subio') return;
          var c = el('circle', { cx: x(p.t), cy: y(p.precio), r: 4.2, 'class': 'hist-punto ' + p.motivo }, svg);
          c.style.stroke = s.color;
        });
      });

      // guía vertical + zona sensible al cursor
      var guia = el('line', { y1: m.s, y2: m.s + h, 'class': 'hist-guia', visibility: 'hidden' }, svg);
      var zona = el('rect', { x: m.i, y: m.s, width: w, height: h, fill: 'transparent' }, svg);

      function mostrar(ev) {
        var r = svg.getBoundingClientRect();
        var px = (ev.clientX - r.left) * (ancho / r.width);
        var ms = datos.desde + Math.min(Math.max((px - m.i) / w, 0), 1) * (datos.hasta - datos.desde);
        guia.setAttribute('x1', x(ms)); guia.setAttribute('x2', x(ms)); guia.setAttribute('visibility', 'visible');

        var html = '<b>' + fechaCorta.format(new Date(ms)) + '</b>';
        visibles.forEach(function (s) {
          var p = precioEn(s, ms);
          if (!p) return;
          // ¿hubo un cambio ese mismo día (±1)? se indica con la etiqueta
          var cambio = s.puntos.filter(function (q) { return (q.motivo === 'bajo' || q.motivo === 'subio') && Math.abs(q.t - ms) <= DIA; })[0];
          var marca = cambio ? ' <em class="' + cambio.motivo + '">' + (cambio.motivo === 'bajo' ? '↓ ' + t.bajo : '↑ ' + t.subio) + '</em>' : '';
          html += '<div><i style="background:' + s.color + '"></i>' + esc(s.tienda) + ': <strong>' + dinero(p.precio) + '</strong>' + marca + '</div>';
        });
        tooltip.innerHTML = html;
        tooltip.hidden = false;
        var tw = tooltip.offsetWidth, ancho_t = r.width;
        var left = (x(ms) * (r.width / ancho)) + 14;
        if (left + tw > ancho_t) left = (x(ms) * (r.width / ancho)) - tw - 14;
        tooltip.style.left = Math.max(left, 4) + 'px';
        tooltip.style.top = '8px';
      }
      function ocultar() { guia.setAttribute('visibility', 'hidden'); tooltip.hidden = true; }
      zona.addEventListener('pointermove', mostrar);
      zona.addEventListener('pointerdown', mostrar);
      zona.addEventListener('pointerleave', ocultar);
    }

    dibujar();
    var anchoPrevio = grafica.clientWidth;
    window.addEventListener('resize', function () {
      if (grafica.clientWidth !== anchoPrevio) { anchoPrevio = grafica.clientWidth; dibujar(); }
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar); else iniciar();
})();
