(function () {
  'use strict';

  var cards = document.querySelector('[data-wish-cards]');
  if (!cards) return;

  var empty = document.querySelector('[data-wish-empty]');
  var count = document.querySelector('[data-wish-count]');
  var error = document.querySelector('[data-wish-error]');
  var totals = {
    steam: document.querySelector('[data-total-steam]'),
    gog: document.querySelector('[data-total-gog]'),
    epic: document.querySelector('[data-total-epic]')
  };
  var juegosPorPlataforma = {
    steam: document.querySelector('[data-juegos-steam]'),
    gog: document.querySelector('[data-juegos-gog]'),
    epic: document.querySelector('[data-juegos-epic]')
  };

  function actualizarLista(lista, juegos) {
    if (!lista) return;
    lista.replaceChildren();
    (juegos || []).forEach(function (nombre) {
      var item = document.createElement('li');
      item.textContent = nombre;
      lista.appendChild(item);
    });
    if (!juegos || juegos.length === 0) {
      var vacio = document.createElement('li');
      vacio.className = 'sin-juegos-plataforma';
      vacio.textContent = 'Sin juegos disponibles';
      lista.appendChild(vacio);
    }
  }

  function actualizarTotales(result) {
    totals.steam.textContent = result.totalSteam || result.steam || '0 COP';
    totals.gog.textContent = result.totalGog || result.gog || '0 COP';
    totals.epic.textContent = result.totalEpic || result.epic || '0 COP';
    actualizarLista(juegosPorPlataforma.steam, result.juegosSteam);
    actualizarLista(juegosPorPlataforma.gog, result.juegosGog);
    actualizarLista(juegosPorPlataforma.epic, result.juegosEpic);
  }

  fetch(document.querySelector('.wishlist-calculadora').dataset.totalesUrl, {
    headers: { 'Accept': 'application/json' },
    credentials: 'same-origin'
  }).then(function (response) {
    if (!response.ok) throw new Error('Wishlist totals failed: HTTP ' + response.status);
    return response.json();
  }).then(actualizarTotales).catch(function (cause) {
    error.hidden = false;
    if (window.console) console.error(cause);
  });

  cards.addEventListener('submit', function (event) {
    var form = event.target.closest('.wish-quitar');
    if (!form) return;
    event.preventDefault();

    var item = form.closest('.wish-item');
    var button = form.querySelector('button[type="submit"]');
    var ajaxUrl = form.action.replace(/\/$/, '') + '/ajax';
    error.hidden = true;
    button.disabled = true;

    fetch(ajaxUrl, {
      method: 'POST',
      body: new FormData(form),
      headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'same-origin'
    }).then(function (response) {
      if (!response.ok || !(response.headers.get('content-type') || '').includes('application/json')) {
        throw new Error('Wishlist removal failed: HTTP ' + response.status);
      }
      return response.json();
    }).then(function (result) {
      item.remove();
      actualizarTotales(result);
      count.textContent = result.count;
      cards.classList.toggle('wish-hidden', result.count === '0');
      empty.classList.toggle('wish-hidden', result.count !== '0');
    }).catch(function (cause) {
      button.disabled = false;
      error.hidden = false;
      if (window.console) console.error(cause);
    });
  });
})();
