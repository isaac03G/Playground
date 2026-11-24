#!/usr/bin/env python3
"""
Automatiza los scrapers: ni buscar juegos a mano ni acordarse de actualizar precios.

Qué hace en cada pasada (se pueden activar por separado con --solo):

  1. ACTUALIZAR lo que ya tienes. Recorre los juegos guardados en MongoDB, empezando por los que
     llevan más tiempo sin revisarse, los consulta de nuevo en Steam, GOG y Epic y:
        - actualiza el precio de cada tienda;
        - detecta si BAJÓ o SUBIÓ y lo anota en el propio precio (precioAnterior, descuento,
          fechaCambio) para que la web pueda mostrar "↓ -25 %";
        - guarda cada cambio en la colección `precios_historial` (sirve para gráficas a futuro);
        - agrega una tienda nueva si el juego apareció en una donde antes no estaba;
        - rellena descripción/imagen/género/desarrolladora si estaban vacíos.
  2. DESCUBRIR juegos nuevos. Llama a la búsqueda aleatoria de juegos_comun.py para ir ampliando
     el catálogo sin que nadie escriba nombres.

Para no pasarse de los límites de las tiendas, cada pasada revisa solo un LOTE de juegos
(los más "viejos") y deja para la siguiente pasada a los demás. Con un catálogo grande se va
cubriendo poco a poco, siempre empezando por los más desactualizados.

Uso:
    python actualizador.py                          # una pasada: actualiza + descubre
    python actualizador.py --cada 6 --con-tasas     # lo repite cada 6 h y también refresca las tasas de cambio
    python actualizador.py --solo actualizar        # solo revisar precios de los juegos guardados
    python actualizador.py --solo descubrir --nuevos 20
    python actualizador.py --lote 100 --antiguedad 12   # hasta 100 juegos que no se revisen hace +12 h
    python actualizador.py --juego "witcher 3"      # revisa ya mismo uno o varios juegos guardados
    python actualizador.py --simular                # muestra qué haría, sin escribir en MongoDB
    python actualizador.py --acortar                # recorta las descripciones largas YA guardadas (usa --simular para ver)
    python actualizador.py --sin-fecha --lote 300   # busca la fecha de lanzamiento de los juegos que no la tienen
    python actualizador.py --ver                    # estado del catálogo y últimos cambios de precio

Qué se guarda en cada elemento de `precios` (además de lo que ya había):
    ultimaRevision   cuándo se verificó ese precio por última vez
    precioAnterior   precio (COP) que tenía antes del último cambio
    descuento        % que cambió: positivo = bajó, negativo = subió
    fechaCambio      cuándo cambió por última vez

Nota sobre precios convertidos (GOG llega en USD): para decidir si un precio cambió se compara el
valor que entrega la tienda (p. ej. 9.99 USD), no los pesos. Así una variación del dólar no se
confunde con una oferta de la tienda.

Los textos (descripción, imagen...) solo se rellenan si estaban vacíos, para no pisar lo que el
administrador haya editado a mano. Usa --sobrescribir si quieres que la tienda mande.

Dependencias: las mismas de los otros scripts (pip install requests pymongo python-dotenv).
"""

from __future__ import annotations

import argparse
import copy
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import fechas
import juegos_comun as jc

try:                       # opcional: sin él los precios en USD (GOG) se guardan sin convertir
    import tasas_cambio
except ImportError:        # pragma: no cover
    tasas_cambio = None

COL_HISTORIAL = os.getenv("HISTORIAL_COLECCION", "precios_historial")
TOLERANCIA = 0.005         # diferencias menores a medio centavo no cuentan como cambio
MAX_FALLOS_SEGUIDOS = 3    # una tienda que falla 3 veces seguidas se apaga durante esa pasada
MIN_HORAS_CICLO = 0.25     # con --cada, nunca más seguido que cada 15 minutos


# ------------------------------------------------------------------ utilidades
def log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {msg}", flush=True)


def _utf8() -> None:
    """Evita UnicodeEncodeError con emojis cuando la salida va a un archivo de log (Windows)."""
    for flujo in (sys.stdout, sys.stderr):
        try:
            flujo.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def _utc(dt):
    """pymongo devuelve fechas sin zona horaria; las tratamos como UTC para poder compararlas."""
    if dt is None:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def _cop(valor: float) -> int:
    return int(round(valor))


