"""
catalogo_conocidos.py - Llena la base de datos con juegos CONOCIDOS (sagas y títulos populares).

Usa exactamente el mismo camino que `python juegos_comun.py -j "nombre"`: busca cada juego en Steam, GOG y
Epic, baja descripción, desarrolladora, género, fecha, portada y precio de cada tienda, y lo guarda en MongoDB.
Los juegos que ya están en la base se saltan (no se vuelven a descargar), así que puedes correrlo varias
veces o retomarlo si se corta internet.

    python catalogo_conocidos.py                    # carga todos los de la lista
    python catalogo_conocidos.py --limite 30        # solo 30 nuevos (para probar)
    python catalogo_conocidos.py --genero terror    # solo una categoría de la lista
    python catalogo_conocidos.py --simular          # muestra qué encontraría, sin escribir
    python catalogo_conocidos.py --listar           # imprime la lista y sale
    python catalogo_conocidos.py --archivo mas_juegos.txt   # carga los nombres de un .txt (uno por línea)
    python catalogo_conocidos.py --actualizar       # NO salta los existentes (refresca sus precios)

Después de cargar, genera el historial de 12 meses para la gráfica de la ficha:
    python generar_historial.py
"""
from __future__ import annotations

import argparse
import sys
import time

import juegos_comun as jc

