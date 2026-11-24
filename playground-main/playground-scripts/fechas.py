"""
Utilidades de contenido compartidas por los scrapers: fecha de lanzamiento y descripciones cortas.

No importa nada del proyecto (así steam.py, gog.py, epic.py y juegos_comun.py pueden usarlo sin
dependencias circulares).

    parsear_fecha("9 jun 2020")          -> date(2020, 6, 9)       (Steam, en español)
    parsear_fecha("Jun 9, 2020")         -> date(2020, 6, 9)       (Steam, en inglés)
    parsear_fecha("2015.05.19")          -> date(2015, 5, 19)      (GOG)
    parsear_fecha("2020-09-17T00:00:00.000Z") -> date(2020, 9, 17) (Epic)
    parsear_fecha("Próximamente")        -> None                   (nunca inventa una fecha)

    resumir(texto_largo)                 -> máx. DESCRIPCION_MAX caracteres, cortado en una frase o palabra

    fecha_a_mongo(date(2020, 6, 9))      -> datetime 2020-06-09 12:00 UTC
"""

from __future__ import annotations

import os
import re
import unicodedata
from datetime import date, datetime, timezone

DESCRIPCION_MAX = int(os.getenv("DESCRIPCION_MAX", "300"))
ANIO_MIN = 1980            # fechas anteriores o con año 2099 (marcador de "sin fecha") se descartan

_MESES = {"ene": 1, "jan": 1, "feb": 2, "mar": 3, "abr": 4, "apr": 4, "may": 5, "jun": 6, "jul": 7,
          "ago": 8, "aug": 8, "sep": 9, "set": 9, "oct": 10, "nov": 11, "dic": 12, "dec": 12}


def _sin_acentos(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.combining(c))


def _valida(anio: int, mes: int, dia: int) -> date | None:
    try:
        d = date(anio, mes, dia)
    except ValueError:
        return None
    return d if ANIO_MIN <= d.year <= date.today().year + 3 else None


def parsear_fecha(valor) -> date | None:
    """Convierte lo que entregue una tienda en un `date`, o None si no se entiende (nunca adivina)."""
    if valor is None:
        return None
    if isinstance(valor, datetime):
        return _valida(valor.year, valor.month, valor.day)
    if isinstance(valor, date):
        return _valida(valor.year, valor.month, valor.day)
    texto = str(valor).strip()
    if not texto:
        return None

    m = re.match(r"^(\d{4})[-./](\d{1,2})[-./](\d{1,2})", texto)          # 2020-09-17 · 2015.05.19
    if m:
        return _valida(int(m[1]), int(m[2]), int(m[3]))

    t = _sin_acentos(texto).lower()
    t = re.sub(r"\bde\b", " ", t)
    t = re.sub(r"[,.]", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    m = re.match(r"^(\d{1,2}) ([a-z]{3,})\w* (\d{4})$", t)               # 9 jun 2020 · 9 junio 2020
    if m and m[2][:3] in _MESES:
        return _valida(int(m[3]), _MESES[m[2][:3]], int(m[1]))
    m = re.match(r"^([a-z]{3,})\w* (\d{1,2}) (\d{4})$", t)               # jun 9 2020 · june 9, 2020
    if m and m[1][:3] in _MESES:
        return _valida(int(m[3]), _MESES[m[1][:3]], int(m[2]))
    return None


def fecha_a_mongo(valor) -> datetime | None:
    """
    `date` -> datetime UTC a las 12:00. Al mediodía la fecha cae en el mismo día en las zonas UTC-11 a
    UTC+11 (toda América, Europa, África y casi toda Asia; Colombia es UTC-5). A medianoche UTC, Java
    en Colombia la leería como el día ANTERIOR. (No cubre UTC+12/+13, p. ej. Nueva Zelanda.)
    """
    d = parsear_fecha(valor)
    return datetime(d.year, d.month, d.day, 12, 0, tzinfo=timezone.utc) if d else None


def resumir(texto, maximo: int | None = None) -> str | None:
    """
    Acorta una descripción a `maximo` caracteres (DESCRIPCION_MAX, 300 por defecto):
    corta al final de una frase si hay una razonablemente cerca; si no, en una palabra, y añade "…".
    """
    if texto is None:
        return None
    s = re.sub(r"\s+", " ", str(texto)).strip()
    if not s:
        return None
    maximo = maximo or DESCRIPCION_MAX
    if len(s) <= maximo:
        return s
    corte = s[:maximo]
    fin = max(corte.rfind(". "), corte.rfind("! "), corte.rfind("? "))
    if fin >= maximo * 0.5:                       # la frase completa más larga que quepa
        return corte[:fin + 1]
    if corte.endswith((".", "!", "?")):
        return corte
    palabra = corte.rfind(" ")
    if palabra > maximo * 0.5:
        corte = corte[:palabra]
    return corte.rstrip(" ,;:-–—") + "…"
