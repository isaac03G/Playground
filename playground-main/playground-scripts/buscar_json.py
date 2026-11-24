#!/usr/bin/env python3
"""
Busca UN juego por nombre en Steam, GOG y Epic Games y responde en JSON.

Lo usa el panel de administración de la app Java (botón "Buscar en plataformas"), pero también
sirve desde la terminal:

    python buscar_json.py "witcher 3"             # solo busca y muestra (no escribe en MongoDB)
    python buscar_json.py "witcher 3" --guardar   # además guarda el juego y sus precios
    python buscar_json.py "hades" --tiendas steam,gog

Reutiliza la lógica de juegos_comun.py (misma búsqueda y mismos criterios de coincidencia que
`python juegos_comun.py -j "nombre"`). Solo agrega una salida en JSON por la salida estándar;
todos los mensajes de depuración van a la salida de errores.

Estado de cada tienda en la respuesta:
    encontrado    -> está en la tienda y tiene precio
    sin_precio    -> está en la tienda pero no muestra precio (p. ej. aún no sale)
    no_encontrado -> la tienda respondió bien pero no tiene ese juego
    error         -> la tienda no respondió (bloqueo, caída, API cambiada...); ver "detalle"
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import re
import sys


def _utf8() -> None:
    """Evita UnicodeEncodeError con los emojis de los mensajes cuando la salida es una tubería (Windows)."""
    for flujo in (sys.stdout, sys.stderr):
        try:
            flujo.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

def _tasas_para_mostrar() -> dict | None:
    """Tasas guardadas por tasas_cambio.py (para mostrar los precios en COP). None si no se pueden leer."""
    try:
        import tasas_cambio
        with contextlib.redirect_stdout(sys.stderr):   # sus mensajes no deben ensuciar el JSON de la salida estándar
            return tasas_cambio.tasas_vigentes(tasas_cambio.conectar())
    except Exception as e:
        print(f"[tasas] no disponibles: {type(e).__name__}: {e}", file=sys.stderr)
        return None


def buscar(consulta: str, tiendas: list[str], umbral: float = 0.92, guardar: bool = False) -> dict:
    import juegos_comun as jc

    mensajes = io.StringIO()   # los print() de los scrapers se capturan aquí
    with contextlib.redirect_stdout(mensajes):
        # 1) Texto libre en todas las tiendas ("witcher 3" -> "The Witcher 3: Wild Hunt")
        fallos: dict = {}
        libres = jc._consultar_tiendas({"tienda": None, "nombre": consulta, "id": None, "ref": None},
                                       tiendas, umbral, fallos, libre=True)
        base = next((t for t in jc.TIENDAS if libres.get(t)), None)

        resultados: dict = {}
        if base:
            resultados[base] = libres[base]
            otras = [t for t in tiendas if t != base]
            if otras:
                # 2) Con el nombre oficial se consulta el resto de tiendas
                fallos2: dict = {}
                resultados.update(jc._consultar_tiendas(
                    {"tienda": None, "nombre": libres[base][0]["nombre"], "id": None, "ref": None},
                    otras, umbral, fallos2))
                fallos.update(fallos2)

        juego, precios = jc._fusionar(resultados) if resultados else ({}, [])

        guardado = None
        if guardar:
            if precios:
                guardado = jc._guardar(jc.obtener_coleccion(), juego, precios, umbral)
            else:
                guardado = "sin_precio"

    # Lo que imprimieron los scrapers pasa a la salida de errores (queda en el log de la app)
    texto = mensajes.getvalue()
    if texto:
        print(texto, file=sys.stderr, end="")

    # Primer mensaje de cada tienda ("⚠️  Steam: HTTPError: ..." o "↪️  GOG: coincidencia descartada ...")
    detalles: dict = {}
    for linea in texto.splitlines():
        m = re.match(r"^\S*\s*(Steam|GOG|Epic Games): (.+)$", linea.strip())
        if m and m.group(1) not in detalles:
            detalles[m.group(1)] = m.group(2)[:200]


    # Fuera del bucle anterior: antes estaba dentro y fallaba (UnboundLocalError) si los scrapers no imprimían nada
    tasas = _tasas_para_mostrar() if any(p for _, p in resultados.values()) else None

    salida_tiendas = []
    for t in tiendas:
        res = resultados.get(t)
        if res:
            j, p = res
            if p and tasas:                       # GOG llega en USD: se muestra en COP (y el original aparte)
                import tasas_cambio
                p = tasas_cambio.fijar_en_cop(dict(p), tasas)
            # % de descuento vigente en la tienda y precio sin descuento (en COP, igual que "precio")
            desc = int(p.get("descuentoTienda") or 0) if p else 0
            sin_desc = None
            if p and 0 < desc < 100 and p.get("precio"):
                sin_desc = round(p["precio"] / (1 - desc / 100))
            salida_tiendas.append({
                "tienda": t,
                "estado": "encontrado" if p else "sin_precio",
                "descuento": desc,
                "precioSinDescuento": sin_desc,
                "nombre": j.get("nombre"),
                "precio": p.get("precio") if p else None,
                "moneda": p.get("moneda") if p else None,
                "precioOriginal": p.get("precioOriginal") if p else None,
                "monedaOriginal": p.get("monedaOriginal") if p else None,
                "esGratis": bool(p.get("esGratis")) if p else False,
                "detalle": None,
            })
        elif fallos.get(t, 0) > 0:
            salida_tiendas.append({"tienda": t, "estado": "error", "nombre": None, "precio": None,
                                   "moneda": None, "esGratis": False,
                                   "detalle": detalles.get(t) or "la tienda no respondió"})
        else:
            salida_tiendas.append({"tienda": t, "estado": "no_encontrado", "nombre": None, "precio": None,
                                   "moneda": None, "esGratis": False, "detalle": detalles.get(t)})

    return {
        "consulta": consulta,
        "encontrado": bool(resultados),
        "nombre": juego.get("nombre") if juego else None,
        "genero": juego.get("genero") if juego else None,
        "imagenUrl": juego.get("imagenUrl") if juego else None,
        "guardado": guardado,      # None | insertado | actualizado | sin_precio
        "tiendas": salida_tiendas,
    }


def main() -> int:
    _utf8()
    salida = sys.stdout            # el JSON final se escribe en la salida estándar real
    ap = argparse.ArgumentParser(description="Busca un juego en Steam, GOG y Epic y responde en JSON.")
    ap.add_argument("busqueda", help="Nombre (o parte) del juego")
    ap.add_argument("--tiendas", default="steam,gog,epic", help="Tiendas separadas por coma (steam,gog,epic)")
    ap.add_argument("--guardar", action="store_true", help="Guarda el juego y sus precios en MongoDB")
    ap.add_argument("--umbral", type=float, default=0.92, help="Similitud mínima de nombres (0-1)")
    args = ap.parse_args()

    try:
        import juegos_comun as jc
        tiendas = []
        for t in args.tiendas.split(","):
            nombre = jc.ALIAS_TIENDAS.get(t.strip().lower())
            if not nombre:
                raise ValueError(f"tienda desconocida: {t.strip()!r} (usa steam, gog, epic)")
            if nombre not in tiendas:
                tiendas.append(nombre)
        consulta = " ".join(args.busqueda.split())
        if len(consulta) < 2:
            raise ValueError("escribe al menos 2 caracteres")
        resultado = buscar(consulta, tiendas, args.umbral, args.guardar)
    except Exception as e:
        print(json.dumps({"error": f"{type(e).__name__}: {e}"}), file=salida)
        return 1

    print(json.dumps(resultado, ensure_ascii=True), file=salida)   # ASCII puro: sin problemas de codificación
    return 0


if __name__ == "__main__":
    sys.exit(main())