def valor_tienda(precio: dict) -> tuple:
    """(valor, moneda) tal como los entregó la tienda: si se convirtió a COP, se usa el original."""
    if precio.get("precioOriginal") is not None and precio.get("monedaOriginal"):
        return precio["precioOriginal"], str(precio["monedaOriginal"]).upper()
    return precio.get("precio"), (precio.get("moneda") or "COP").upper()


# ------------------------------------------------------- detección de cambios
def comparar(existente: dict | None, crudo: dict, nuevo: dict) -> dict:
    """
    Compara el precio guardado de una tienda con el recién consultado.

    existente: elemento de `precios` que ya estaba guardado (o None si esa tienda es nueva)
    crudo:     precio nuevo tal como lo entregó la tienda (moneda original)
    nuevo:     el mismo precio ya convertido a COP

    Devuelve {"tipo": "nuevo" | "igual" | "bajo" | "subio",
              "porcentaje": int | None,        # positivo = bajó, negativo = subió
              "anterior_cop": int | None}      # precio anterior expresado en COP de hoy
    """
    if existente is None or existente.get("precio") is None:
        return {"tipo": "nuevo", "porcentaje": None, "anterior_cop": None}

    viejo_tienda, moneda_vieja = valor_tienda(existente)
    nuevo_tienda, moneda_nueva = crudo["precio"], (crudo.get("moneda") or "COP").upper()
    if viejo_tienda is None:
        return {"tipo": "nuevo", "porcentaje": None, "anterior_cop": None}

    misma_moneda = moneda_vieja == moneda_nueva
    if misma_moneda:
        antes, ahora = float(viejo_tienda), float(nuevo_tienda)
    else:                                        # la tienda cambió de moneda: se comparan los pesos
        antes, ahora = float(existente["precio"]), float(nuevo["precio"])

    if abs(antes - ahora) <= TOLERANCIA:
        return {"tipo": "igual", "porcentaje": None, "anterior_cop": None}

    porcentaje = round((antes - ahora) / antes * 100) if antes > 0 else None
    if misma_moneda and ahora > 0:               # llevar el precio anterior a COP con la tasa de hoy
        anterior_cop = _cop(float(nuevo["precio"]) * antes / ahora)
    else:
        anterior_cop = _cop(float(existente["precio"]))
    return {"tipo": "bajo" if ahora < antes else "subio",
            "porcentaje": porcentaje, "anterior_cop": anterior_cop}


def _campos_precio(nuevo: dict, ahora: datetime, cambio: dict) -> tuple[dict, dict]:
    """
    ($set, $unset) para actualizar UNA tienda de `precios` sin borrar el resto de sus campos
    (precioAnterior, fechaCambio... de cambios anteriores se conservan si el precio no cambió).
    """
    set_ = {
        "precios.$.precio": nuevo["precio"],
        "precios.$.moneda": nuevo["moneda"],
        "precios.$.esGratis": nuevo["esGratis"],
        "precios.$.ultimaRevision": ahora,
    }
    unset_: dict = {}
    if nuevo.get("url"):                         # enlace directo a la tienda (si la tienda no lo da, se conserva el anterior)
        set_["precios.$.url"] = nuevo["url"]
    for k in ("precioOriginal", "monedaOriginal"):
        if nuevo.get(k) is not None:
            set_[f"precios.$.{k}"] = nuevo[k]
        else:
            unset_[f"precios.$.{k}"] = ""
    if cambio["tipo"] in ("bajo", "subio"):
        set_["precios.$.precioAnterior"] = cambio["anterior_cop"]
        set_["precios.$.fechaCambio"] = ahora
        if cambio["porcentaje"] is None:
            unset_["precios.$.descuento"] = ""
        else:
            set_["precios.$.descuento"] = cambio["porcentaje"]
    elif cambio["tipo"] == "nuevo":
        set_["precios.$.fechaCambio"] = ahora
    return set_, unset_


def _historial(col):
    return col.database[COL_HISTORIAL]


