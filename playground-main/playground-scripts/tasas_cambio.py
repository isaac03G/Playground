#!/usr/bin/env python3
"""
Tasas de cambio -> MongoDB, y precios de los juegos siempre en COP (pesos colombianos).

Idea general
------------
  * La plataforma guarda TODOS los precios base en COP (campo `precio`, `moneda` = "COP").
  * Algunas tiendas (GOG, a veces Steam/Epic) devuelven USD u otra moneda. Esos precios se convierten
    a COP al guardarlos y se conserva el dato original en `precioOriginal` / `monedaOriginal`:

        {plataforma: "GOG", precio: 41200, moneda: "COP", precioOriginal: 9.99, monedaOriginal: "USD"}

  * Las tasas se guardan en MongoDB y la app Java las lee para mostrar los precios en la moneda
    que el usuario elija. Este script las mantiene al día.

Qué hace este archivo
---------------------
  1. DESCARGA las tasas de las monedas configuradas y las GUARDA en MongoDB:
        - colección `tasas_cambio`            -> un documento (_id = "actual") con las tasas vigentes
        - colección `tasas_cambio_historial`  -> una foto por actualización (se borra sola tras N días)
  2. REPITE la descarga cada cierto tiempo con `--cada HORAS` (déjalo corriendo) o programado con el
     Programador de tareas de Windows (ver LEEME).
  3. NORMALIZA a COP los precios de la colección `juegos`. Siempre parte del precio ORIGINAL de la
     tienda, así que no se acumulan errores de redondeo y los precios convertidos siguen la tasa del día.

Uso:
    python tasas_cambio.py                          # actualiza tasas + normaliza precios, una vez
    python tasas_cambio.py --cada 6                 # lo repite cada 6 horas (dejar corriendo)
    python tasas_cambio.py --solo-tasas             # solo guarda tasas, sin tocar los juegos
    python tasas_cambio.py --monedas COP,USD,EUR    # monedas a mantener (también: MONEDAS en .env)
    python tasas_cambio.py --ver                    # muestra las tasas guardadas
    python tasas_cambio.py --convertir 59900 --de COP --a USD
    python tasas_cambio.py --revertir               # deja los precios como los entregó cada tienda
    python tasas_cambio.py --simular                # muestra qué haría, sin escribir

Para AGREGAR una moneda a la plataforma: añade su código a MONEDAS en el .env (p. ej.
MONEDAS=COP,USD,EUR,MXN,BRL) y ejecuta el script; el selector de la web la mostrará sola.

Fuente de datos: https://www.exchangerate-api.com (endpoint abierto, sin clave, se actualiza
1 vez al día). Sus términos EXIGEN atribución: la web ya muestra el enlace "Rates By Exchange Rate API".
Si esa fuente falla se prueba con una de respaldo (currency-api vía jsDelivr).

Las tasas se guardan siempre con base USD; entre dos monedas cualesquiera:
    valor_en_B = valor_en_A * tasas[B] / tasas[A]

Variables de entorno (.env, las mismas que usan los otros scripts):
    MONGO_URI, MONGO_DB, MONGO_COLLECTION      (colección de juegos)
    TASAS_COLECCION                            (opcional, por defecto "tasas_cambio")
    MONEDAS                                    (opcional, lista separada por comas)
"""

from __future__ import annotations

import argparse
import copy
import math
import os
import sys
import time
from datetime import datetime, timedelta, timezone

import requests
from dotenv import load_dotenv
from pymongo import ASCENDING, MongoClient, UpdateOne
from pymongo.errors import OperationFailure

load_dotenv()

MONGO_URI = os.getenv("MONGO_URI", "mongodb://localhost:27017")
DB_NAME = os.getenv("MONGO_DB", "juegos_db")
COL_JUEGOS = os.getenv("MONGO_COLLECTION", "juegos")
COL_TASAS = os.getenv("TASAS_COLECCION", "tasas_cambio")
COL_HISTORIAL = COL_TASAS + "_historial"

