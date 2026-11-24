"""
predictor.py - Predice que juegos bajaran de precio en los proximos 30 dias y lo guarda en MongoDB.
Lee `precios_historial` (lo escribe actualizador.py) y escribe `predicciones_descuento`
(la lee la web Java). Ejecutar 1 vez por semana, desde playground-scraper/:

    python predictor.py              # modo auto: modelo si hay datos suficientes, si no estimacion por reglas
    python predictor.py --simular    # solo muestra, no escribe en Mongo
    python predictor.py --forzar     # guarda el modelo aunque sea poco fiable
    python predictor.py --metodo heuristico   # fuerza la estimacion por reglas (sirve con pocos datos)
    python predictor.py --metodo modelo       # fuerza el modelo (falla si no hay datos suficientes)

Variables opcionales en .env:  PREDICCION_UMBRAL (0.5)  PREDICCION_MIN_AUC (0.6)
Dependencias: pip install pandas numpy scikit-learn pymongo python-dotenv
"""
import os, sys, argparse
from datetime import datetime, timezone
import numpy as np, pandas as pd
from dotenv import load_dotenv
from pymongo import MongoClient
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import make_pipeline
from sklearn.metrics import precision_score, recall_score, f1_score, roc_auc_score

load_dotenv()
ap = argparse.ArgumentParser()
ap.add_argument("--simular", action="store_true")
ap.add_argument("--forzar", action="store_true")
ap.add_argument("--metodo", choices=["auto", "modelo", "heuristico"], default="auto")
args = ap.parse_args()
UMBRAL = float(os.getenv("PREDICCION_UMBRAL", "0.5"))
MIN_AUC = float(os.getenv("PREDICCION_MIN_AUC", "0.6"))
UMBRAL_H = float(os.getenv("PREDICCION_UMBRAL_H", "0.45"))   # umbral del modo por reglas
HORIZON, STEP, MIN_CAMBIO, MIN_HIST, SEED = 30, 7, 0.02, 14, 42
TEMPORADAS_DOY = [79, 176, 329, 356]      # rebajas grandes: 20-mar, 25-jun, 25-nov, 22-dic

# ---------- 1. leer precios_historial de MongoDB ----------
db = MongoClient(os.getenv("MONGO_URI", "mongodb://localhost:27017"))[os.getenv("MONGO_DB", "playground")]
# generar_historial.py marca sus filas con simulado=true (solo sirven para la gráfica): el modelo no se entrena
# con ellas salvo que se pida expresamente con INCLUIR_SIMULADO=1.
_filtro = {} if os.getenv("INCLUIR_SIMULADO") == "1" else {"simulado": {"$ne": True}}
filas = list(db[os.getenv("HISTORIAL_COLECCION", "precios_historial")].find(
    _filtro, {"_id": 0, "juegoId": 1, "nombre": 1, "plataforma": 1, "precioCOP": 1, "fecha": 1}))
df = pd.DataFrame(filas, columns=["juegoId", "nombre", "plataforma", "precioCOP", "fecha"]) if not filas else pd.DataFrame(filas)
df["fecha"] = pd.to_datetime(df["fecha"], utc=True, errors="coerce").dt.tz_convert(None).dt.normalize()
df = df.dropna(subset=["juegoId", "plataforma", "precioCOP", "fecha"]).copy()
df["jid"] = df["juegoId"].astype(str)                 # ObjectId -> texto (igual que Juego.id en Java)
df["plataforma"] = df["plataforma"].astype(str).str.strip()
df["precioCOP"] = df["precioCOP"].astype(float)
class Insuf(Exception):
    """No hay datos suficientes para entrenar el modelo."""

if len(df):
    print(f"Historial: {df.jid.nunique()} juegos, {len(df)} cambios, del {df.fecha.min().date()} al {df.fecha.max().date()}")
else:
    print("Historial: vacio (precios_historial todavia no tiene cambios).")

