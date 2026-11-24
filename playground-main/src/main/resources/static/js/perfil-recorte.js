(function () {
  'use strict';

  var dialog = document.getElementById('perfil-recorte');
  if (!dialog) return;

  var viewport = document.getElementById('perfil-recorte-ventana');
  var image = document.getElementById('perfil-recorte-imagen');
  var zoom = document.getElementById('perfil-recorte-zoom');
  var title = document.getElementById('perfil-recorte-titulo');
  var help = document.getElementById('perfil-recorte-ayuda');
  var cancel = document.getElementById('perfil-recorte-cancelar');
  var confirm = document.getElementById('perfil-recorte-confirmar');
  var selectedInput = null;
  var objectUrl = null;
  var baseScale = 1;
  var scale = 1;
  var imageX = 0;
  var imageY = 0;
  var drag = null;

  function clampImage() {
    var width = image.naturalWidth * scale;
    var height = image.naturalHeight * scale;
    imageX = Math.min(0, Math.max(viewport.clientWidth - width, imageX));
    imageY = Math.min(0, Math.max(viewport.clientHeight - height, imageY));
    image.style.width = width + 'px';
    image.style.height = height + 'px';
    image.style.left = imageX + 'px';
    image.style.top = imageY + 'px';
  }

  function resetCrop() {
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
    selectedInput = null;
    drag = null;
    confirm.disabled = false;
    image.removeAttribute('src');
    viewport.classList.remove('fondo');
  }

  function closeCrop() {
    if (dialog.open) dialog.close();
    else resetCrop();
  }

  function openCrop(input, file) {
    selectedInput = input;
    viewport.classList.toggle('fondo', input.dataset.recorte === 'fondo');
    title.textContent = input.dataset.recorte === 'fondo'
      ? dialog.dataset.tituloFondo : dialog.dataset.tituloAvatar;
    help.textContent = dialog.dataset.ayuda;
    cancel.textContent = dialog.dataset.cancelar;
    confirm.textContent = dialog.dataset.confirmar;

    objectUrl = URL.createObjectURL(file);
    image.src = objectUrl;
    image.decode().then(function () {
      if (!selectedInput) return;
      dialog.showModal();
      baseScale = Math.max(
        viewport.clientWidth / image.naturalWidth,
        viewport.clientHeight / image.naturalHeight
      );
      zoom.value = '1';
      scale = baseScale;
      imageX = (viewport.clientWidth - image.naturalWidth * scale) / 2;
      imageY = (viewport.clientHeight - image.naturalHeight * scale) / 2;
      clampImage();
    }).catch(function () {
      alert(dialog.dataset.error);
      input.value = '';
      resetCrop();
    });
  }

  document.querySelectorAll('input[type="file"][data-recorte]').forEach(function (input) {
    input.addEventListener('change', function () {
      var file = input.files && input.files[0];
      if (!file) return;

      if (file.size > Number(input.dataset.maxBytes)) {
        alert(input.dataset.maxMessage);
        input.value = '';
        return;
      }
      openCrop(input, file);
    });
  });

  viewport.addEventListener('pointerdown', function (event) {
    if (!selectedInput || event.button !== 0) return;
    drag = { x: event.clientX, y: event.clientY, imageX: imageX, imageY: imageY };
    viewport.setPointerCapture(event.pointerId);
  });

  viewport.addEventListener('pointermove', function (event) {
    if (!drag) return;
    imageX = drag.imageX + event.clientX - drag.x;
    imageY = drag.imageY + event.clientY - drag.y;
    clampImage();
  });

  viewport.addEventListener('pointerup', function () { drag = null; });
  viewport.addEventListener('pointercancel', function () { drag = null; });

  zoom.addEventListener('input', function () {
    if (!selectedInput) return;
    var centerX = (viewport.clientWidth / 2 - imageX) / scale;
    var centerY = (viewport.clientHeight / 2 - imageY) / scale;
    scale = baseScale * Number(zoom.value);
    imageX = viewport.clientWidth / 2 - centerX * scale;
    imageY = viewport.clientHeight / 2 - centerY * scale;
    clampImage();
  });

  cancel.addEventListener('click', function () {
    if (selectedInput) selectedInput.value = '';
    closeCrop();
  });

  confirm.addEventListener('click', function () {
    if (!selectedInput) return;
    confirm.disabled = true;

    var input = selectedInput;
    var form = input.form;
    var canvas = document.createElement('canvas');
    var backgroundCrop = input.dataset.recorte === 'fondo';
    canvas.width = backgroundCrop ? 1600 : 512;
    canvas.height = backgroundCrop ? 500 : 512;

    var sourceX = Math.max(0, -imageX / scale);
    var sourceY = Math.max(0, -imageY / scale);
    var sourceWidth = viewport.clientWidth / scale;
    var sourceHeight = viewport.clientHeight / scale;
    var outputType = input.files[0].type === 'image/png' ? 'image/png' : 'image/jpeg';
    var quality = outputType === 'image/jpeg' ? 0.92 : undefined;

    canvas.getContext('2d').drawImage(
      image, sourceX, sourceY, sourceWidth, sourceHeight,
      0, 0, canvas.width, canvas.height
    );
    canvas.toBlob(function (blob) {
      if (!blob) {
        alert(dialog.dataset.error);
        confirm.disabled = false;
        return;
      }
      if (blob.size > Number(input.dataset.maxBytes)) {
        alert(input.dataset.maxMessage);
        input.value = '';
        closeCrop();
        return;
      }

      var croppedFile = new File([blob], 'perfil-recortado.' + (outputType === 'image/png' ? 'png' : 'jpg'), {
        type: outputType,
        lastModified: Date.now()
      });
      var transfer = new DataTransfer();
      transfer.items.add(croppedFile);
      input.files = transfer.files;
      closeCrop();
      if (form.requestSubmit) form.requestSubmit();
      else form.submit();
    }, outputType, quality);
  });

  dialog.addEventListener('cancel', function () {
    if (selectedInput) selectedInput.value = '';
  });
  dialog.addEventListener('close', resetCrop);
})();