BASE = "USD"                      # moneda base con la que se guardan las TASAS
MONEDA_BASE_PRECIOS = "COP"       # moneda en la que se guardan los PRECIOS de los juegos
MONEDAS_POR_DEFECTO = os.getenv("MONEDAS") or "COP,USD,EUR,GBP,MXN,ARS,BRL,CLP,PEN,CAD,JPY"
SIN_DECIMALES = {"COP", "CLP", "JPY", "KRW", "VND", "PYG", "ISK", "HUF", "UGX"}

URL_ER_API = "https://open.er-api.com/v6/latest/" + BASE
URL_RESPALDO = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json"
HEADERS = {"User-Agent": "playground-scraper/1.0 (tasas_cambio.py)"}
TIMEOUT = 20

SALTO_MAXIMO = 0.5        # una tasa que cambie más de 50 % de golpe se considera sospechosa
ESPERA_429 = 20 * 60      # el endpoint abierto bloquea 20 min si se abusa de él
MIN_HORAS = 1.0           # el API solo cambia 1 vez al día: no tiene sentido consultar más seguido


def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def lista_monedas(texto: str) -> list[str]:
    """'cop, usd,EUR' -> ['COP', 'EUR', 'USD'] (siempre incluye COP, que es la moneda de los precios)."""
    monedas = {m.strip().upper() for m in texto.split(",") if m.strip()}
    malas = sorted(m for m in monedas if not (len(m) == 3 and m.isalpha()))
    if malas:
        raise ValueError(f"códigos de moneda inválidos: {', '.join(malas)} (usa 3 letras, p. ej. USD)")
    return sorted(monedas | {MONEDA_BASE_PRECIOS})


# =============================================================================
# 1. DESCARGA DE TASAS
# =============================================================================
class FuenteFallida(Exception):
    """Una fuente de tasas no respondió bien (red, 429, JSON raro...)."""


def _get_json(url: str) -> dict:
    try:
        r = requests.get(url, headers=HEADERS, timeout=TIMEOUT)
    except requests.RequestException as e:
        raise FuenteFallida(f"error de red: {e}") from e
    if r.status_code == 429:
        raise FuenteFallida("429 (demasiadas consultas; el bloqueo dura ~20 min)")
    if r.status_code != 200:
        raise FuenteFallida(f"HTTP {r.status_code}")
    try:
        return r.json()
    except ValueError as e:
        raise FuenteFallida("la respuesta no es JSON") from e


def _desde_er_api() -> tuple[dict, dict]:
    datos = _get_json(URL_ER_API)
    if datos.get("result") != "success":
        raise FuenteFallida(f"result={datos.get('result')!r} {datos.get('error-type', '')}")
    proxima = datos.get("time_next_update_unix")
    meta = {
        "fuente": "open.er-api.com (ExchangeRate-API)",
        "atribucion": "Rates By Exchange Rate API - https://www.exchangerate-api.com",
        "proximaActualizacion": datetime.fromtimestamp(proxima, timezone.utc) if proxima else None,
    }
    return datos.get("rates") or {}, meta


def _desde_respaldo() -> tuple[dict, dict]:
    datos = _get_json(URL_RESPALDO)
    crudas = datos.get(BASE.lower())
    if not isinstance(crudas, dict):
        raise FuenteFallida("formato inesperado")
    tasas = {k.upper(): v for k, v in crudas.items()}
    return tasas, {"fuente": "currency-api (jsDelivr)", "atribucion": None, "proximaActualizacion": None}


FUENTES = (("ExchangeRate-API", _desde_er_api), ("currency-api", _desde_respaldo))


def descargar_tasas(monedas: list[str]) -> tuple[dict, dict]:
    """
    Prueba cada fuente en orden y devuelve ({moneda: tasa}, meta) con SOLO las monedas pedidas.
    Una fuente se acepta si trae todas las monedas pedidas con valores válidos.
    """
    pedidas = sorted(set(monedas) | {BASE})
    errores = []
    for nombre, fuente in FUENTES:
        try:
            crudas, meta = fuente()
        except FuenteFallida as e:
            errores.append(f"{nombre}: {e}")
            log(f"⚠️  {nombre} falló: {e}")
            continue
        tasas, faltan = {}, []
        for m in pedidas:
            v = crudas.get(m)
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or v <= 0:
                faltan.append(m)
            else:
                tasas[m] = float(v)
        if faltan:
            errores.append(f"{nombre}: sin tasa válida para {', '.join(faltan)}")
            log(f"⚠️  {nombre} no trae: {', '.join(faltan)}")
            continue
        tasas[BASE] = 1.0
        return tasas, meta
    raise RuntimeError("Ninguna fuente respondió: " + " | ".join(errores))


