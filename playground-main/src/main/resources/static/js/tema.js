/*
 * tema.js — modo oscuro / claro.
 * Se carga en el <head> SIN defer para aplicar el tema guardado antes de que se pinte la página
 * (así no hay un destello oscuro al abrir en modo claro). Guarda la elección en localStorage.
 * Por defecto la página se ve oscura, como siempre.
 */
(function () {
  'use strict';
  var CLAVE = 'tema';
  var raiz = document.documentElement;
  var idioma = raiz.getAttribute('lang') === 'en' ? 'en' : 'es';
  var textos = idioma === 'en'
    ? { claro: 'Switch to light mode', oscuro: 'Switch to dark mode' }
    : { claro: 'Cambiar a modo claro', oscuro: 'Cambiar a modo oscuro' };

  function leer() {
    try { return localStorage.getItem(CLAVE) === 'claro' ? 'claro' : 'oscuro'; }
    catch (e) { return 'oscuro'; }          // almacenamiento bloqueado: sin recordar, pero funciona
  }
  function guardar(t) {
    try { localStorage.setItem(CLAVE, t); } catch (e) { /* sin almacenamiento */ }
  }
  function aplicar(t) { raiz.setAttribute('data-tema', t); }

  aplicar(leer());

  var SOL = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">'
          + '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>';
  var LUNA = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
           + '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>';

  function crearBoton() {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'tema-toggle';

    function pintar() {
      var claro = raiz.getAttribute('data-tema') === 'claro';
      btn.innerHTML = claro ? LUNA : SOL;     // muestra a qué tema vas a cambiar
      var txt = claro ? textos.oscuro : textos.claro;
      btn.title = txt;
      btn.setAttribute('aria-label', txt);
      btn.setAttribute('aria-pressed', claro ? 'true' : 'false');
    }

    btn.addEventListener('click', function () {
      var nuevo = raiz.getAttribute('data-tema') === 'claro' ? 'oscuro' : 'claro';
      raiz.classList.add('tema-anim');        // transición suave solo durante el cambio
      aplicar(nuevo);
      guardar(nuevo);
      pintar();
      setTimeout(function () { raiz.classList.remove('tema-anim'); }, 450);
    });

    // Si cambian el tema en otra pestaña, esta se sincroniza
    window.addEventListener('storage', function (e) {
      if (e.key === CLAVE) { aplicar(leer()); pintar(); }
    });

    pintar();
    return btn;
  }

  function iniciar() {
    if (document.querySelector('.tema-toggle')) return;
    var btn = crearBoton();
    var nav = document.querySelector('.navbar');
    if (nav) {
      var acciones = nav.querySelector('.nav-actions');
      (acciones || nav).appendChild(btn);
    } else {                                   // login, registro, error: sin barra
      btn.classList.add('flotante');
      document.body.appendChild(btn);
    }
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar);
  else iniciar();
})();
