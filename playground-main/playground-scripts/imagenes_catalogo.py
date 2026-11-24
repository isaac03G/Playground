"""
Normalización centralizada de portadas para las tarjetas del catálogo.

Todas las imágenes de tarjeta se generan a 600×800 (proporción 3:4):
imagen original completa y centrada sobre un fondo desenfocado de la misma foto.
La URL original (`imagenUrl`) no se modifica.

    from imagenes_catalogo import aplicar_imagen_catalogo
    aplicar_imagen_catalogo(juego, existente)

Los scrapers (steam.py, epic.py, gog.py) siguen guardando la URL de la tienda;
esta función se invoca al persistir (juegos_comun.upsert_juego, actualizador.py)
y al reprocesar el catálogo ya guardado (normalizar_imagenes_existentes.py).
"""
from __future__ import annotations

import hashlib
import os
import re
from io import BytesIO
from pathlib import Path
from urllib.parse import urlparse

import requests
from PIL import Image, ImageFilter, UnidentifiedImageError

ANCHO = 600
ALTO = 800
CALIDAD_WEBP = 82
RADIO_DESENFOQUE = 28
MAX_BYTES = 12 * 1024 * 1024
TIMEOUT = 20

_SCRAPER_DIR = Path(__file__).resolve().parent
_STATIC_ROOT = _SCRAPER_DIR.parent / "Playground" / "src" / "main" / "resources" / "static"
_UPLOADS_DEFAULT = _STATIC_ROOT / "uploads"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    ),
    "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
}

# imagenFuenteUrl: URL realmente usada para generar la portada (vertical oficial u horizontal).
# imagenVerticalNoDisponible: la portada vertical de la tienda falló; no se vuelve a intentar.
CAMPOS_NORMALIZADOS = ("imagenNormalizadaUrl", "imagenOriginalHash", "imagenFuenteUrl")
CAMPOS_VERTICAL = ("imagenVerticalUrl", "imagenVerticalNoDisponible")

_RE_STEAM_APP = re.compile(r"^(https?://[^/?#]+/steam/apps/\d+/)")


def vertical_steam(url: str | None) -> str | None:
    """Portada vertical oficial de Steam (600×900) derivada de cualquier imagen de /steam/apps/<id>/."""
    m = _RE_STEAM_APP.match((url or "").strip())
    return m.group(1) + "library_600x900.jpg" if m else None


def directorio_normalizadas() -> Path:
    crudo = os.getenv("IMAGENES_NORMALIZADAS_DIR")
    if crudo:
        return Path(crudo)
    return Path(os.getenv("APP_UPLOAD_DIR", str(_UPLOADS_DEFAULT))) / "normalizadas"


def prefijo_url() -> str:
    return os.getenv("IMAGENES_NORMALIZADAS_URL", "/uploads/normalizadas").rstrip("/") + "/"


def hash_bytes(datos: bytes) -> str:
    return hashlib.sha256(datos).hexdigest()


def _a_rgb(im: Image.Image) -> Image.Image:
    if im.mode == "RGB":
        return im
    if im.mode in ("RGBA", "LA") or (im.mode == "P" and "transparency" in im.info):
        rgba = im.convert("RGBA")
        fondo = Image.new("RGB", rgba.size, (8, 10, 16))
        fondo.paste(rgba, mask=rgba.split()[-1])
        return fondo
    return im.convert("RGB")


