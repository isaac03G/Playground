"""
cargar_demo.py - DEMO: simula historial de precios para los juegos REALES de tu catalogo, entrena el
modelo con el y publica las predicciones para que la web muestre el chip «Posible descuento pronto».

    python cargar_demo.py            # crea la demo
    python cargar_demo.py --limpiar  # la borra (deja la web como estaba)

OJO: el historial es INVENTADO (se guarda aparte, en precios_historial_demo, sin tocar el real).
Solo sirve para mostrar el flujo completo. No son predicciones reales.
"""
import os, sys, math, runpy
from datetime import datetime, timezone
import numpy as np, pandas as pd
from dotenv import load_dotenv
from pymongo import MongoClient

load_dotenv()
db = MongoClient(os.getenv("MONGO_URI", "mongodb://localhost:27017"))[os.getenv("MONGO_DB", "playground")]
DEMO, RELLENO, DIAS = "precios_historial_demo", 250, 730
TIENDAS = ["Steam", "GOG", "Epic Games"]

if "--limpiar" in sys.argv:
    db[DEMO].drop(); db["predicciones_descuento"].delete_many({}); db["predicciones_descuento_log"].delete_many({})
    print("Demo eliminada: precios_historial_demo, predicciones_descuento y predicciones_descuento_log vacias.")
    sys.exit()

rng = np.random.default_rng(11)
fin = pd.Timestamp(datetime.now(timezone.utc).date())
ini = fin - pd.Timedelta(days=DIAS)
fechas = pd.date_range(ini, fin)
temporadas = [pd.Timestamp(y, m, d) for y in (fin.year - 2, fin.year - 1, fin.year) for m, d in
              [(3, 20), (6, 25), (11, 25), (12, 22)]]
temporadas = [t for t in temporadas if ini < t < fin]

def simular(jid, nombre, tiendas_precio):
    """tiendas_precio: {tienda: precio_base}. Devuelve filas de cambios de precio."""
    propension = rng.beta(1.5, 3)
    tiendas = list(tiendas_precio)
    inicio = ini + pd.Timedelta(days=int(rng.integers(0, 90)))
    ventas = {t: [] for t in tiendas}
    fechas_venta = [d + pd.Timedelta(days=int(rng.integers(-3, 4))) for d in temporadas
                    if rng.random() < 0.4 + 0.5 * propension]
    fechas_venta += [fechas[int(i)] for i in rng.integers(0, len(fechas), rng.poisson(1 + 6 * propension))]
    for f in fechas_venta:
        desc, dur, lider = float(rng.choice([.1, .2, .25, .33, .4, .5, .6, .75])), int(rng.integers(5, 15)), rng.choice(tiendas)
        for t in tiendas:
            if t == lider:
                ventas[t].append((f, dur, desc))
            elif rng.random() < 0.6:
                ventas[t].append((f + pd.Timedelta(days=int(rng.integers(0, 6))), dur, desc * rng.uniform(.7, 1.0)))
    filas = []
    for t in tiendas:
        base = float(tiendas_precio[t])
        s = pd.Series(base, index=fechas)
        for f, dur, desc in ventas[t]:
            s.loc[f:f + pd.Timedelta(days=dur)] = round(base * (1 - desc) / 100) * 100
        s = s[s.index >= inicio]
        prev = None
        for f, p in s.items():
            if prev is None:
                filas.append((t, p, f, "base"))
            elif abs(p - prev) / max(prev, 1) > 0.001:
                filas.append((t, p, f, "bajo" if p < prev else "subio"))
            prev = p
        # (jid, nombre) se agrega abajo
    return [{"juegoId": jid, "nombre": nombre, "plataforma": t, "precioCOP": float(p),
             "fecha": f.to_pydatetime(), "motivo": m} for t, p, f, m in filas]

reales = list(db["juegos"].find({}, {"nombre": 1, "precios": 1}))
if not reales:
    sys.exit("La coleccion juegos esta vacia: carga juegos primero (python juegos_comun.py --cantidad 20).")
filas, ids_reales = [], set()
for d in reales:
    tp = {p["plataforma"]: (p.get("precio") or 50000) for p in d.get("precios", []) if p.get("plataforma")}
    if not tp:
        tp = {"Steam": 50000}
    ids_reales.add(str(d["_id"]))
    filas += simular(d["_id"], d.get("nombre"), tp)
for i in range(RELLENO):        # juegos ficticios: solo dan volumen para entrenar
    base = float(np.round(np.exp(rng.normal(11.0, .6)) / 1000) * 1000)
    filas += simular(f"demo-{i}", f"Relleno {i}", {t: base * rng.uniform(.9, 1.1) for t in TIENDAS if rng.random() < .85} or {"Steam": base})
db[DEMO].drop(); db[DEMO].insert_many(filas)
print(f"Historial simulado: {len(reales)} juegos reales + {RELLENO} de relleno, {len(filas)} cambios de precio.")

# entrenar y publicar con el predictor (leyendo la coleccion demo)
os.environ["HISTORIAL_COLECCION"] = DEMO
sys.argv = ["predictor.py", "--forzar"]
try:
    runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), "predictor.py"), run_name="__main__")
except SystemExit as e:
    if e.code not in (None, 0):
        sys.exit(e.code)

# dejar solo los juegos reales y marcar como «probables» el 25 % con mayor probabilidad (minimo 3)
col = db["predicciones_descuento"]
col.delete_many({"juegoId": {"$nin": list(ids_reales)}})
reales_pred = list(col.find({}, {"juegoId": 1, "probabilidad": 1}).sort("probabilidad", -1))
por_juego = {}
for p in reales_pred:
    por_juego.setdefault(p["juegoId"], p["probabilidad"])
top = sorted(por_juego, key=por_juego.get, reverse=True)[:max(3, math.ceil(0.25 * len(por_juego)))]
col.update_many({}, {"$set": {"probableDescuento": False}})
col.update_many({"juegoId": {"$in": top}}, {"$set": {"probableDescuento": True}})
print(f"\nDEMO LISTA: {len(top)} de {len(por_juego)} juegos reales marcados con «Posible descuento pronto».")
print("Reinicia la app Java (o espera 5 min) y abre http://localhost:3066")