def _anotar(hist, doc: dict, existente: dict | None, crudo: dict, nuevo: dict, cambio: dict,
            ahora: datetime) -> None:
    """Deja constancia en `precios_historial`. Solo se escribe cuando hay algo que contar."""
    tienda = nuevo["plataforma"]
    hay_previo = hist.find_one({"juegoId": doc["_id"], "plataforma": tienda}, {"_id": 1}) is not None

    def fila(valor, moneda, cop, gratis, fecha, motivo):
        return {"juegoId": doc["_id"], "nombre": doc.get("nombre"), "plataforma": tienda,
                "precio": valor, "moneda": moneda, "precioCOP": cop, "esGratis": bool(gratis),
                "fecha": fecha, "motivo": motivo}

    if cambio["tipo"] in ("bajo", "subio"):
        if not hay_previo and existente:         # punto de partida, para que la gráfica tenga de dónde arrancar
            v, m = valor_tienda(existente)
            hist.insert_one(fila(v, m, existente.get("precio"), existente.get("esGratis"),
                                 _utc(existente.get("ultimaRevision") or doc.get("fechaActualizacion")) or ahora,
                                 "base"))
        hist.insert_one(fila(crudo["precio"], crudo.get("moneda") or "COP", nuevo["precio"],
                             nuevo["esGratis"], ahora, cambio["tipo"]))
    elif not hay_previo:                         # primera vez que se ve este precio
        hist.insert_one(fila(crudo["precio"], crudo.get("moneda") or "COP", nuevo["precio"],
                             nuevo["esGratis"], ahora, "base"))


# ----------------------------------------------------------- revisar UN juego
def revisar_juego(col, doc: dict, tiendas: list[str], umbral: float = 0.92, fallos: dict | None = None,
                  simular: bool = False, sobrescribir: bool = False, consultar=None) -> dict:
    """
    Consulta el juego en las tiendas y deja MongoDB al día.

    Devuelve {"nombre", "estados": {tienda: estado}, "cambios": [...], "campos": [...], "revisado": bool}
    donde estado es: igual | bajo | subio | nuevo | sin_precio | no_encontrado | error.
    `consultar` permite sustituir la consulta a las tiendas (se usa para probar sin internet).
    """
    consultar = consultar or jc._consultar_tiendas
    fallos = fallos if fallos is not None else {}
    ahora = datetime.now(timezone.utc)
    nombre = doc["nombre"]
    antes_fallos = dict(fallos)

    resultados = consultar({"tienda": None, "nombre": nombre, "id": None, "ref": None},
                           tiendas, umbral, fallos)

    existentes = {p.get("plataforma"): p for p in (doc.get("precios") or [])
                  if isinstance(p, dict) and p.get("plataforma")}
    hist = _historial(col)
    estados: dict = {}
    cambios: list = []
    hubo_cambio = False
    respondieron = 0

    for tienda in tiendas:
        if fallos.get(tienda, 0) > antes_fallos.get(tienda, 0):
            estados[tienda] = "error"            # la tienda falló: no se toca nada de ella
            continue
        respondieron += 1
        resultado = resultados.get(tienda)
        if not resultado:
            estados[tienda] = "no_encontrado"
            continue
        _, precio = resultado
        if precio is None:
            estados[tienda] = "sin_precio"       # está en la tienda pero sin precio: se conserva el anterior
            continue

        crudo = jc.normalizar_precio(precio)
        nuevo = copy.deepcopy(crudo)
        if tasas_cambio:
            tasas_cambio.normalizar_precio_scrapeado(nuevo, col.database)   # GOG: USD -> COP
        existente = existentes.get(tienda)
        cambio = comparar(existente, crudo, nuevo)
        estados[tienda] = cambio["tipo"]

        if cambio["tipo"] != "igual":
            hubo_cambio = True
            cambios.append({"tienda": tienda, "tipo": cambio["tipo"], "porcentaje": cambio["porcentaje"],
                            "antes": existente.get("precio") if existente else None,
                            "despues": nuevo["precio"], "moneda": nuevo["moneda"]})
        if simular:
            continue

        _anotar(hist, doc, existente, crudo, nuevo, cambio, ahora)
        if existente is None:
            entrada = {**nuevo, "ultimaRevision": ahora, "fechaCambio": ahora}
            col.update_one({"_id": doc["_id"]}, {"$push": {"precios": entrada}})
            continue
        set_, unset_ = _campos_precio(nuevo, ahora, cambio)
        update = {"$set": set_}
        if unset_:
            update["$unset"] = unset_
        r = col.update_one({"_id": doc["_id"], "precios.plataforma": tienda}, update)
        if r.matched_count == 0:                 # el administrador quitó esa tienda mientras tanto
            col.update_one({"_id": doc["_id"]},
                           {"$push": {"precios": {**nuevo, "ultimaRevision": ahora, "fechaCambio": ahora}}})

    # Contenido (descripción, imagen...): solo si estaba vacío, salvo --sobrescribir
    campos: dict = {}
    contenido, _ = jc._fusionar({t: r for t, r in resultados.items() if r}) if any(resultados.values()) else ({}, [])
    for k in jc.CAMPOS_CONTENIDO:
        valor = contenido.get(k)
        if valor and valor != doc.get(k) and (sobrescribir or not doc.get(k)):
            campos[k] = valor

    # Portada 3:4: juegos nuevos/sin procesar, o si cambió imagenUrl. No toca precios.
    ref_img = {"imagenUrl": campos.get("imagenUrl") or doc.get("imagenUrl"),
               "imagenVerticalUrl": campos.get("imagenVerticalUrl") or doc.get("imagenVerticalUrl")}
    extra_img = jc.adjuntar_imagen_catalogo(ref_img, doc)
    for k, v in extra_img.items():
        if v and v != doc.get(k):
            campos[k] = v

    revisado = respondieron > 0                  # si TODAS las tiendas fallaron, se reintenta en la próxima pasada
    if not simular and (revisado or campos):
        set_doc = dict(campos)
        if revisado:
            set_doc["fechaRevision"] = ahora
        if hubo_cambio or campos:
            set_doc["fechaActualizacion"] = ahora
        col.update_one({"_id": doc["_id"]}, {"$set": set_doc})

    return {"nombre": nombre, "estados": estados, "cambios": cambios,
            "campos": sorted(campos), "revisado": revisado}


