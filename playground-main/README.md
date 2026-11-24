# Playground

**Playground** es una aplicación web para consultar y comparar precios de videojuegos en Steam, Epic Games y GOG. Permite gestionar un catálogo, consultar precios, crear listas de deseos, revisar el historial y mostrar estimaciones de posibles descuentos.

El proyecto está construido con Spring Boot, Java, Thymeleaf, Spring Security y MongoDB. Los procesos de consulta y actualización de tiendas se ejecutan mediante scripts Python.

## Contenido del repositorio

```text
Playground/
├── pom.xml                         # Proyecto Maven / Spring Boot
├── src/
│   ├── main/
│   │   ├── java/                   # Backend Java
│   │   └── resources/              # Properties, vistas, CSS, JS e imágenes
│   └── test/                       # Pruebas automatizadas
├── playground-scripts/             # Scrapers y procesos auxiliares Python
│   ├── buscar_json.py              # Búsqueda puntual desde el panel admin
│   ├── actualizador.py             # Actualización de precios y descubrimiento
│   ├── juegos_comun.py              # Lógica compartida de catálogo y MongoDB
│   ├── predictor.py                 # Predicción de descuentos
│   ├── requirements.txt
│   └── .env.example
├── .env.example                     # Variables de entorno de Spring Boot
├── .gitignore
└── README.md
```

Hay un solo proyecto Maven en la raíz. `target/`, entornos virtuales, cachés, archivos temporales y metadatos de Git no forman parte del código fuente.

## Requisitos

- Java 17 o superior.
- Maven 3.8+ (o usar el wrapper si se incorpora posteriormente).
- Python 3.10+.
- MongoDB local o una instancia de MongoDB Atlas.
- Credenciales de Google OAuth2 si se habilita el inicio de sesión con Google.

## Configuración local

### 1. Clonar y configurar variables

Copia `.env.example` a `.env` para tus variables locales. **Spring Boot no carga automáticamente archivos `.env`**: en Linux/macOS puedes exportarlas con `set -a; source .env; set +a`, o configurarlas en tu IDE o en el panel de variables del proveedor de despliegue.

Genera un secreto JWT nuevo:

```bash
openssl rand -base64 48
```

Asigna el resultado a `JWT_SECRET`. No uses el valor de ejemplo ni reutilices claves que hayan sido compartidas anteriormente.

Ejemplo en Linux/macOS:

```bash
cp .env.example .env
# Edita .env y reemplaza JWT_SECRET por un valor generado.
set -a; source .env; set +a
export MONGODB_URI='mongodb://localhost:27017/playground'
export MONGODB_DATABASE='playground'
export JWT_SECRET="$(openssl rand -base64 48)"
export PORT=3066
export SCRAPER_DIR='./playground-scripts'
export UPLOAD_DIR='./data/uploads'
```

En Windows PowerShell:

```powershell
$env:MONGODB_URI = 'mongodb://localhost:27017/playground'
$env:MONGODB_DATABASE = 'playground'
$env:JWT_SECRET = 'PEGA_AQUI_UN_SECRETO_ALEATORIO_DE_32_BYTES_O_MAS'
$env:PORT = '3066'
$env:SCRAPER_DIR = '.\playground-scripts'
$env:UPLOAD_DIR = '.\data\uploads'
```

Para Google OAuth2 configura `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` y `GOOGLE_REDIRECT_URI`. Si no configuras credenciales reales, el cliente queda con valores ficticios y el acceso mediante Google no funcionará. En desarrollo, la URI habitual es:

```text
http://localhost:3066/login/oauth2/code/google
```

Registra exactamente esa URI en las credenciales OAuth de Google Cloud. En producción usa el dominio HTTPS de la aplicación.

### 2. Instalar dependencias Python

Desde la raíz del proyecto:

```bash
python -m venv playground-scripts/.venv
```

Activar el entorno:

```bash
# Linux/macOS
source playground-scripts/.venv/bin/activate

# Windows PowerShell
.\playground-scripts\.venv\Scripts\Activate.ps1
```

Instalar dependencias:

```bash
python -m pip install -r playground-scripts/requirements.txt
```

Los scripts Python también admiten un archivo `playground-scripts/.env` para ejecución manual. Crea ese archivo copiando `playground-scripts/.env.example` y configura `MONGO_URI`, `MONGO_DB` y `MONGO_COLLECTION`. El archivo real está excluido de Git. Cuando los scripts se ejecutan desde Spring Boot, también pueden recibir las variables exportadas en el entorno del proceso Java.

### 3. Ejecutar la aplicación

Desde la raíz:

```bash
mvn spring-boot:run
```

La aplicación estará disponible en `http://localhost:3066` (o en el puerto definido mediante `PORT`).

Para generar el JAR:

```bash
mvn clean package
```

Para ejecutarlo:

```bash
java -jar target/playground-0.0.1-SNAPSHOT.jar
```

## Variables de entorno