def componer_portada(im: Image.Image, ancho: int = ANCHO, alto: int = ALTO) -> Image.Image:
    """Devuelve un lienzo 3:4 sin deformar ni recortar la imagen principal."""
    original = _a_rgb(im)
    if original.width < 1 or original.height < 1:
        raise ValueError("imagen sin dimensiones")

    escala_fondo = max(ancho / original.width, alto / original.height)
    fw = max(ancho, int(original.width * escala_fondo + 0.5))
    fh = max(alto, int(original.height * escala_fondo + 0.5))
    fondo = original.resize((fw, fh), Image.Resampling.LANCZOS)
    left = max(0, (fw - ancho) // 2)
    top = max(0, (fh - alto) // 2)
    fondo = fondo.crop((left, top, left + ancho, top + alto))
    fondo = fondo.filter(ImageFilter.GaussianBlur(radius=RADIO_DESENFOQUE))

    escala_frente = min(ancho / original.width, alto / original.height)
    pw = max(1, int(original.width * escala_frente))
    ph = max(1, int(original.height * escala_frente))
    frente = original.resize((pw, ph), Image.Resampling.LANCZOS)

    lienzo = fondo
    lienzo.paste(frente, ((ancho - pw) // 2, (alto - ph) // 2))
    return lienzo


def _leer_local(url: str) -> bytes | None:
    parsed = urlparse(url)
    if parsed.scheme in ("http", "https"):
        return None
    rel = url.split("?", 1)[0]
    if rel.startswith("/uploads/"):
        ruta = _UPLOADS_DEFAULT / rel[len("/uploads/"):]
    elif rel.startswith("/"):
        ruta = _STATIC_ROOT / rel.lstrip("/")
    else:
        ruta = Path(rel)
    if not ruta.is_file():
        return None
    datos = ruta.read_bytes()
    if not datos or len(datos) > MAX_BYTES:
        return None
    return datos


def descargar_original(url: str, session: requests.Session | None = None) -> bytes | None:
    if not url or not str(url).strip():
        return None
    url = str(url).strip()
    local = _leer_local(url)
    if local is not None:
        return local
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return None
    cliente = session or requests
    try:
        r = cliente.get(url, headers=HEADERS, timeout=TIMEOUT, stream=True)
        if r.status_code != 200:
            return None
        largo = r.headers.get("Content-Length")
        if largo and int(largo) > MAX_BYTES:
            r.close()
            return None
        buf = bytearray()
        for trozo in r.iter_content(64 * 1024):
            buf.extend(trozo)
            if len(buf) > MAX_BYTES:
                return None
        return bytes(buf) if buf else None
    except (requests.RequestException, ValueError, OSError):
        return None


def abrir_imagen(datos: bytes) -> Image.Image:
    im = Image.open(BytesIO(datos))
    im.load()
    if im.width < 1 or im.height < 1:
        raise ValueError("imagen sin dimensiones")
    return im


def ruta_desde_url_publica(url: str | None) -> Path | None:
    if not url:
        return None
    prefijo = prefijo_url()
    if not url.startswith(prefijo):
        return None
    nombre = url[len(prefijo):]
    if not nombre or "/" in nombre or "\\" in nombre:
        return None
    return directorio_normalizadas() / nombre


def _archivo_listo(url: str | None) -> bool:
    ruta = ruta_desde_url_publica(url)
    return bool(ruta and ruta.is_file())


def guardar_webp(im: Image.Image, destino: Path) -> None:
    destino.parent.mkdir(parents=True, exist_ok=True)
    temporal = destino.with_suffix(".webp.tmp")
    im.save(temporal, format="WEBP", quality=CALIDAD_WEBP, method=6)
    temporal.replace(destino)


def normalizar_bytes(datos: bytes, destino: Path | None = None) -> tuple[Image.Image, str]:
    digest = hash_bytes(datos)
    im = abrir_imagen(datos)
    portada = componer_portada(im)
    if destino is None:
        destino = directorio_normalizadas() / f"{digest}.webp"
    if not destino.is_file():
        guardar_webp(portada, destino)
    return portada, digest


def _vertical(juego: dict, existente: dict, principal: str) -> str:
    """Candidata vertical: la del scraper, la guardada o (Steam) la derivada. '' si no hay o ya falló."""
    nueva = (juego.get("imagenVerticalUrl") or "").strip()
    if nueva:
        return nueva
    if juego.get("imagenVerticalNoDisponible") or existente.get("imagenVerticalNoDisponible"):
        return ""
    return (existente.get("imagenVerticalUrl") or "").strip() or vertical_steam(principal) or ""


def aplicar_imagen_catalogo(juego: dict, existente: dict | None = None,
                            session: requests.Session | None = None,
                            solo_vertical: bool = False) -> dict:
    """
    Completa `imagenNormalizadaUrl`, `imagenOriginalHash` e `imagenFuenteUrl` en `juego`.
    Prefiere la portada vertical de la tienda (imagenVerticalUrl) y, si falla o no existe,
    usa imagenUrl. Conserva imagenUrl. Si la fuente no cambió y el archivo existe, no hace
    ninguna petición HTTP ni recompone.

    solo_vertical=True (reproceso de juegos antiguos): intenta únicamente la vertical y, si
    falla, deja intacta la portada actual y marca imagenVerticalNoDisponible.
    """
    existente = existente or {}
    principal = (juego.get("imagenUrl") or existente.get("imagenUrl") or "").strip()
    vertical = _vertical(juego, existente, principal)
    if vertical:
        juego.setdefault("imagenVerticalUrl", vertical)
    candidatos = [vertical] if solo_vertical else [u for u in (vertical, principal) if u]
    if not candidatos:
        return juego

    hash_previo = existente.get("imagenOriginalHash")
    url_previa = existente.get("imagenNormalizadaUrl")
    fuente_previa = (existente.get("imagenFuenteUrl") or existente.get("imagenUrl") or "").strip()
    listo = bool(hash_previo and _archivo_listo(url_previa))
    ya_vale = fuente_previa in candidatos and (not solo_vertical or fuente_previa == vertical)
    if listo and ya_vale:
        juego["imagenNormalizadaUrl"] = url_previa
        juego["imagenOriginalHash"] = hash_previo
        juego["imagenFuenteUrl"] = fuente_previa
        return juego

    for url in candidatos:
        datos = descargar_original(url, session)
        try:
            if not datos:
                raise ValueError("sin datos")
            digest = hash_bytes(datos)
            destino = directorio_normalizadas() / f"{digest}.webp"
            normalizar_bytes(datos, destino)          # no regenera si el archivo ya existe
        except (UnidentifiedImageError, OSError, ValueError):
            if url == vertical:
                juego["imagenVerticalNoDisponible"] = True
            continue
        juego["imagenNormalizadaUrl"] = prefijo_url() + destino.name
        juego["imagenOriginalHash"] = digest
        juego["imagenFuenteUrl"] = url
        if url == vertical:
            juego["imagenVerticalUrl"] = vertical
            juego.pop("imagenVerticalNoDisponible", None)
        return juego
    return juego


def necesita_normalizar(doc: dict) -> bool:
    url = (doc.get("imagenUrl") or "").strip()
    if not url:
        return False
    if not doc.get("imagenNormalizadaUrl") or not doc.get("imagenOriginalHash"):
        return True
    return not _archivo_listo(doc.get("imagenNormalizadaUrl"))