def entrenar_modelo(df):
    """Random Forest con el historial. Lanza Insuf si no alcanza para entrenar."""
    if len(df) == 0:
        raise Insuf("precios_historial esta vacia.")
    # ---------- 2. series diarias y variables ----------
    def dias_desde(ev, idx):
        return (idx.to_series() - idx.to_series().where(ev == 1).ffill()).dt.days

    def serie(g, fin):
        g = g.sort_values("fecha").drop_duplicates("fecha", keep="last").set_index("fecha")
        idx = pd.date_range(g.index.min(), fin)
        p = g["precioCOP"].reindex(idx).ffill()
        chg = (p - p.shift(1)) / p.shift(1).replace(0, np.nan)
        drop = (chg < -MIN_CAMBIO).astype(int)       # bajada de precio = descuento
        rise = (chg > MIN_CAMBIO).astype(int)
        o = pd.DataFrame(index=idx)
        fut = drop[::-1].rolling(HORIZON, min_periods=HORIZON).sum()[::-1].shift(-1)
        o["y"] = (fut > 0).astype(float).where(fut.notna())          # ¿bajada en t+1..t+30?
        o["precio"] = p
        o["log_precio"] = np.log1p(p)
        o["es_gratis"] = (p == 0).astype(int)
        o["ratio_max_hist"] = (p / p.cummax().replace(0, np.nan)).fillna(1)
        o["ratio_max_365"] = (p / p.rolling(365, min_periods=1).max().replace(0, np.nan)).fillna(1)
        dd, dr = dias_desde(drop, idx), dias_desde(rise, idx)
        o["sin_bajada_previa"] = dd.isna().astype(int)
        o["dias_desde_bajada"] = dd.fillna(999)
        o["dias_desde_subida"] = dr.fillna(999)
        o["n_bajadas_90"] = drop.rolling(90, min_periods=1).sum()
        o["n_bajadas_365"] = drop.rolling(365, min_periods=1).sum()
        o["n_bajadas_hist"] = drop.cumsum()
        o["n_subidas_90"] = rise.rolling(90, min_periods=1).sum()
        o["pct_ultima_bajada"] = (-chg).where(drop == 1).ffill().fillna(0)
        o["dias_obs"] = np.arange(len(idx))
        o["bajadas_por_mes"] = o["n_bajadas_hist"] / np.maximum(o["dias_obs"], 30) * 30
        o["drop14"] = drop.rolling(14, min_periods=1).sum()
        doy = np.asarray(idx.dayofyear)
        o["dias_a_temporada"] = np.min([(a - doy) % 365 for a in TEMPORADAS_DOY], axis=0)
        o["dias_desde_temporada"] = np.min([(doy - a) % 365 for a in TEMPORADAS_DOY], axis=0)
        o["mes"] = idx.month
        o["date"] = idx
        return o

    fin = max(df.fecha.max(), pd.Timestamp.today().normalize())
    frames = []
    for (jid, plat), g in df.groupby(["jid", "plataforma"]):
        s = serie(g, fin); s["jid"], s["plataforma"], s["nombre"] = jid, plat, g["nombre"].iloc[0]
        frames.append(s)
    D = pd.concat(frames, ignore_index=True)
    tot = D.groupby(["jid", "date"])["drop14"].transform("sum")
    D["otras_tiendas_bajaron_14d"] = (tot - D["drop14"]).clip(lower=0)      # ¿bajaron las otras tiendas?
    for t in sorted(D.plataforma.unique()):
        D["tienda_" + t.lower().replace(" ", "_")] = (D.plataforma == t).astype(int)
    feats = [c for c in D.columns if c not in ("y", "jid", "nombre", "plataforma", "date", "dias_obs", "drop14")]

    # ---------- 3. entrenamiento y evaluación ----------
    data = D.dropna(subset=["y"])
    data = data[(data.dias_obs >= MIN_HIST) & (((data.date - D.date.min()).dt.days % STEP) == 0)]
    t0, t1 = data.date.min(), data.date.max() if len(data) else (None, None)
    if len(data) == 0 or (t1 - t0).days < 2 * HORIZON:
        raise Insuf(f"Historial insuficiente: se necesitan varios meses de datos (mínimo ~{MIN_HIST + 3*HORIZON} días).")
    corte = t0 + (t1 - t0) * 0.7
    tr = data[data.date <= corte - pd.Timedelta(days=HORIZON)]
    te = data[data.date >= corte]
    if tr.y.nunique() < 2 or te.y.nunique() < 2:
        raise Insuf("No hay suficientes bajadas de precio en el historial para entrenar.")
    Xtr, ytr, Xte, yte = tr[feats], tr.y.astype(int), te[feats], te.y.astype(int)

    lr = make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000, class_weight="balanced", random_state=SEED)).fit(Xtr, ytr)
    rf = RandomForestClassifier(n_estimators=300, min_samples_leaf=5, class_weight="balanced", n_jobs=-1, random_state=SEED).fit(Xtr, ytr)

    def fila(nombre, pred, proba=None):
        return {"modelo": nombre, "precision": precision_score(yte, pred, zero_division=0),
                "recall": recall_score(yte, pred, zero_division=0), "f1": f1_score(yte, pred, zero_division=0),
                "roc_auc": roc_auc_score(yte, proba) if proba is not None else np.nan}
    tabla = pd.DataFrame([
        fila("Siempre 'sí' (referencia)", np.ones(len(yte), dtype=int)),
        fila("Bajada en últimos 90 días (referencia)", (te.n_bajadas_90 > 0).astype(int)),
        fila("Regresión logística", lr.predict(Xte), lr.predict_proba(Xte)[:, 1]),
        fila("Random Forest", rf.predict(Xte), rf.predict_proba(Xte)[:, 1]),
    ]).round(3)
    print(f"\nEntrenamiento: {len(tr)} filas | Prueba: {len(te)} filas (desde {corte.date()})")
    print("\n=== MÉTRICAS (en el periodo de prueba) ===")
    print(tabla.to_string(index=False))
    print("\n=== VARIABLES MÁS IMPORTANTES ===")
    print(pd.Series(rf.feature_importances_, feats).sort_values(ascending=False).head(8).round(3).to_string())

    auc = float(roc_auc_score(yte, rf.predict_proba(Xte)[:, 1]))
    final = RandomForestClassifier(n_estimators=300, min_samples_leaf=5, class_weight="balanced", n_jobs=-1, random_state=SEED).fit(data[feats], data.y.astype(int))
    hoy = D[(D.date == fin) & (D.dias_obs >= MIN_HIST)].copy()
    hoy["prob"] = final.predict_proba(hoy[feats])[:, 1]
    ahora = datetime.now(timezone.utc)
    docs = [{"juegoId": r.jid, "nombre": r.nombre, "plataforma": r.plataforma,
             "probabilidad": round(float(r.prob), 4), "probableDescuento": bool(r.prob >= UMBRAL),
             "horizonteDias": HORIZON, "aucModelo": round(auc, 3), "metodo": "modelo", "calculadoEn": ahora}
            for r in hoy.itertuples()]
    return docs, auc