| Variable | Uso | Valor local sugerido |
|---|---|---|
| `MONGODB_URI` | URI de conexión a MongoDB | `mongodb://localhost:27017/playground` |
| `MONGODB_DATABASE` | Base de datos | `playground` |
| `JWT_SECRET` | Clave de firma JWT. Obligatoria y privada | Generada con `openssl rand -base64 48` |
| `JWT_EXPIRATION_MS` | Duración del JWT en milisegundos | `86400000` |
| `GOOGLE_CLIENT_ID` | ID OAuth2 de Google | Credencial propia |
| `GOOGLE_CLIENT_SECRET` | Secreto OAuth2 de Google | Credencial propia |
| `GOOGLE_REDIRECT_URI` | Callback OAuth2 | `http://localhost:3066/login/oauth2/code/google` |
| `PORT` | Puerto HTTP | `3066` |
| `UPLOAD_DIR` | Directorio persistente para imágenes subidas | `./data/uploads` |
| `SCRAPER_DIR` | Carpeta que contiene los scripts Python | `./playground-scripts` |
| `PYTHON_EXECUTABLE` | Ruta al intérprete Python; vacío para autodetección | Vacío |
| `SCRAPER_TIMEOUT_SECONDS` | Tiempo máximo para una búsqueda | `120` |
| `STEAMGRIDDB_API_KEY` | Token opcional de SteamGridDB | Vacío |
| `STEAMGRIDDB_TIMEOUT_MS` | Timeout de SteamGridDB | `2500` |
| `ACTUALIZADOR_AUTO` | Activa el actualizador periódico | `false` |
| `ACTUALIZADOR_INTERVALO` | Frecuencia ISO-8601 del actualizador | `PT6H` |
| `ACTUALIZADOR_ESPERA_INICIAL` | Espera inicial del actualizador | `PT2M` |

Para los scripts Python se usan además `MONGO_URI`, `MONGO_DB`, `MONGO_COLLECTION` y `HISTORIAL_COLECCION`. `MONGO_URI` debe apuntar a la misma instancia que `MONGODB_URI`.

## Scrapers y actualización de precios

Los scripts principales se encuentran en `playground-scripts/`:

- `buscar_json.py`: consulta un juego en las tiendas y devuelve JSON para el panel de administración.
- `actualizador.py`: actualiza precios existentes, registra cambios en el historial y descubre juegos nuevos.
- `juegos_comun.py`: lógica compartida para normalización, coincidencias y persistencia.
- `steam.py`, `epic.py` y `gog.py`: obtención de información de cada tienda.
- `tasas_cambio.py`: tasas de cambio para normalizar precios a COP.
- `predictor.py`: genera predicciones a partir del historial de precios.

El actualizador periódico está **desactivado por defecto**. Para habilitarlo, configura `ACTUALIZADOR_AUTO=true` y define el intervalo. También puede iniciarse manualmente desde el panel de administración.

Ejecutar una pasada manual:

```bash
cd playground-scripts
python actualizador.py
```

Ejecutar el predictor:

```bash
python predictor.py
```

El predictor necesita datos históricos suficientes para entrenar un modelo. Si no los hay, utiliza el modo heurístico cuando corresponde; consulta `python predictor.py --help` para las opciones disponibles.

## Despliegue

1. Configura todas las variables de entorno en el proveedor (Railway, Render, VPS, etc.). No subas `.env`.
2. Configura `MONGODB_URI`, `MONGODB_DATABASE` y un `JWT_SECRET` nuevo.
3. Si usas Google OAuth2, configura las credenciales y registra la URL pública `https://TU-DOMINIO/login/oauth2/code/google` en Google Cloud.
4. Configura `PORT` según el puerto asignado por el proveedor.
5. Configura `UPLOAD_DIR` en un volumen persistente. El sistema de archivos efímero de muchos proveedores puede borrar las imágenes al reiniciar o volver a desplegar.
6. Instala Python y las dependencias de `playground-scripts/requirements.txt` en el entorno de ejecución si vas a utilizar búsquedas o actualizaciones desde la aplicación.
7. Habilita `ACTUALIZADOR_AUTO` solo si el servicio permanece encendido y tiene acceso a MongoDB y Python.

**Importante:** la aplicación ejecuta procesos Python desde el servidor. El despliegue debe incluir Python, los scripts y sus dependencias, y permitir ejecutar procesos hijos. Si el proveedor no ofrece estas condiciones, los módulos web que dependen de los scrapers no funcionarán allí aunque la aplicación Java arranque.

## Seguridad

- No publiques `.env`, `playground-scripts/.env`, claves API, secretos OAuth2, URI de MongoDB ni claves JWT.
- Las credenciales que hayan sido expuestas deben revocarse o rotarse en el servicio correspondiente. Borrarlas del código no invalida una credencial filtrada.
- Mantén `data/` fuera del repositorio; usa almacenamiento persistente y con permisos restringidos en producción.
- No uses el secreto JWT de desarrollo en producción.

## Pruebas

Ejecuta las pruebas Java con:

```bash
mvn test
```

Las pruebas Python disponibles pueden ejecutarse desde la carpeta de scripts:

```bash
python -m unittest discover -s . -p 'test_*.py'
```

Algunas pruebas o procesos requieren conectividad con servicios externos o una instancia MongoDB configurada.
