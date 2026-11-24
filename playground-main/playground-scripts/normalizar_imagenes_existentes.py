"""
Procesa portadas de videojuegos ya guardados en MongoDB.

No toca precios, historiales ni textos. Solo genera (o reutiliza) la versión
3:4 en disco y escribe imagenNormalizadaUrl / imagenOriginalHash.

    python normalizar_imagenes_existentes.py --simular
    python normalizar_imagenes_existentes.py --lote 50
    python normalizar_imagenes_existentes.py -j "hades"
    python normalizar_imagenes_existentes.py --reintentar-errores
    python normalizar_imagenes_existentes.py --verticales --lote 100   # mejora a portada vertical oficial

Se puede reanudar: omite los que ya tienen archivo y hash, y los que fallaron
(salvo --reintentar-errores). Un fallo individual no detiene el lote.
"""
from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import juegos_comun as jc
from imagenes_catalogo import (aplicar_imagen_catalogo, CAMPOS_NORMALIZADOS, CAMPOS_VERTICAL,
                               necesita_normalizar, vertical_steam)

LOG_ERRORES = Path(__file__).resolve().parent / "normalizar_imagenes_errores.log"


def _log_error(nombre: str, detalle: str) -> None:
    linea = f"{datetime.now(timezone.utc).isoformat()} | {nombre} | {detalle}\n"
    with LOG_ERRORES.open("a", encoding="utf-8") as f:
        f.write(linea)
    print(f"  !  {nombre}: {detalle}", file=sys.stderr)


def mejorar_verticales(col, args) -> None:
    """Pasada aparte: solo juegos con portada vertical candidata que aún no la usan. Reanudable."""
    cursor = col.find({}, {"nombre": 1, "imagenUrl": 1, "imagenNormalizadaUrl": 1, "imagenOriginalHash": 1,
                           "imagenFuenteUrl": 1, "imagenVerticalUrl": 1, "imagenVerticalNoDisponible": 1})
    docs = []
    for d in cursor:
        if d.get("imagenVerticalNoDisponible") and not args.reintentar_errores:
            continue
        vertical = (d.get("imagenVerticalUrl") or "").strip() or vertical_steam(d.get("imagenUrl")) or ""
        if not vertical:
            continue
        if args.juego and args.juego.lower() not in (d.get("nombre") or "").lower():
            continue
        if (d.get("imagenFuenteUrl") or "") == vertical and not args.forzar:
            continue
        docs.append(d)
        if args.lote and len(docs) >= args.lote:
            break

    mejoradas = fallidas = 0
    for d in docs:
        nombre = d.get("nombre") or str(d.get("_id"))
        existente = dict(d)
        if args.reintentar_errores:
            existente.pop("imagenVerticalNoDisponible", None)
        juego = {"imagenUrl": d.get("imagenUrl")}
        try:
            aplicar_imagen_catalogo(juego, existente, solo_vertical=True)
        except Exception as e:
            fallidas += 1
            _log_error(nombre, f"vertical: {type(e).__name__}: {e}")
            continue
        if juego.get("imagenFuenteUrl") and juego["imagenFuenteUrl"] == juego.get("imagenVerticalUrl"):
            mejoradas += 1
            print(f"  ok {nombre}\n    -> {juego['imagenNormalizadaUrl']}")
            if not args.simular:
                set_ = {k: juego[k] for k in CAMPOS_NORMALIZADOS + CAMPOS_VERTICAL if juego.get(k)}
                col.update_one({"_id": d["_id"]},
                               {"$set": set_, "$unset": {"imagenVerticalNoDisponible": ""}})
        else:
            fallidas += 1
            print(f"  -  {nombre}: sin portada vertical utilizable")
            if not args.simular:
                col.update_one({"_id": d["_id"]}, {"$set": {"imagenVerticalNoDisponible": True}})
        time.sleep(args.pausa)
    print(f"\nCandidatos {len(docs)}: {mejoradas} mejoradas, {fallidas} sin vertical."
          + ("  [--simular: no se escribió nada]" if args.simular else ""))