# ------------------------------------------------------- ciclo de actualización
def pendientes(col, antiguedad_horas: float, lote: int) -> list:
    """Ids de los juegos que llevan más de `antiguedad_horas` sin revisarse, los más viejos primero."""
    limite = datetime.now(timezone.utc) - timedelta(hours=antiguedad_horas)
    minimo = datetime.min.replace(tzinfo=timezone.utc)
    candidatos = []
    for d in col.find({"nombre": {"$exists": True}}, {"nombre": 1, "fechaRevision": 1, "fechaActualizacion": 1}):
        if not d.get("nombre"):
            continue
        ultima = _utc(d.get("fechaRevision") or d.get("fechaActualizacion"))
        if ultima is None or ultima < limite:
            candidatos.append((ultima or minimo, d["_id"]))
    candidatos.sort(key=lambda c: c[0])
    return [i for _, i in candidatos[:lote]]


def _linea_cambio(c: dict) -> str:
    if c["tipo"] == "nuevo":
        return f"{c['tienda']}: ahora {c['despues']:,} {c['moneda']}".replace(",", ".")
    flecha = "↓" if c["tipo"] == "bajo" else "↑"
    pct = f" ({abs(c['porcentaje'])}%)" if c.get("porcentaje") else ""
    return f"{c['tienda']}: {c['antes']:,} → {c['despues']:,} {c['moneda']} {flecha}{pct}".replace(",", ".")