# Nombres tal como se buscan en las tiendas. Se agrupan solo para poder filtrar con --genero.
CATALOGO: dict[str, list[str]] = {
    "accion": [
        "Grand Theft Auto V", "Red Dead Redemption 2", "Cyberpunk 2077", "God of War", "Devil May Cry 5",
        "Marvel's Spider-Man Remastered", "Batman: Arkham Knight", "Assassin's Creed Valhalla",
        "Assassin's Creed Odyssey", "Sekiro: Shadows Die Twice", "Hades", "Dead Cells", "Hollow Knight",
        "Mortal Kombat 11", "Street Fighter 6", "Tekken 8", "Sleeping Dogs", "Mad Max", "Control",
        "Death Stranding", "Star Wars Jedi: Survivor", "Hi-Fi Rush", "Metal Gear Solid V: The Phantom Pain",
        "Prince of Persia: The Lost Crown", "Ghostrunner",
    ],
    "rpg": [
        "The Witcher 3: Wild Hunt", "Elden Ring", "Baldur's Gate 3", "The Elder Scrolls V: Skyrim Special Edition", "Fallout 4", "Fallout: New Vegas",
        "Dark Souls III", "Dark Souls: Remastered", "Mass Effect Legendary Edition", "Dragon Age: Inquisition",
        "Divinity: Original Sin 2", "Persona 5 Royal", "Final Fantasy VII Remake", "Final Fantasy XV",
        "Monster Hunter: World", "Diablo IV", "Path of Exile", "Disco Elysium", "Starfield", "Cyberpunk 2077",
        "Kingdom Come: Deliverance", "Pillars of Eternity", "Torchlight II", "Nier: Automata", "Octopath Traveler",
        "Yakuza: Like a Dragon", "Dragon's Dogma: Dark Arisen", "Horizon Zero Dawn", "The Elder Scrolls IV: Oblivion",
    ],
    "shooter": [
        "DOOM Eternal", "DOOM", "Half-Life 2", "Half-Life: Alyx", "Counter-Strike 2", "Call of Duty: Modern Warfare",
        "Battlefield 2042", "Battlefield 1", "Borderlands 3", "Borderlands 2", "Left 4 Dead 2", "Far Cry 6",
        "Far Cry 5", "Titanfall 2", "Wolfenstein II: The New Colossus", "Apex Legends", "PUBG: BATTLEGROUNDS",
        "Overwatch 2", "Rainbow Six Siege", "Halo: The Master Chief Collection", "Resident Evil 4",
        "Metro Exodus", "Serious Sam 4", "Team Fortress 2", "BioShock Infinite", "Payday 3",
    ],
    "aventura": [
        "The Last of Us Part I", "Life is Strange", "Tomb Raider", "Rise of the Tomb Raider", "Uncharted: Legacy of Thieves Collection",
        "Firewatch", "What Remains of Edith Finch", "Outer Wilds", "Detroit: Become Human", "Heavy Rain",
        "Telltale's The Walking Dead", "The Wolf Among Us", "A Plague Tale: Innocence", "Alan Wake 2",
        "Oxenfree", "Return of the Obra Dinn", "Subnautica", "Journey", "Stray", "Kena: Bridge of Spirits",
        "Ori and the Will of the Wisps", "Spyro Reignited Trilogy", "Crash Bandicoot N. Sane Trilogy",
    ],
    "mundo_abierto": [
        "Minecraft", "Terraria", "Stardew Valley", "Just Cause 4", "Watch Dogs 2", "Ghost of Tsushima",
        "Horizon Forbidden West", "Forza Horizon 5", "Hogwarts Legacy", "No Man's Sky",
        "Sea of Thieves", "Rust", "ARK: Survival Evolved", "Valheim", "Grounded", "Satisfactory", "The Forest",
        "Far Cry 3", "Saints Row IV", "Days Gone", "Mount & Blade II: Bannerlord",
    ],
    "estrategia": [
        "Civilization VI", "Age of Empires II: Definitive Edition", "Age of Empires IV", "Total War: WARHAMMER III",
        "XCOM 2", "Stellaris", "Crusader Kings III", "Europa Universalis IV", "Cities: Skylines", "Frostpunk",
        "Anno 1800", "StarCraft II", "Warcraft III: Reforged", "Company of Heroes 3", "Northgard",
        "Into the Breach", "Slay the Spire", "Hearts of Iron IV", "They Are Billions", "Humankind",
        "Planet Zoo", "Two Point Hospital", "Factorio", "RimWorld", "Oxygen Not Included",
    ],
    "deportes": [
        "EA SPORTS FC 24", "eFootball", "NBA 2K24", "Madden NFL 24", "F1 24", "Rocket League", "Football Manager 2024",
        "Tony Hawk's Pro Skater 1 + 2", "Descenders", "Riders Republic", "WWE 2K24", 
        "Golf With Your Friends", 
    ],
    "carreras": [
        "Forza Horizon 4", "Need for Speed Heat", "Need for Speed Unbound", "Assetto Corsa",
        "Assetto Corsa Competizione", "DiRT Rally 2.0", "Project CARS 3", "The Crew 2", "Euro Truck Simulator 2",
        "American Truck Simulator", "Wreckfest", "Burnout Paradise Remastered", "Trackmania", 
        "Hot Wheels Unleashed", "F1 22", "Art of Rally", "BeamNG.drive",
    ],
    "terror": [
        "Resident Evil 2", "Resident Evil Village", "Resident Evil 7: Biohazard", "Silent Hill 2", "Dead Space",
        "Outlast", "Amnesia: The Bunker", "Phasmophobia", "Alien: Isolation", "Five Nights at Freddy's",
        "The Evil Within", "Layers of Fear", "Little Nightmares II", "Dying Light 2 Stay Human", "Dying Light",
        "The Callisto Protocol", "Visage", "Until Dawn", "Darkwood", "Dredge", "Lethal Company",
        "The Texas Chain Saw Massacre", "Dead by Daylight",
    ],
    "indie": [
        "Celeste", "Cuphead", "Undertale", "Hades II", "Inside", "Limbo", "Shovel Knight", "The Binding of Isaac: Rebirth",
        "Among Us", "Fall Guys", "Vampire Survivors", "Risk of Rain 2", "Gris", "Katana ZERO", "Hotline Miami",
        "Papers, Please", "Untitled Goose Game", "Cult of the Lamb", "Balatro", "Dave the Diver", "Spiritfarer",
        "Super Meat Boy", "Enter the Gungeon", "Slay the Spire", "Return to Monkey Island",
    ],
    "multijugador": [
        "Dota 2", "Fortnite", "Destiny 2", "Warframe", "Rainbow Six Extraction",
        "It Takes Two", "Overcooked! 2", "Human: Fall Flat", "Phasmophobia", "Helldivers 2", "Back 4 Blood",
        "Deep Rock Galactic", "Monster Hunter Rise", "Lost Ark", "Black Desert", "New World", "Albion Online",
        "Gang Beasts", "Pummel Party",
    ],
    "simulacion": [
        "The Sims 4", "Microsoft Flight Simulator", "Cities: Skylines II", "Farming Simulator 22", "PowerWash Simulator",
        "House Flipper", "Planet Coaster", "Prison Architect", "Kerbal Space Program", "Surviving Mars",
        "Car Mechanic Simulator 2021", "Goat Simulator 3", "Bus Simulator 21", "Cooking Simulator", "Hearts of Iron IV",
    ],
    "plataformas": [
        "Super Meat Boy", "Rayman Legends", "Sonic Mania", "Little Nightmares", "A Hat in Time", "Psychonauts 2",
        "Trine 4", "Ori and the Blind Forest", "Hollow Knight: Silksong", "Prince of Persia: The Sands of Time",
    ],
    "lucha": [
        "Mortal Kombat 1", "Injustice 2", "Dragon Ball FighterZ", "Guilty Gear -Strive-", "Soulcalibur VI",
        "Granblue Fantasy Versus", "Brawlhalla", "Skullgirls", "Killer Instinct", "Samurai Shodown",
    ],
    "puzles": [
        "Portal 2", "Portal", "The Witness", "The Talos Principle", "Baba Is You", "Tetris Effect: Connected",
        "Human: Fall Flat", "Fez", "Monument Valley", "Unpacking", "Opus Magnum", "Return of the Obra Dinn",
    ],
}