def heuristico(df):
    """
    Estimacion por reglas, para cuando hay pocos datos. NO aprende: suma puntos por senales conocidas.
      base 0.15 | cercania a una gran rebaja (+0.25 si faltan <=14 dias, +0.15 <=30, +0.05 <=60)
      otra tienda del mismo juego bajo en los ultimos 14 dias (+0.20)
      bajadas previas de esa tienda en el historial (+0.10 si >=1, +0.20 si >=3)
    """
    ahora = datetime.now(timezone.utc)
    doy = ahora.timetuple().tm_yday
    d_temp = min((a - doy) % 365 for a in TEMPORADAS_DOY)
    b_temp = 0.25 if d_temp <= 14 else 0.15 if d_temp <= 30 else 0.05 if d_temp <= 60 else 0.0
    bajas = {}
    for (jid, plat), g in df.groupby(["jid", "plataforma"]):
        p = g.sort_values("fecha").precioCOP.values
        bajas[(jid, plat)] = int((p[1:] < p[:-1] * (1 - MIN_CAMBIO)).sum())
    docs = []
    for j in db["juegos"].find({}, {"nombre": 1, "precios": 1}):
        jid, precios = str(j["_id"]), j.get("precios") or []
        recientes = set()
        for p in precios:
            fc = pd.to_datetime(p.get("fechaCambio"), utc=True, errors="coerce")
            if (p.get("descuento") or 0) > 0 and pd.notna(fc) and (ahora - fc.to_pydatetime()).days <= 14:
                recientes.add(p.get("plataforma"))
        for p in precios:
            plat = str(p.get("plataforma") or "").strip()
            if not plat:
                continue
            sc = 0.15 + b_temp + (0.20 if recientes - {p.get("plataforma")} else 0.0)
            nb = bajas.get((jid, plat), 0)
            sc += 0.20 if nb >= 3 else 0.10 if nb >= 1 else 0.0
            sc = min(sc, 1.0)
            docs.append({"juegoId": jid, "nombre": j.get("nombre"), "plataforma": plat,
                         "probabilidad": round(sc, 4), "probableDescuento": bool(sc >= UMBRAL_H),
                         "horizonteDias": HORIZON, "aucModelo": None, "metodo": "heuristico", "calculadoEn": ahora})
    return docs


# ---------- 5. elegir metodo, publicar ----------
docs, auc, metodo = None, None, None
if args.metodo != "heuristico":
    try:
        docs, auc = entrenar_modelo(df)
        print(f"\nAUC del modelo: {auc:.3f} (minimo para publicar: {MIN_AUC})")
        if auc < MIN_AUC and not args.forzar:
            raise Insuf(f"el modelo es poco fiable con este historial (AUC {auc:.2f} < {MIN_AUC}).")
        metodo = "modelo"
    except Insuf as e:
        print(f"\nNo se puede usar el modelo: {e}")
        if args.metodo == "modelo":
            sys.exit("Con --metodo modelo no hay alternativa. Quita esa opcion para usar la estimacion por reglas.")
        docs = None
if docs is None:
    print("Usando ESTIMACION POR REGLAS (modo pocos datos): es una aproximacion, no un modelo entrenado.")
    docs, metodo = heuristico(df), "heuristico"
print(f"Metodo: {metodo} | {sum(d['probableDescuento'] for d in docs)} de {len(docs)} con descuento probable")
if args.simular:
    print("--simular: no se escribio nada en MongoDB.")
else:
    db["predicciones_descuento"].delete_many({})
    if docs:
        db["predicciones_descuento"].insert_many([dict(d) for d in docs])
        db["predicciones_descuento_log"].insert_many([dict(d) for d in docs])
    print(f"{len(docs)} predicciones guardadas en predicciones_descuento.")