def actualizar_existentes(col, tiendas: list[str], lote: int = 60, antiguedad: float = 12.0,
                          pausa: float = 2.0, umbral: float = 0.92, simular: bool = False,
                          sobrescribir: bool = False, ids: list | None = None, consultar=None) -> dict:
    """Una pasada sobre los juegos guardados. `ids` fuerza qué juegos revisar (ignora la antigüedad)."""
    tiendas = list(tiendas)
    manual = ids is not None                     # revisión pedida con --juego: se informa también "sin cambios"
    ids = ids if ids is not None else pendientes(col, antiguedad, lote)
    resumen = {"pendientes": len(ids), "revisados": 0, "con_cambios": 0, "bajaron": 0, "subieron": 0,
               "tiendas_nuevas": 0, "sin_respuesta": 0, "errores": 0}
    if not ids:
        log("✅ Nada pendiente: todos los juegos se revisaron hace poco.")
        return resumen
    log(f"🔄 Revisando {len(ids)} juego(s) en {', '.join(tiendas)}...")

    fallos: dict = {}
    try:
        for n, _id in enumerate(ids, 1):
            if not tiendas:
                log("⛔ Todas las tiendas están fallando; termino esta pasada.")
                break
            doc = col.find_one({"_id": _id})
            if not doc or not doc.get("nombre"):
                continue
            try:
                r = revisar_juego(col, doc, tiendas, umbral, fallos, simular, sobrescribir, consultar)
            except Exception as e:               # un juego problemático no debe frenar a los demás
                resumen["errores"] += 1
                log(f"⚠️  {doc.get('nombre')}: {type(e).__name__}: {e}")
                continue

            if r["revisado"]:
                resumen["revisados"] += 1
            else:
                resumen["sin_respuesta"] += 1
            if r["cambios"]:
                resumen["con_cambios"] += 1
            for c in r["cambios"]:
                resumen["bajaron" if c["tipo"] == "bajo" else "subieron" if c["tipo"] == "subio"
                        else "tiendas_nuevas"] += 1
                log(f"   💱 {r['nombre']} — {_linea_cambio(c)}")
            if r["campos"]:
                log(f"   📝 {r['nombre']} — completado: {', '.join(r['campos'])}")
            if manual and not r["cambios"] and not r["campos"]:
                estado = ", ".join(f"{t}: {e}" for t, e in r["estados"].items())
                log(f"   ✓ {r['nombre']} — sin cambios ({estado})")

            for t in [t for t in tiendas if fallos.get(t, 0) >= MAX_FALLOS_SEGUIDOS]:
                tiendas.remove(t)
                log(f"🔌 {t} falló {MAX_FALLOS_SEGUIDOS} veces seguidas; la desactivo durante esta pasada.")
            if n < len(ids) and pausa:
                time.sleep(pausa)
    except KeyboardInterrupt:
        log("⏹️  Interrumpido por el usuario.")
    return resumen


def descubrir_nuevos(col, tiendas: list[str], cantidad: int, pausa: float, umbral: float,
                     simular: bool) -> dict | None:
    """Agrega juegos que no estén en la base de datos (búsqueda aleatoria de juegos_comun.py)."""
    if cantidad <= 0:
        return None
    log(f"🎲 Buscando {cantidad} juego(s) nuevo(s)...")
    try:
        r = jc.buscar_juegos_aleatorios(col, cantidad, pausa, umbral, None, simular, tiendas)
    except ValueError as e:
        log(f"⚠️  No se pudo descubrir juegos: {e}")
        return None
    log(f"🎲 Nuevos: {r['guardados']} guardados, {r['ya_existian']} ya existían, "
        f"{r['no_disponibles']} sin precio, {r['errores']} errores.")
    return r


def actualizar_tasas(db, simular: bool) -> None:
    """Refresca las tasas de cambio y deja los precios en COP (lo mismo que `python tasas_cambio.py`)."""
    if tasas_cambio is None:
        log("ℹ️  tasas_cambio.py no está disponible; me salto las tasas.")
        return
    try:
        if not simular:
            tasas_cambio.preparar_indices(db, 365)
        args = SimpleNamespace(forzar=False, simular=simular, solo_tasas=False, sin_historial=False)
        tasas_cambio.actualizar(db, tasas_cambio.lista_monedas(tasas_cambio.MONEDAS_POR_DEFECTO), args)
    except Exception as e:
        log(f"⚠️  No se pudieron actualizar las tasas: {type(e).__name__}: {e}")


def pasada(col, args, tiendas: list[str]) -> bool:
    """Una pasada completa. Devuelve True si no hubo problemas graves."""
    ok = True
    if args.con_tasas:
        actualizar_tasas(col.database, args.simular)
    if args.solo in (None, "actualizar"):
        r = actualizar_existentes(col, tiendas, args.lote, args.antiguedad, args.pausa, args.umbral,
                                  args.simular, args.sobrescribir)
        log(f"🔄 Resumen: {r['revisados']} revisados · {r['con_cambios']} con cambios "
            f"({r['bajaron']} bajaron, {r['subieron']} subieron, {r['tiendas_nuevas']} tiendas nuevas) · "
            f"{r['sin_respuesta']} sin respuesta · {r['errores']} errores.")
        ok = ok and r["errores"] == 0 and not (r["pendientes"] and r["revisados"] == 0)
    if args.solo in (None, "descubrir"):
        descubrir_nuevos(col, tiendas, args.nuevos, args.pausa, args.umbral, args.simular)
    return ok