def lista_unica(generos: list[str] | None = None) -> list[tuple[str, str]]:
    """[(nombre, categoria)] sin repetidos (un mismo juego puede estar en varias categorías)."""
    vistos, salida = set(), []
    for cat, nombres in CATALOGO.items():
        if generos and cat not in generos:
            continue
        for n in nombres:
            if n.lower() not in vistos:
                vistos.add(n.lower())
                salida.append((n, cat))
    return salida


def leer_archivo(ruta: str) -> list[tuple[str, str]]:
    """Un nombre por línea. Se ignoran líneas vacías y las que empiezan con #. Opcional: «categoria|Nombre»."""
    salida, vistos = [], set()
    with open(ruta, encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()
            if not linea or linea.startswith("#"):
                continue
            cat, _, nombre = linea.partition("|") if "|" in linea else ("archivo", "", linea)
            nombre = nombre.strip()
            if nombre and nombre.lower() not in vistos:
                vistos.add(nombre.lower())
                salida.append((nombre, cat.strip() or "archivo"))
    return salida


def main() -> None:
    ap = argparse.ArgumentParser(description="Carga juegos conocidos (Steam + GOG + Epic) en MongoDB")
    ap.add_argument("--limite", type=int, default=None, help="Máximo de juegos NUEVOS a cargar en esta corrida")
    ap.add_argument("--genero", action="append", choices=sorted(CATALOGO), help="Solo estas categorías (repetible)")
    ap.add_argument("--tiendas", default="steam,gog,epic", help="Tiendas a consultar, separadas por coma")
    ap.add_argument("--pausa", type=float, default=1.5, help="Segundos entre juegos (para no saturar las tiendas)")
    ap.add_argument("--umbral", type=float, default=0.92, help="Similitud mínima de nombres (0-1)")
    ap.add_argument("--archivo", help="Archivo .txt con nombres de juegos (uno por línea) en vez de la lista interna")
    ap.add_argument("--simular", action="store_true", help="No escribe en MongoDB, solo muestra")
    ap.add_argument("--actualizar", action="store_true", help="No saltar los que ya existen")
    ap.add_argument("--listar", action="store_true", help="Imprime la lista y termina")
    args = ap.parse_args()

    if args.archivo:
        try:
            lista = leer_archivo(args.archivo)
        except OSError as e:
            ap.error(f"no se pudo leer {args.archivo}: {e}")
    else:
        lista = lista_unica(args.genero)
    if args.listar:
        for n, c in lista:
            print(f"{c:15} {n}")
        print(f"\n{len(lista)} juegos en la lista.")
        return

    try:
        tiendas = [jc.ALIAS_TIENDAS[t.strip().lower()] for t in args.tiendas.split(",") if t.strip()]
    except KeyError as e:
        ap.error(f"tienda desconocida {e}; usa steam, gog y/o epic")

    col = jc.obtener_coleccion()
    jc.migrar_claves(col)

    resumen = {"insertado": 0, "actualizado": 0, "simulado": 0, "ya_estaba": 0, "no_encontrado": 0, "sin_precio": 0, "error": 0}
    print(f"📚 {len(lista)} juegos en la lista · tiendas: {', '.join(tiendas)}")
    nuevos = 0
    for i, (nombre, cat) in enumerate(lista, 1):
        if args.limite is not None and nuevos >= args.limite:
            print(f"\nLímite de {args.limite} juegos nuevos alcanzado.")
            break
        if not args.actualizar:
            existente, _ = jc.buscar_existente(col, nombre, args.umbral)
            if existente:
                resumen["ya_estaba"] += 1
                print(f"[{i}/{len(lista)}] ⏭  {nombre}: ya está en la base")
                continue
        print(f"[{i}/{len(lista)}] ({cat})", end="")
        try:
            estado = jc.buscar_juego_especifico(col, nombre, tiendas, args.umbral, args.simular)
        except KeyboardInterrupt:
            print("\nInterrumpido: lo ya guardado se conserva; vuelve a correr el script para continuar.")
            break
        except Exception as e:                              # una tienda caída no debe frenar todo el lote
            print(f"⚠️  {nombre}: {type(e).__name__}: {e}")
            resumen["error"] += 1
            time.sleep(args.pausa * 2)
            continue
        resumen[estado] = resumen.get(estado, 0) + 1
        if estado in ("insertado", "simulado"):
            nuevos += 1
        time.sleep(args.pausa)

    print("\n──────── Resumen ────────")
    for k, v in resumen.items():
        if v:
            print(f"  {k:14} {v}")
    print(f"  total en la colección: {col.count_documents({})}")
    if not args.simular and resumen["insertado"]:
        print("\nSiguiente paso: python generar_historial.py   (historial de 12 meses para la gráfica)")


if __name__ == "__main__":
    main()
