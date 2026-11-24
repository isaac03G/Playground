"""
generar_historial.py - Genera un historial de precios de 12 MESES para los juegos del catálogo, para que la
gráfica de «Historial de precios» de la ficha del juego tenga bajadas y subidas que mostrar desde el primer día.

    python generar_historial.py              # genera (o regenera) el historial simulado de todos los juegos
    python generar_historial.py --simular    # solo muestra un resumen, no escribe
    python generar_historial.py --limpiar    # borra SOLO las filas simuladas (las reales no se tocan)
    python generar_historial.py --semilla 7  # otra «versión» del mismo historial simulado

⚠️  IMPORTANTE: estos datos son SIMULADOS (no se puede viajar al pasado para saber cuánto costaba cada juego).
    Siguen el calendario real de rebajas (primavera, verano, otoño y fin de año de Steam; Mega Sale de Epic) y
    terminan exactamente en el precio ACTUAL de cada tienda, pero no son precios históricos reales.
    Cada fila lleva `simulado: true` para poder distinguirla y borrarla. Los juegos que YA tengan historial real
    (lo escribe actualizador.py) NO se simulan: a partir de ahora ese historial real es el que manda.

Es seguro correrlo varias veces: con la misma semilla produce el mismo resultado y reemplaza lo simulado anterior.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import random
from datetime import datetime, timedelta, timezone

from dotenv import load_dotenv
from pymongo import ASCENDING, MongoClient

load_dotenv()

MONGO_URI = os.getenv("MONGO_URI", "mongodb://localhost:27017")
DB_NAME = os.getenv("MONGO_DB", "playground")
COL_JUEGOS = os.getenv("MONGO_COLLECTION", "juegos")
COL_HISTORIAL = os.getenv("HISTORIAL_COLECCION", "precios_historial")

DIAS = 365
HORA_REBAJA = 17          # 17:00 UTC = 10:00 en Seattle, la hora en que Steam empieza/termina sus rebajas

# (mes, día de inicio, duración en días, quién participa)
TEMPORADAS = [
    (2, 5, 7, "todas"),         # Año Nuevo Lunar
    (3, 14, 8, "todas"),        # Rebajas de primavera
    (5, 15, 28, "epic"),        # Mega Sale de Epic
    (6, 25, 14, "todas"),       # Rebajas de verano
    (10, 28, 6, "todas"),       # Halloween
    (11, 25, 8, "todas"),       # Rebajas de otoño / Black Friday
    (12, 19, 15, "todas"),      # Rebajas de invierno
]

# Probabilidad de que cada tienda participe cuando el juego entra en una rebaja de temporada
PARTICIPACION = {"Steam": 1.0, "GOG": 0.7, "Epic Games": 0.45}


def _rng(*partes, semilla: int) -> random.Random:
    """Generador reproducible (hash estable; el hash() de Python cambia en cada ejecución)."""
    h = hashlib.sha256(("|".join(map(str, partes)) + f"|{semilla}").encode()).hexdigest()
    return random.Random(int(h[:16], 16))


def _descuentos(edad_anios: float) -> tuple[list[int], list[int]]:
    """Porcentajes posibles y su peso: los juegos nuevos rebajan poco; los viejos, mucho."""
    if edad_anios < 1:
        return [10, 15, 20, 25, 33, 40], [4, 3, 3, 2, 1, 1]
    if edad_anios < 3:
        return [15, 20, 25, 33, 40, 50, 60], [2, 3, 3, 3, 3, 2, 1]
    return [25, 33, 40, 50, 60, 67, 75, 80, 90], [1, 2, 3, 4, 4, 3, 3, 2, 1]


def _redondear(cop: float) -> float:
    return float(round(cop / 100) * 100)


def _ventas(rng: random.Random, tienda: str, hoy: datetime, ini: datetime, edad: float, propension: float,
            por_temporada: dict) -> list[tuple[datetime, datetime, int]]:
    """Rebajas de UNA tienda: [(inicio, fin_exclusivo, % descuento)]."""
    pcts, pesos = _descuentos(edad)
    ventas = []
    for (anio, mes, dia, quien), base in por_temporada.items():
        if quien == "epic" and tienda != "Epic Games":
            continue
        if not base["entra"]:                           # el juego se salta esta temporada en todas las tiendas
            continue
        if rng.random() > PARTICIPACION[tienda] and quien != "epic":
            continue
        inicio = datetime(anio, mes, dia, HORA_REBAJA, tzinfo=timezone.utc) + timedelta(days=rng.randint(-1, 2))
        dur = base["dur"] + rng.randint(-1, 2)
        pct = max(5, min(90, base["pct"] + rng.choice([-5, 0, 0, 0, 5])))
        ventas.append((inicio, inicio + timedelta(days=dur), pct))

    # rebajas sueltas (ofertas relámpago, fines de semana, promoción del editor)
    for _ in range(rng.choice([0, 0, 1, 1, 2]) + (1 if propension > 0.6 else 0)):
        inicio = ini + timedelta(days=rng.randint(10, DIAS - 20), hours=HORA_REBAJA)
        ventas.append((inicio, inicio + timedelta(days=rng.randint(3, 10)), rng.choices(pcts, pesos)[0]))

    # solo las rebajas que ya terminaron antes de hoy: el precio de hoy es el real, sin rebaja simulada
    return [v for v in ventas if v[0] >= ini and v[1] < hoy - timedelta(days=1)]


def simular_juego(doc: dict, hoy: datetime, semilla: int) -> list[dict]:
    """Filas de precios_historial (una por cambio) para todas las tiendas con precio del juego."""
    jid = doc["_id"]
    rng_juego = _rng(jid, "juego", semilla=semilla)
    ini_ventana = hoy - timedelta(days=DIAS)

    lanz = doc.get("fechaDeLanzamiento")
    if isinstance(lanz, datetime):
        lanz = lanz if lanz.tzinfo else lanz.replace(tzinfo=timezone.utc)
        if lanz > hoy:
            return []                                    # aún no sale
    else:
        lanz = None
    edad = max(0.0, (hoy - lanz).days / 365) if lanz else 3.0
    inicio = max(ini_ventana, lanz) if lanz else ini_ventana
    propension = rng_juego.betavariate(1.6, 2.6)         # qué tan «rebajable» es este juego (0..1)

    # decisiones de temporada comunes a todas las tiendas (así Steam/GOG/Epic rebajan en fechas parecidas)
    por_temporada = {}
    pcts, pesos = _descuentos(edad)
    for anio in (hoy.year - 1, hoy.year):
        for mes, dia, dur, quien in TEMPORADAS:
            por_temporada[(anio, mes, dia, quien)] = {
                "entra": rng_juego.random() < (0.30 + 0.55 * propension),
                "pct": rng_juego.choices(pcts, pesos)[0], "dur": dur}

    filas = []
    for p in doc.get("precios") or []:
        if not isinstance(p, dict) or not p.get("plataforma") or p.get("precio") is None:
            continue
        tienda, actual = p["plataforma"], float(p["precio"])
        gratis = bool(p.get("esGratis")) or actual == 0
        rng = _rng(jid, tienda, semilla=semilla)

        def fila(cop, fecha, motivo):
            return {"juegoId": jid, "nombre": doc.get("nombre"), "plataforma": tienda, "precio": cop,
                    "moneda": "COP", "precioCOP": cop, "esGratis": gratis, "fecha": fecha,
                    "motivo": motivo, "simulado": True}

        if gratis:                                       # gratis: una sola fila, sin rebajas que contar
            filas.append(fila(0.0, inicio, "base"))
            continue

        # ¿hay una rebaja en curso? (el actualizador la marca con precioAnterior + descuento reciente)
        base_final = actual
        en_curso = None
        fc, pa, d = p.get("fechaCambio"), p.get("precioAnterior"), p.get("descuento")
        if isinstance(fc, datetime) and pa and d and d > 0:
            fc = fc if fc.tzinfo else fc.replace(tzinfo=timezone.utc)
            if hoy - fc < timedelta(days=14):
                base_final, en_curso = float(pa), (fc, actual)

        # ~10 % de los juegos subieron de precio de forma permanente en algún momento del año
        sube_dia, factor = None, 1.0
        if rng.random() < 0.10 and (hoy - inicio).days > 90:
            sube_dia = inicio + timedelta(days=rng.randint(45, (hoy - inicio).days - 30))
            factor = rng.uniform(1.08, 1.22)
        base_antes = _redondear(base_final / factor)

        ventas = _ventas(rng, tienda, hoy, inicio, edad, propension, por_temporada)

        previo = None
        dia = inicio.replace(hour=HORA_REBAJA, minute=0, second=0, microsecond=0)
        while dia <= hoy:
            base = base_antes if (sube_dia and dia < sube_dia) else base_final
            pct = max((v[2] for v in ventas if v[0] <= dia < v[1]), default=0)
            precio = _redondear(base * (1 - pct / 100)) if pct else base
            if en_curso and dia >= en_curso[0].replace(minute=0, second=0, microsecond=0):
                precio = en_curso[1]                     # la rebaja real que ya está en la tienda
            if previo is None:
                filas.append(fila(precio, dia, "base"))
            elif abs(precio - previo) / max(previo, 1) > 0.001:
                filas.append(fila(precio, dia, "bajo" if precio < previo else "subio"))
            previo = precio
            dia += timedelta(days=1)
    return filas


def main() -> None:
    ap = argparse.ArgumentParser(description="Genera historial de precios SIMULADO de 12 meses")
    ap.add_argument("--simular", action="store_true", help="No escribe en MongoDB, solo muestra un resumen")
    ap.add_argument("--limpiar", action="store_true", help="Borra solo las filas simuladas y termina")
    ap.add_argument("--semilla", type=int, default=2026, help="Cambia el «azar» (misma semilla = mismo resultado)")
    args = ap.parse_args()

    db = MongoClient(MONGO_URI)[DB_NAME]
    hist = db[COL_HISTORIAL]

    if args.limpiar:
        r = hist.delete_many({"simulado": True})
        print(f"Filas simuladas eliminadas: {r.deleted_count} (el historial real no se tocó).")
        return

    hoy = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    juegos = list(db[COL_JUEGOS].find({}, {"nombre": 1, "precios": 1, "fechaDeLanzamiento": 1}))
    if not juegos:
        raise SystemExit("La colección de juegos está vacía. Primero: python catalogo_conocidos.py")

    # juegos que ya tienen historial REAL: no se simulan
    con_real = set(hist.distinct("juegoId", {"simulado": {"$ne": True}}))

    todas, n_juegos, n_real = [], 0, 0
    ids_a_reemplazar = []
    for doc in juegos:
        if doc["_id"] in con_real:
            n_real += 1
            continue
        filas = simular_juego(doc, hoy, args.semilla)
        if filas:
            n_juegos += 1
            ids_a_reemplazar.append(doc["_id"])
            todas += filas

    bajas = sum(1 for f in todas if f["motivo"] == "bajo")
    suben = sum(1 for f in todas if f["motivo"] == "subio")
    print(f"Juegos en el catálogo: {len(juegos)} · con historial real (no se tocan): {n_real} · simulados: {n_juegos}")
    print(f"Filas: {len(todas)} ({bajas} bajadas, {suben} subidas) · rango: {DIAS} días hasta {hoy:%Y-%m-%d}")

    if args.simular:
        print("(simulación: no se escribió nada)")
        return

    hist.delete_many({"simulado": True, "juegoId": {"$in": ids_a_reemplazar}})
    if todas:
        hist.insert_many(todas, ordered=False)
    hist.create_index([("juegoId", ASCENDING), ("fecha", ASCENDING)])     # la web consulta por juego y fecha
    print("Listo. Abre la ficha de cualquier juego: la gráfica «Historial de precios» ya muestra los 12 meses.")


if __name__ == "__main__":
    main()