def acortar_descripciones(col, simular: bool = False, maximo: int | None = None) -> dict:
    """
    Recorta las descripciones ya guardadas que superen el máximo (fechas.DESCRIPCION_MAX, 300 por defecto).
    Los scrapers solo acortan lo que guardan de ahora en adelante; esto arregla lo anterior.
    """
    r = {"revisadas": 0, "acortadas": 0, "caracteres_ahorrados": 0}
    for d in col.find({"descripcion": {"$exists": True}}, {"nombre": 1, "descripcion": 1}):
        texto = d.get("descripcion")
        if not isinstance(texto, str):
            continue
        r["revisadas"] += 1
        nueva = fechas.resumir(texto, maximo)
        if nueva and nueva != texto.strip() and len(nueva) < len(texto):
            r["acortadas"] += 1
            r["caracteres_ahorrados"] += len(texto) - len(nueva)
            if not simular:
                col.update_one({"_id": d["_id"]}, {"$set": {"descripcion": nueva}})
    return r


def esperar(segundos: float) -> None:
    """sleep en tramos cortos para que Ctrl+C responda al instante."""
    fin = time.time() + segundos
    while (resto := fin - time.time()) > 0:
        time.sleep(min(resto, 5))


def bucle(col, args, tiendas: list[str]) -> None:
    horas = max(args.cada, MIN_HORAS_CICLO)
    if args.cada < MIN_HORAS_CICLO:
        log(f"ℹ️  Uso {MIN_HORAS_CICLO:g} h como mínimo entre pasadas.")
    log(f"⏱️  Modo continuo: una pasada cada {horas:g} h. Ctrl+C para detener.")
    while True:
        try:
            ok = pasada(col, args, tiendas)
        except Exception as e:                   # Mongo caído, red, etc.: se reintenta sin matar el proceso
            ok = False
            log(f"❌ {type(e).__name__}: {e}")
        espera = horas * 3600 if ok else min(horas * 3600, 1800)
        log(f"😴 Próxima pasada en {espera / 3600:.1f} h ({datetime.now() + timedelta(seconds=espera):%Y-%m-%d %H:%M})")
        esperar(espera)


# --------------------------------------------------------------------- estado
def mostrar_estado(col, antiguedad: float) -> None:
    total = col.count_documents({})
    ids = pendientes(col, antiguedad, 10 ** 9)
    ahora = datetime.now(timezone.utc)
    ult24 = sum(1 for d in col.find({"fechaRevision": {"$exists": True}}, {"fechaRevision": 1})
                if (_utc(d["fechaRevision"]) or ahora - timedelta(days=9)) > ahora - timedelta(hours=24))
    print(f"Juegos en el catálogo: {total}")
    print(f"Revisados en las últimas 24 h: {ult24}")
    print(f"Pendientes (sin revisar hace más de {antiguedad:g} h): {len(ids)}")
    print("\nÚltimos cambios de precio:")
    filas = list(_historial(col).find({"motivo": {"$in": ["bajo", "subio"]}}).sort("fecha", -1).limit(10))
    if not filas:
        print("  (todavía no se ha detectado ninguno)")
    for f in filas:
        flecha = "↓ bajó " if f["motivo"] == "bajo" else "↑ subió"
        print(f"  {_utc(f['fecha']):%Y-%m-%d %H:%M}  {flecha}  {f.get('nombre')} · {f.get('plataforma')} "
              f"· {f.get('precio')} {f.get('moneda')}")


