"""Pruebas del procesador de portadas 3:4 (sin MongoDB ni tiendas reales)."""
from __future__ import annotations

import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import imagenes_catalogo as ic


def _png(ancho: int, alto: int, color=(40, 120, 200)) -> bytes:
    im = Image.new("RGB", (ancho, alto), color)
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    return buf.getvalue()


class TestComposicion(unittest.TestCase):
    def test_horizontal_steam(self):
        im = Image.open(io.BytesIO(_png(1200, 500)))
        out = ic.componer_portada(im)
        self.assertEqual(out.size, (600, 800))

    def test_horizontal_epic(self):
        im = Image.open(io.BytesIO(_png(1920, 1080, (180, 80, 40))))
        out = ic.componer_portada(im)
        self.assertEqual(out.size, (600, 800))

    def test_vertical_gog(self):
        im = Image.open(io.BytesIO(_png(400, 900, (20, 160, 80))))
        out = ic.componer_portada(im)
        self.assertEqual(out.size, (600, 800))

    def test_cuadrada(self):
        im = Image.open(io.BytesIO(_png(500, 500, (200, 200, 40))))
        out = ic.componer_portada(im)
        self.assertEqual(out.size, (600, 800))

    def test_pequena(self):
        im = Image.open(io.BytesIO(_png(40, 24, (255, 0, 0))))
        out = ic.componer_portada(im)
        self.assertEqual(out.size, (600, 800))

    def test_invalida(self):
        with self.assertRaises(Exception):
            ic.abrir_imagen(b"esto no es una imagen")

    def test_proporcion_frente_horizontal(self):
        """Un original 2:1 cabe a 600×300 centrado, idéntico al reescalado (sin deformar ni recortar)."""
        orig = Image.new("RGB", (800, 400))
        for x in range(800):                       # degradado horizontal: fondo y frente se distinguen
            for y in range(400):
                orig.putpixel((x, y), (x * 255 // 799, y * 255 // 399, 90))
        out = ic.componer_portada(orig)
        self.assertEqual(out.size, (600, 800))
        esperado = orig.resize((600, 300), Image.Resampling.LANCZOS)
        franja = out.crop((0, 250, 600, 550))
        self.assertEqual(franja.tobytes(), esperado.tobytes())
        # Por encima y por debajo de la franja hay fondo desenfocado, no franjas vacías ni negras.
        for y in (5, 790):
            r, g, b = out.getpixel((300, y))
            self.assertGreater(r + g + b, 60)

    def test_cuadrada_no_se_deforma(self):
        orig = Image.new("RGB", (500, 500), (200, 30, 30))
        out = ic.componer_portada(orig)
        # Cuadrada -> 600×600 centrada (y=100..699); el centro conserva el color original.
        self.assertEqual(out.getpixel((300, 400)), (200, 30, 30))


class TestAplicar(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.prev_dir = ic.os.environ.get("IMAGENES_NORMALIZADAS_DIR")
        ic.os.environ["IMAGENES_NORMALIZADAS_DIR"] = str(self.dir)

    def tearDown(self):
        if self.prev_dir is None:
            ic.os.environ.pop("IMAGENES_NORMALIZADAS_DIR", None)
        else:
            ic.os.environ["IMAGENES_NORMALIZADAS_DIR"] = self.prev_dir
        self.tmp.cleanup()

    def test_inaccesible(self):
        juego = {"imagenUrl": "https://127.0.0.1:1/no-existe.jpg"}
        ic.aplicar_imagen_catalogo(juego)
        self.assertNotIn("imagenNormalizadaUrl", juego)
        self.assertEqual(juego["imagenUrl"], "https://127.0.0.1:1/no-existe.jpg")

    def test_sin_cambio_no_redescarga(self):
        datos = _png(640, 360)
        digest = ic.hash_bytes(datos)
        destino = self.dir / f"{digest}.webp"
        ic.normalizar_bytes(datos, destino)
        juego = {"imagenUrl": "https://cdn.example/steam.jpg"}
        existente = {
            "imagenUrl": "https://cdn.example/steam.jpg",
            "imagenOriginalHash": digest,
            "imagenNormalizadaUrl": "/uploads/normalizadas/" + destino.name,
        }
        with patch.object(ic, "descargar_original") as dl:
            ic.aplicar_imagen_catalogo(juego, existente)
            dl.assert_not_called()
        self.assertEqual(juego["imagenOriginalHash"], digest)
        self.assertEqual(juego["imagenNormalizadaUrl"], existente["imagenNormalizadaUrl"])
        mtime1 = destino.stat().st_mtime
        ic.aplicar_imagen_catalogo(juego, dict(juego, imagenUrl=juego["imagenUrl"]))
        mtime2 = destino.stat().st_mtime
        self.assertEqual(mtime1, mtime2)

    def test_imagen_cambiada_genera_nueva(self):
        a = _png(640, 360, (10, 10, 10))
        b = _png(640, 360, (250, 250, 250))
        ha, hb = ic.hash_bytes(a), ic.hash_bytes(b)
        self.assertNotEqual(ha, hb)
        juego = {"imagenUrl": "https://cdn.example/epic.jpg"}
        with patch.object(ic, "descargar_original", return_value=a):
            ic.aplicar_imagen_catalogo(juego)
        self.assertEqual(juego["imagenOriginalHash"], ha)
        url_a = juego["imagenNormalizadaUrl"]
        juego["imagenUrl"] = "https://cdn.example/epic-v2.jpg"
        with patch.object(ic, "descargar_original", return_value=b):
            ic.aplicar_imagen_catalogo(juego, dict(juego, imagenUrl="https://cdn.example/epic.jpg"))
        self.assertEqual(juego["imagenOriginalHash"], hb)
        self.assertNotEqual(juego["imagenNormalizadaUrl"], url_a)
        self.assertTrue((self.dir / f"{ha}.webp").is_file())
        self.assertTrue((self.dir / f"{hb}.webp").is_file())

    def test_antiguo_sin_procesar(self):
        datos = _png(300, 400, (0, 80, 160))
        juego = {"imagenUrl": "https://cdn.example/gog.jpg"}
        with patch.object(ic, "descargar_original", return_value=datos):
            ic.aplicar_imagen_catalogo(juego, {"imagenUrl": juego["imagenUrl"]})
        self.assertTrue(juego.get("imagenNormalizadaUrl"))
        self.assertEqual(juego["imagenUrl"], "https://cdn.example/gog.jpg")
        ruta = self.dir / f"{juego['imagenOriginalHash']}.webp"
        self.assertTrue(ruta.is_file())
        with Image.open(ruta) as im:
            self.assertEqual(im.size, (600, 800))

    def test_necesita_normalizar(self):
        self.assertTrue(ic.necesita_normalizar({"imagenUrl": "http://x/a.jpg"}))
        self.assertFalse(ic.necesita_normalizar({}))


class TestVertical(unittest.TestCase):
    """Portada vertical oficial de la tienda con respaldo a la horizontal."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.prev = ic.os.environ.get("IMAGENES_NORMALIZADAS_DIR")
        ic.os.environ["IMAGENES_NORMALIZADAS_DIR"] = self.tmp.name

    def tearDown(self):
        if self.prev is None:
            ic.os.environ.pop("IMAGENES_NORMALIZADAS_DIR", None)
        else:
            ic.os.environ["IMAGENES_NORMALIZADAS_DIR"] = self.prev
        self.tmp.cleanup()

    def _web(self, mapa):
        return lambda url, session=None: mapa.get(url)

    def test_vertical_steam_derivada(self):
        self.assertEqual(
            ic.vertical_steam("https://cdn.x.com/steam/apps/570/capsule_616x353.jpg?t=1"),
            "https://cdn.x.com/steam/apps/570/library_600x900.jpg")
        self.assertIsNone(ic.vertical_steam("https://cdn.example/otra.jpg"))
        self.assertIsNone(ic.vertical_steam(None))

    def test_usa_vertical_si_existe(self):
        h, v = "https://s/steam/apps/1/header.jpg", "https://s/steam/apps/1/library_600x900.jpg"
        web = {h: _png(460, 215), v: _png(600, 900, (9, 9, 9))}
        juego = {"imagenUrl": h}
        with patch.object(ic, "descargar_original", self._web(web)):
            ic.aplicar_imagen_catalogo(juego)
        self.assertEqual(juego["imagenFuenteUrl"], v)
        self.assertEqual(juego["imagenOriginalHash"], ic.hash_bytes(web[v]))
        self.assertEqual(juego["imagenUrl"], h)               # la original no se toca
        with Image.open(ic.ruta_desde_url_publica(juego["imagenNormalizadaUrl"])) as im:
            self.assertEqual(im.size, (600, 800))

    def test_respaldo_si_vertical_falla(self):
        h = "https://s/steam/apps/2/header.jpg"
        juego = {"imagenUrl": h}
        with patch.object(ic, "descargar_original", self._web({h: _png(460, 215)})):
            ic.aplicar_imagen_catalogo(juego)
        self.assertEqual(juego["imagenFuenteUrl"], h)
        self.assertTrue(juego["imagenVerticalNoDisponible"])
        self.assertTrue(juego["imagenNormalizadaUrl"])

    def test_vertical_invalida_cae_a_horizontal(self):
        h, v = "https://e/h.jpg", "https://e/t.jpg"
        juego = {"imagenUrl": h, "imagenVerticalUrl": v}
        with patch.object(ic, "descargar_original", self._web({h: _png(1920, 1080), v: b"basura"})):
            ic.aplicar_imagen_catalogo(juego)
        self.assertEqual(juego["imagenFuenteUrl"], h)
        self.assertTrue(juego["imagenVerticalNoDisponible"])

    def test_sin_cambio_no_descarga(self):
        h, v = "https://g/h.jpg", "https://g/v.jpg"
        web = {v: _png(400, 600)}
        primero = {"imagenUrl": h, "imagenVerticalUrl": v}
        with patch.object(ic, "descargar_original", self._web(web)):
            ic.aplicar_imagen_catalogo(primero)
        existente = dict(primero)
        with patch.object(ic, "descargar_original") as dl:
            nuevo = {"imagenUrl": h, "imagenVerticalUrl": v}
            ic.aplicar_imagen_catalogo(nuevo, existente)
            dl.assert_not_called()
        self.assertEqual(nuevo["imagenFuenteUrl"], v)

    def test_solo_vertical_mejora_documento_antiguo(self):
        h, v = "https://s/steam/apps/3/header.jpg", "https://s/steam/apps/3/library_600x900.jpg"
        antiguo = {"imagenUrl": h}
        with patch.object(ic, "descargar_original", self._web({h: _png(460, 215)})):
            # simula portada antigua generada a partir de la horizontal, sin vertical
            ic.aplicar_imagen_catalogo(antiguo, {"imagenVerticalNoDisponible": True})
        existente = {k: antiguo[k] for k in ("imagenUrl", "imagenNormalizadaUrl", "imagenOriginalHash", "imagenFuenteUrl")}
        juego = {"imagenUrl": h}
        with patch.object(ic, "descargar_original", self._web({v: _png(600, 900)})):
            ic.aplicar_imagen_catalogo(juego, existente, solo_vertical=True)
        self.assertEqual(juego["imagenFuenteUrl"], v)
        self.assertNotEqual(juego["imagenNormalizadaUrl"], existente["imagenNormalizadaUrl"])
        # el archivo anterior se conserva
        self.assertTrue(ic.ruta_desde_url_publica(existente["imagenNormalizadaUrl"]).is_file())

    def test_solo_vertical_fallo_no_toca_actual(self):
        h = "https://s/steam/apps/4/header.jpg"
        existente = {"imagenUrl": h, "imagenNormalizadaUrl": "/uploads/normalizadas/x.webp",
                     "imagenOriginalHash": "x", "imagenFuenteUrl": h}
        juego = {"imagenUrl": h}
        with patch.object(ic, "descargar_original", self._web({})):
            ic.aplicar_imagen_catalogo(juego, existente, solo_vertical=True)
        self.assertNotIn("imagenNormalizadaUrl", juego)
        self.assertTrue(juego["imagenVerticalNoDisponible"])


if __name__ == "__main__":
    unittest.main()