def main() -> None:
    ap = argparse.ArgumentParser(description="Normaliza portadas 3:4 de juegos ya guardados")
    ap.add_argument("--simular", action="store_true")
    ap.add_argument("--lote", type=int, default=0, help="máximo de juegos a procesar (0 = todos)")
    ap.add_argument("--pausa", type=float, default=0.2)
    ap.add_argument("-j", "--juego", help="solo este juego (nombre, sin importar mayúsculas)")
    ap.add_argument("--reintentar-errores", action="store_true",
                    help="incluye documentos con imagenNormalizacionError")
    ap.add_argument("--forzar", action="store_true",
                    help="vuelve a generar aunque ya exista la versión normalizada")
    ap.add_argument("--verticales", action="store_true",
                    help="reemplaza la portada por la vertical oficial de la tienda donde exista "
                         "(imagenVerticalUrl o, en Steam, library_600x900); si falla, deja la actual")
    args = ap.parse_args()

    col = jc.obtener_coleccion()
    if args.verticales:
        return mejorar_verticales(col, args)
    filtro: dict = {}
    if args.juego:
        import re
        filtro["nombre"] = {"$regex": re.escape(args.juego), "$options": "i"}
    elif not args.forzar:
        filtro["$or"] = [
            {"imagenNormalizadaUrl": {"$exists": False}},
            {"imagenNormalizadaUrl": None},
            {"imagenNormalizadaUrl": ""},
            {"imagenOriginalHash": {"$exists": False}},
            {"imagenOriginalHash": None},
            {"imagenOriginalHash": ""},
        ]
        if not args.reintentar_errores:
            filtro["imagenNormalizacionError"] = {"$ne": True}

    cursor = col.find(filtro, {
        "nombre": 1, "imagenUrl": 1, "imagenNormalizadaUrl": 1,
        "imagenOriginalHash": 1, "imagenNormalizacionError": 1,
        "imagenFuenteUrl": 1, "imagenVerticalUrl": 1, "imagenVerticalNoDisponible": 1,
    })
    docs = []
    for d in cursor:
        if not (d.get("imagenUrl") or "").strip():
            continue
        # Reanudación segura: los que ya fallaron se saltan salvo que se pida reintentarlos.
        if (d.get("imagenNormalizacionError") and not
                (args.reintentar_errores or args.forzar or args.juego)):
            continue
        if args.forzar or args.juego or necesita_normalizar(d) or (
            args.reintentar_errores and d.get("imagenNormalizacionError")
        ):
            docs.append(d)
        if args.lote and len(docs) >= args.lote:
            break

    procesados = omitidos = fallidos = iguales = 0
    for d in docs:
        nombre = d.get("nombre") or str(d.get("_id"))
        actual = {
            k: d.get(k) for k in ("imagenNormalizadaUrl", "imagenOriginalHash")
        }
        try:
            juego = {"imagenUrl": d.get("imagenUrl")}
            # --forzar regenera la portada, pero conserva la vertical oficial ya conocida
            existente = ({k: d[k] for k in ("imagenUrl", "imagenVerticalUrl", "imagenVerticalNoDisponible") if d.get(k)}
                         if args.forzar else d)
            aplicar_imagen_catalogo(juego, existente)
        except Exception as e:
            fallidos += 1
            _log_error(nombre, f"{type(e).__name__}: {e}")
            if not args.simular:
                col.update_one({"_id": d["_id"]}, {"$set": {"imagenNormalizacionError": True}})
            continue

        if not juego.get("imagenNormalizadaUrl"):
            fallidos += 1
            _log_error(nombre, "no se pudo descargar o leer la imagen original")
            if not args.simular:
                col.update_one({"_id": d["_id"]}, {"$set": {"imagenNormalizacionError": True}})
            time.sleep(args.pausa)
            continue

        if (juego.get("imagenNormalizadaUrl") == actual.get("imagenNormalizadaUrl")
                and juego.get("imagenOriginalHash") == actual.get("imagenOriginalHash")
                and not args.forzar):
            iguales += 1
            if not args.simular and d.get("imagenNormalizacionError"):
                col.update_one({"_id": d["_id"]}, {"$unset": {"imagenNormalizacionError": ""}})
            continue

        procesados += 1
        print(f"  ok {nombre}\n       {d.get('imagenUrl')}\n    -> {juego['imagenNormalizadaUrl']}")
        if not args.simular:
            set_ = {k: juego[k] for k in CAMPOS_NORMALIZADOS + CAMPOS_VERTICAL if juego.get(k)}
            col.update_one(
                {"_id": d["_id"]},
                {"$set": set_, "$unset": {"imagenNormalizacionError": ""}},
            )
        time.sleep(args.pausa)

    print(f"\nCandidatos {len(docs)}: {procesados} generados, {iguales} ya estaban, "
          f"{omitidos} omitidos, {fallidos} errores."
          + ("  [--simular: no se escribió nada]" if args.simular else ""))
    if fallidos:
        print(f"Errores en {LOG_ERRORES}")


if __name__ == "__main__":
    main()
