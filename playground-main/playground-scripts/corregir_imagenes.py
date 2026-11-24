"""
corregir_imagenes.py - Vuelve a buscar la portada HORIZONTAL (16:9) de los juegos ya guardados.

    python corregir_imagenes.py --simular        # muestra que cambiaria, sin escribir
    python corregir_imagenes.py                  # corrige todos
    python corregir_imagenes.py --lote 50        # solo 50 juegos por corrida (los que aun no se revisaron)
    python corregir_imagenes.py -j "hades"       # un juego concreto

Orden de busqueda: Steam -> Epic Games -> GOG (la primera tienda que encuentre el juego).
Las imagenes subidas a mano desde el admin (/uploads/...) NO se tocan.
"""
import argparse, re, sys, time
import juegos_comun as jc

ORDEN = ("Steam", "Epic Games", "GOG")


def nueva_imagen(nombre):
    for tienda in ORDEN:
        try:
            r = jc.BUSCADORES[tienda](nombre)
        except Exception as e:                      # una tienda caida no detiene el resto
            print(f"    {tienda}: error ({e})", file=sys.stderr)
            continue
        if r and r[0] and r[0].get("imagenUrl"):
            return r[0]["imagenUrl"], tienda
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--simular", action="store_true")
    ap.add_argument("--lote", type=int, default=0, help="maximo de juegos a procesar (0 = todos)")
    ap.add_argument("--pausa", type=float, default=0.6)
    ap.add_argument("-j", "--juego", help="solo este juego (busca por nombre, sin importar mayusculas)")
    a = ap.parse_args()

    col = jc.obtener_coleccion()
    filtro = {"imagenRevisada": {"$ne": True}}
    if a.juego:
        filtro = {"nombre": {"$regex": re.escape(a.juego), "$options": "i"}}
    docs = list(col.find(filtro, {"nombre": 1, "imagenUrl": 1}))
    if a.lote:
        docs = docs[:a.lote]
    cambiados = sin_cambio = omitidos = fallidos = 0
    for d in docs:
        actual = d.get("imagenUrl") or ""
        if actual.startswith("/uploads/"):
            omitidos += 1
            col.update_one({"_id": d["_id"]}, {"$set": {"imagenRevisada": True}}) if not a.simular else None
            continue
        url, tienda = nueva_imagen(d["nombre"])
        if not url:
            fallidos += 1
            print(f"  ?  {d['nombre']}: no se encontro imagen")
        elif url == actual:
            sin_cambio += 1
            if not a.simular:
                col.update_one({"_id": d["_id"]}, {"$set": {"imagenRevisada": True}})
        else:
            cambiados += 1
            print(f"  ok {d['nombre']}: {tienda}\n       {actual}\n    -> {url}")
            if not a.simular:
                set_ = {"imagenUrl": url, "imagenRevisada": True}
                set_.update(jc.adjuntar_imagen_catalogo({"imagenUrl": url}, d))
                col.update_one({"_id": d["_id"]}, {"$set": set_})
        time.sleep(a.pausa)
    print(f"\nRevisados {len(docs)}: {cambiados} cambiados, {sin_cambio} iguales, "
          f"{omitidos} subidos a mano (omitidos), {fallidos} sin imagen."
          + ("  [--simular: no se escribio nada]" if a.simular else ""))


if __name__ == "__main__":
    main()