# =============================================================================
# 2. ALMACENAMIENTO EN MONGODB
# =============================================================================
def conectar():
    cliente = MongoClient(MONGO_URI, serverSelectionTimeoutMS=10_000)
    return cliente[DB_NAME]


def preparar_indices(db, dias_historial: int) -> None:
    try:
        db[COL_HISTORIAL].create_index([("fecha", ASCENDING)], expireAfterSeconds=dias_historial * 86400)
    except OperationFailure:
        # Ya existía con otro plazo de borrado: se actualiza con collMod.
        db.command("collMod", COL_HISTORIAL,
                   index={"keyPattern": {"fecha": 1}, "expireAfterSeconds": dias_historial * 86400})


def cargar_tasas(db) -> dict | None:
    """Devuelve el documento de tasas vigentes o None si aún no hay."""
    return db[COL_TASAS].find_one({"_id": "actual"})


def filtrar_saltos(nuevas: dict, anteriores: dict | None, forzar: bool) -> dict:
    """Descarta tasas que se mueven demasiado respecto a la guardada (probable error de la fuente)."""
    if not anteriores or forzar:
        return nuevas
    aceptadas = {}
    for m, v in nuevas.items():
        previa = anteriores.get(m)
        if previa and abs(v / previa - 1) > SALTO_MAXIMO:
            log(f"🚨 {m}: {previa} -> {v} cambia más de {SALTO_MAXIMO:.0%}; la ignoro (usa --forzar si es real)")
            continue
        aceptadas[m] = v
    return aceptadas


def guardar_tasas(db, tasas: dict, meta: dict, guardar_historial: bool = True) -> datetime:
    """Actualiza el documento vigente (sin borrar monedas que ya tenía) y añade una foto al historial."""
    ahora = datetime.now(timezone.utc)
    set_ = {f"tasas.{m}": v for m, v in tasas.items()}
    set_.update({
        "base": BASE,
        "fuente": meta.get("fuente"),
        "atribucion": meta.get("atribucion"),
        "proximaActualizacion": meta.get("proximaActualizacion"),
        "actualizadoEn": ahora,
    })
    db[COL_TASAS].update_one({"_id": "actual"}, {"$set": set_}, upsert=True)
    if guardar_historial:
        db[COL_HISTORIAL].insert_one({"fecha": ahora, "base": BASE, "fuente": meta.get("fuente"), "tasas": tasas})
    return ahora


# =============================================================================
# 3. CONVERSIÓN Y NORMALIZACIÓN A COP
# =============================================================================
def redondear(valor: float, moneda: str):
    """COP/CLP/JPY... sin decimales (entero); el resto con 2."""
    if moneda in SIN_DECIMALES:
        return int(round(valor))
    return round(valor, 2)


def convertir(monto: float, de: str, a: str, tasas: dict) -> float:
    """Convierte `monto` de la moneda `de` a `a`. KeyError si falta alguna tasa."""
    de, a = de.upper(), a.upper()
    if de == a:
        return float(monto)
    return float(monto) * tasas[a] / tasas[de]


def moneda_de(precio: dict) -> str:
    """Moneda de un precio; si no trae el campo se asume COP (precios cargados a mano)."""
    return (precio.get("moneda") or MONEDA_BASE_PRECIOS).strip().upper()