def main() -> None:
    _utf8()
    ap = argparse.ArgumentParser(
        description="Mantiene el catálogo al día: actualiza precios de los juegos guardados y descubre juegos nuevos.")
    ap.add_argument("--solo", choices=("actualizar", "descubrir"),
                    help="Hacer solo una de las dos tareas (por defecto, ambas)")
    ap.add_argument("--cada", type=float, metavar="HORAS", help="Repite la pasada cada N horas (modo continuo)")
    ap.add_argument("--lote", type=int, default=60, help="Máximo de juegos guardados a revisar por pasada")
    ap.add_argument("--antiguedad", type=float, default=12.0, metavar="HORAS",
                    help="No vuelve a revisar un juego revisado hace menos de N horas")
    ap.add_argument("--nuevos", type=int, default=5, help="Juegos nuevos que intenta agregar por pasada")
    ap.add_argument("--tiendas", default="steam,gog,epic", help="Tiendas a consultar, separadas por coma")
    ap.add_argument("--pausa", type=float, default=2.0, help="Segundos de espera entre juegos")
    ap.add_argument("--umbral", type=float, default=0.92, help="Similitud mínima de nombres (0-1)")
    ap.add_argument("--juego", "-j", action="append", metavar="NOMBRE",
                    help="Revisa ya mismo este juego guardado (repetible); ignora la antigüedad")
    ap.add_argument("--sobrescribir", action="store_true",
                    help="Reemplaza descripción/imagen/género/desarrolladora aunque ya existan")
    ap.add_argument("--con-tasas", action="store_true",
                    help="Antes de cada pasada refresca las tasas de cambio (como tasas_cambio.py)")
    ap.add_argument("--acortar", action="store_true",
                    help="Recorta las descripciones largas ya guardadas y termina")
    ap.add_argument("--sin-fecha", action="store_true",
                    help="Revisa ya mismo (hasta --lote) los juegos que no tienen fecha de lanzamiento y termina")
    ap.add_argument("--simular", action="store_true", help="No escribe en MongoDB, solo muestra")
    ap.add_argument("--ver", action="store_true", help="Muestra el estado del catálogo y termina")
    args = ap.parse_args()

    try:
        tiendas = [jc.ALIAS_TIENDAS[t.strip().lower()] for t in args.tiendas.split(",") if t.strip()]
    except KeyError as e:
        ap.error(f"tienda desconocida {e}; usa steam, gog y/o epic")
    tiendas = [t for t in jc.TIENDAS if t in tiendas]
    if not tiendas:
        ap.error("indica al menos una tienda")

    col = jc.obtener_coleccion()
    migrados = jc.migrar_claves(col)
    if migrados:
        log(f"Documentos antiguos migrados: {migrados}")

    if args.ver:
        return mostrar_estado(col, args.antiguedad)

    if args.acortar:
        res = acortar_descripciones(col, args.simular)
        log(f"✂️  {res['acortadas']} de {res['revisadas']} descripciones superaban el máximo "
            f"({res['caracteres_ahorrados']:,} caracteres menos){' [simulado]' if args.simular else ''}.".replace(",", "."))
        return

    if args.sin_fecha:
        ids = [d["_id"] for d in col.find({"fechaDeLanzamiento": None}, {"_id": 1}).limit(args.lote)]
        log(f"📅 {len(ids)} juego(s) sin fecha de lanzamiento (hasta --lote {args.lote}).")
        if ids:
            actualizar_existentes(col, tiendas, antiguedad=0, pausa=args.pausa, umbral=args.umbral,
                                  simular=args.simular, sobrescribir=args.sobrescribir, ids=ids)
        return

    if args.juego:
        ids = []
        for nombre in args.juego:
            doc, _ = jc.buscar_existente(col, nombre, args.umbral)
            if doc is None:
                log(f"❌ '{nombre}' no está guardado. Para agregarlo usa: python juegos_comun.py -j \"{nombre}\"")
            else:
                ids.append(doc["_id"])
        if not ids:
            sys.exit(1)
        actualizar_existentes(col, tiendas, antiguedad=0, pausa=args.pausa, umbral=args.umbral,
                              simular=args.simular, sobrescribir=args.sobrescribir, ids=ids)
        return

    if args.cada:
        try:
            bucle(col, args, tiendas)
        except KeyboardInterrupt:
            log("⏹️  Detenido por el usuario.")
        return

    try:
        if not pasada(col, args, tiendas):
            sys.exit(1)
    except KeyboardInterrupt:
        log("⏹️  Interrumpido por el usuario.")


if __name__ == "__main__":
    main()