def _es_numero(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def fijar_en_cop(precio: dict, tasas: dict) -> dict:
    """
    Convierte `precio` (dict con precio/moneda) a COP, en el mismo diccionario.
    Si ya está en COP, o no hay tasa para su moneda, lo deja tal cual (el script periódico lo corregirá).
    Guarda el valor de la tienda en `precioOriginal` / `monedaOriginal`.
    """
    valor = precio.get("precio")
    if not _es_numero(valor):
        return precio
    origen = moneda_de(precio)
    if origen == MONEDA_BASE_PRECIOS:
        return precio
    if origen not in tasas or MONEDA_BASE_PRECIOS not in tasas:
        return precio
    precio["precioOriginal"] = valor
    precio["monedaOriginal"] = origen
    precio["precio"] = redondear(convertir(valor, origen, MONEDA_BASE_PRECIOS, tasas), MONEDA_BASE_PRECIOS)
    precio["moneda"] = MONEDA_BASE_PRECIOS
    return precio


def normalizar_precios_juegos(col, tasas: dict, simular: bool = False, lote: int = 500) -> dict:
    """
    Deja en COP todos los precios de la colección de juegos, partiendo SIEMPRE del precio original de la
    tienda (si lo hay). Solo escribe lo que cambió. Usa arrayFilters para tocar únicamente la tienda
    afectada: si un scraper actualiza otra tienda del mismo juego a la vez, no se pisan.
    También elimina el campo `conversiones` de versiones anteriores de este script.
    """
    resumen = {"juegos": 0, "actualizados": 0, "sin_cambios": 0, "omitidos": 0}
    campos = ("precio", "moneda", "precioOriginal", "monedaOriginal")
    ops: list[UpdateOne] = []

    def volcar():
        if ops and not simular:
            col.bulk_write(ops, ordered=False)
        ops.clear()

    for juego in col.find({"precios.0": {"$exists": True}}, {"precios": 1}):
        resumen["juegos"] += 1
        for p in juego.get("precios") or []:
            if not isinstance(p, dict) or not p.get("plataforma"):
                resumen["omitidos"] += 1
                continue
            nuevo = copy.deepcopy(p)
            nuevo.pop("conversiones", None)
            if "precioOriginal" in nuevo and "monedaOriginal" in nuevo:     # volver al dato de la tienda
                nuevo["precio"] = nuevo.pop("precioOriginal")
                nuevo["moneda"] = nuevo.pop("monedaOriginal")
            if not _es_numero(nuevo.get("precio")) or moneda_de(nuevo) not in tasas:
                resumen["omitidos"] += 1
                continue
            fijar_en_cop(nuevo, tasas)

            set_ = {f"precios.$[p].{k}": nuevo[k] for k in campos if k in nuevo and p.get(k) != nuevo[k]}
            unset_ = {f"precios.$[p].{k}": "" for k in (*campos, "conversiones") if k not in nuevo and k in p}
            if not set_ and not unset_:
                resumen["sin_cambios"] += 1
                continue
            update = {}
            if set_:
                update["$set"] = set_
            if unset_:
                update["$unset"] = unset_
            resumen["actualizados"] += 1
            ops.append(UpdateOne({"_id": juego["_id"]}, update,
                                 array_filters=[{"p.plataforma": p["plataforma"]}]))
            if len(ops) >= lote:
                volcar()
    volcar()
    return resumen


def revertir_moneda(col, simular: bool = False) -> int:
    """Restaura precio y moneda tal como los entregó cada tienda (deshace la normalización a COP)."""
    ops = []
    for juego in col.find({"precios.precioOriginal": {"$exists": True}}, {"precios": 1}):
        for p in juego.get("precios") or []:
            if isinstance(p, dict) and "precioOriginal" in p and p.get("plataforma"):
                ops.append(UpdateOne(
                    {"_id": juego["_id"]},
                    {"$set": {"precios.$[p].precio": p["precioOriginal"],
                              "precios.$[p].moneda": p.get("monedaOriginal") or MONEDA_BASE_PRECIOS},
                     "$unset": {"precios.$[p].precioOriginal": "", "precios.$[p].monedaOriginal": ""}},
                    array_filters=[{"p.plataforma": p["plataforma"]}]))
    if ops and not simular:
        col.bulk_write(ops, ordered=False)
    return len(ops)


# --- Utilidad para los scrapers (steam/gog/epic vía juegos_comun y buscar_json) ---------
_cache_tasas: dict = {"t": 0.0, "tasas": None}


def tasas_vigentes(db, ttl: int = 600) -> dict | None:
    """
    Tasas guardadas en Mongo (con caché de `ttl` segundos). Si todavía no hay ninguna, intenta
    descargarlas y guardarlas en ese momento, para que el primer scrapeo ya salga en COP.
    Devuelve None si no hay forma de conseguirlas (el scraper sigue sin convertir).
    """
    if time.time() - _cache_tasas["t"] <= ttl:
        return _cache_tasas["tasas"]
    tasas = (cargar_tasas(db) or {}).get("tasas")
    if not tasas:
        try:
            tasas, meta = descargar_tasas(lista_monedas(MONEDAS_POR_DEFECTO))
            guardar_tasas(db, tasas, meta)
            log("💱 No había tasas guardadas: las descargué ahora.")
        except Exception as e:
            print(f"⚠️  no se pudieron obtener tasas de cambio: {type(e).__name__}: {e}")
            tasas = None
    _cache_tasas.update(t=time.time(), tasas=tasas)
    return tasas


def normalizar_precio_scrapeado(precio: dict | None, db) -> dict | None:
    """
    Pasa a COP un precio recién scrapeado (p. ej. GOG en USD), justo antes de guardarlo.
    Nunca rompe al scraper: si no hay tasas o algo falla, devuelve el precio como vino.
    """
    if not precio:
        return precio
    try:
        tasas = tasas_vigentes(db)
        if tasas:
            fijar_en_cop(precio, tasas)
        elif moneda_de(precio) != MONEDA_BASE_PRECIOS:
            print(f"⚠️  precio en {moneda_de(precio)} sin convertir (no hay tasas); "
                  f"ejecuta tasas_cambio.py para corregirlo")
    except Exception as e:
        print(f"⚠️  no se pudo convertir el precio a COP: {type(e).__name__}: {e}")
    return precio


# =============================================================================
# CICLO PRINCIPAL
# =============================================================================
def actualizar(db, monedas: list[str], args) -> bool:
    """Una pasada: descargar -> guardar -> normalizar precios a COP. Devuelve True si salió bien."""
    anterior = cargar_tasas(db)
    try:
        tasas, meta = descargar_tasas(monedas)
    except RuntimeError as e:
        log(f"❌ {e}")
        return False

    tasas = filtrar_saltos(tasas, (anterior or {}).get("tasas"), args.forzar)
    if not tasas:
        log("❌ Todas las tasas fueron descartadas por sospechosas; no guardo nada.")
        return False

    resumen_tasas = ", ".join(f"{m} {v:,.4f}" for m, v in sorted(tasas.items()))
    if args.simular:
        log(f"🧪 [simulación] 1 {BASE} = {resumen_tasas}")
    else:
        guardar_tasas(db, tasas, meta, not args.sin_historial)
        _cache_tasas.update(t=0.0, tasas=None)
        log(f"💱 Tasas guardadas ({meta['fuente']}): 1 {BASE} = {resumen_tasas}")

    if args.solo_tasas:
        return True

    vigentes = tasas if args.simular else (cargar_tasas(db) or {}).get("tasas", tasas)
    r = normalizar_precios_juegos(db[COL_JUEGOS], vigentes, args.simular)
    log(f"🎮 Precios en COP: {r['actualizados']} actualizados, {r['sin_cambios']} sin cambios, "
        f"{r['omitidos']} omitidos ({r['juegos']} juegos).")
    return True


def esperar(segundos: float) -> None:
    """sleep en tramos cortos para que Ctrl+C responda al instante."""
    fin = time.time() + segundos
    while (resto := fin - time.time()) > 0:
        time.sleep(min(resto, 5))


def bucle(db, monedas: list[str], args) -> None:
    horas = max(args.cada, MIN_HORAS)
    if args.cada < MIN_HORAS:
        log(f"ℹ️  El API solo se actualiza 1 vez al día; uso {MIN_HORAS:g} h como mínimo.")
    log(f"⏱️  Modo continuo: actualizo cada {horas:g} h. Ctrl+C para detener.")
    fallos = 0
    while True:
        try:
            ok = actualizar(db, monedas, args)
        except Exception as e:  # Mongo caído, red, etc.: se reintenta sin matar el proceso
            ok = False
            log(f"❌ {type(e).__name__}: {e}")
        fallos = 0 if ok else fallos + 1
        # Tras un fallo se reintenta antes (máx. cada 30 min, 20 min como mínimo si fue un 429).
        espera = horas * 3600 if ok else min(horas * 3600, max(ESPERA_429, 600 * fallos))
        log(f"😴 Próxima actualización en {espera / 3600:.1f} h ({datetime.now() + timedelta(seconds=espera):%Y-%m-%d %H:%M})")
        esperar(espera)


def mostrar(db) -> None:
    doc = cargar_tasas(db)
    if not doc:
        print("Todavía no hay tasas guardadas. Ejecuta: python tasas_cambio.py --solo-tasas")
        return
    act = doc.get("actualizadoEn")
    if act and act.tzinfo is None:
        act = act.replace(tzinfo=timezone.utc)
    edad = f" (hace {(datetime.now(timezone.utc) - act).total_seconds() / 3600:.1f} h)" if act else ""
    print(f"Base: {doc.get('base')} | fuente: {doc.get('fuente')} | actualizado: {act}{edad}")
    for m, v in sorted((doc.get("tasas") or {}).items()):
        print(f"  1 {doc.get('base')} = {v:>14,.4f} {m}")
    if doc.get("atribucion"):
        print(f"\n{doc['atribucion']}")


def main() -> None:
    ap = argparse.ArgumentParser(description="Descarga tasas de cambio, las guarda en MongoDB y deja los precios en COP.")
    ap.add_argument("--monedas", default=MONEDAS_POR_DEFECTO,
                    help=f"Monedas a mantener, separadas por coma (por defecto: {MONEDAS_POR_DEFECTO})")
    ap.add_argument("--cada", type=float, metavar="HORAS", help="Repite la actualización cada N horas (modo continuo)")
    ap.add_argument("--solo-tasas", action="store_true", help="Guarda las tasas pero no toca los juegos")
    ap.add_argument("--revertir", action="store_true",
                    help="Deja los precios como los entregó cada tienda (deshace la conversión a COP) y termina")
    ap.add_argument("--ver", action="store_true", help="Muestra las tasas guardadas y termina")
    ap.add_argument("--convertir", type=float, metavar="MONTO", help="Convierte un monto con las tasas guardadas")
    ap.add_argument("--de", default=MONEDA_BASE_PRECIOS, help="Moneda de origen para --convertir")
    ap.add_argument("--a", default="USD", help="Moneda de destino para --convertir")
    ap.add_argument("--sin-historial", action="store_true", help="No guarda foto en el historial")
    ap.add_argument("--dias-historial", type=int, default=365, help="Días que se conserva el historial")
    ap.add_argument("--forzar", action="store_true", help="Acepta tasas aunque cambien más de 50 %% de golpe")
    ap.add_argument("--simular", action="store_true", help="No escribe en MongoDB, solo muestra")
    args = ap.parse_args()

    try:
        monedas = lista_monedas(args.monedas)
    except ValueError as e:
        ap.error(str(e))

    db = conectar()

    if args.ver:
        return mostrar(db)
    if args.convertir is not None:
        doc = cargar_tasas(db)
        if not doc:
            sys.exit("No hay tasas guardadas. Ejecuta primero: python tasas_cambio.py --solo-tasas")
        try:
            res = convertir(args.convertir, args.de, args.a, doc["tasas"])
        except KeyError as e:
            sys.exit(f"No tengo tasa para {e}. Monedas guardadas: {', '.join(sorted(doc['tasas']))}")
        print(f"{args.convertir:,.2f} {args.de.upper()} = {redondear(res, args.a.upper()):,} {args.a.upper()}")
        return
    if args.revertir:
        n = revertir_moneda(db[COL_JUEGOS], args.simular)
        print(f"Precios restaurados a la moneda original de la tienda: {n}{' (simulación)' if args.simular else ''}")
        return

    if not args.simular:
        preparar_indices(db, args.dias_historial)

    if args.cada:
        try:
            bucle(db, monedas, args)
        except KeyboardInterrupt:
            log("⏹️  Detenido por el usuario.")
        return

    if not actualizar(db, monedas, args):
        sys.exit(1)


if __name__ == "__main__":
    main()