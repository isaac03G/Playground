/**
 * Busca un juego por nombre en Steam, GOG y Epic Games ejecutando
 * {@code playground-scraper/buscar_json.py} (el mismo código de los scrapers).
 * <p>
 * Ventajas de este diseño: no hay que mantener un segundo servidor, ni reescribir los scrapers en
 * Java; la app solo necesita Python y las dependencias del scraper ({@code pip install requests
 * pymongo python-dotenv}). El comando se lanza con una lista de argumentos (sin shell), así que el
 * nombre escrito por el administrador nunca se interpreta como comando.
 * </p>
 * Propiedades (todas opcionales):
 * <ul>
 *   <li>{@code app.scraper.dir}: carpeta de los scrapers (por defecto {@code ../playground-scraper})</li>
 *   <li>{@code app.scraper.python}: ejecutable de Python (por defecto se usa el {@code .venv} de esa
 *       carpeta si existe; si no, {@code python})</li>
 *   <li>{@code app.scraper.timeout-seconds}: tiempo máximo por búsqueda (por defecto 120)</li>
 * </ul>
 */
package com.proyecto.aula.playgroundv2.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proyecto.aula.playgroundv2.dto.ResultadoBusqueda;
import com.proyecto.aula.playgroundv2.dto.TiendaResultado;

@Service
public class ScraperService {

    private static final Logger log = LoggerFactory.getLogger(ScraperService.class);
    private static final String SCRIPT = "buscar_json.py";
    /** Búsquedas simultáneas permitidas: cada una abre 3 conexiones a tiendas externas. */
    private static final int MAX_SIMULTANEAS = 2;

    @Value("${app.scraper.dir:../playground-scraper}")
    private String scraperDir;

    @Value("${app.scraper.python:}")
    private String pythonConfigurado;

    @Value("${app.scraper.timeout-seconds:120}")
    private long timeoutSegundos;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Semaphore cupos = new Semaphore(MAX_SIMULTANEAS);

    /**
     * Busca el juego en las tres tiendas.
     *
     * @param nombre  texto escrito por el administrador (puede ser parcial, p. ej. "witcher 3")
     * @param guardar si es {@code true}, además guarda el juego y sus precios en MongoDB
     * @throws IllegalArgumentException si el nombre no es válido
     * @throws IllegalStateException    si no se pudo ejecutar el buscador (mensaje apto para mostrar)
     */
    public ResultadoBusqueda buscar(String nombre, boolean guardar) {
        String consulta = nombre == null ? "" : nombre.replaceAll("\\p{Cntrl}", " ").trim().replaceAll("\\s+", " ");
        if (consulta.length() < 2) {
            throw new IllegalArgumentException("Escribe al menos 2 caracteres.");
        }
        if (consulta.length() > 100) {
            throw new IllegalArgumentException("El nombre es demasiado largo (máximo 100 caracteres).");
        }

        Path dir = Paths.get(scraperDir).toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve(SCRIPT))) {
            throw new IllegalStateException("No encuentro " + SCRIPT + " en " + dir
                    + ". Revisa app.scraper.dir en application.properties.");
        }
        if (!cupos.tryAcquire()) {
            throw new IllegalStateException("Ya hay búsquedas en curso; espera unos segundos e inténtalo de nuevo.");
        }
        try {
            return ejecutar(dir, consulta, guardar);
        } finally {
            cupos.release();
        }
    }

    private ResultadoBusqueda ejecutar(Path dir, String consulta, boolean guardar) {
        List<String> comando = new ArrayList<>();
        comando.add(python(dir));
        comando.add(SCRIPT);
        if (guardar) {
            comando.add("--guardar");
        }
        comando.add("--");          // lo que sigue es el nombre aunque empiece por "-"
        comando.add(consulta);

        final Process proceso = iniciar(comando, dir);
        CompletableFuture<byte[]> salida = CompletableFuture.supplyAsync(() -> {
            try {
                return proceso.getInputStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });

        try {
            if (!proceso.waitFor(timeoutSegundos, TimeUnit.SECONDS)) {
                proceso.destroyForcibly();
                throw new IllegalStateException("La búsqueda tardó más de " + timeoutSegundos
                        + " segundos y se canceló. Inténtalo de nuevo.");
            }
            String texto = new String(salida.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            return interpretar(consulta, texto, guardar, proceso.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proceso.destroyForcibly();
            throw new IllegalStateException("La búsqueda fue interrumpida.");
        } catch (ExecutionException | TimeoutException e) {
            proceso.destroyForcibly();
            throw new IllegalStateException("No pude leer la respuesta del buscador.");
        }
    }

    private Process iniciar(List<String> comando, Path dir) {
        ProcessBuilder pb = new ProcessBuilder(comando).directory(dir.toFile());
        pb.environment().put("PYTHONIOENCODING", "utf-8");   // evita errores con emojis/acentos en Windows
        pb.environment().put("PYTHONUTF8", "1");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);    // los avisos de los scrapers salen en la consola de la app
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("No pude ejecutar Python ('" + comando.get(0) + "'). Instálalo o indica la ruta en "
                    + "app.scraper.python. Detalle: " + e.getMessage());
        }
    }

    /** Python del entorno virtual del scraper si existe; si no, el configurado o simplemente "python". */
    private String python(Path dir) {
        if (pythonConfigurado != null && !pythonConfigurado.isBlank()) {
            return pythonConfigurado.trim();
        }
        for (String relativa : new String[] {".venv/Scripts/python.exe", ".venv/bin/python"}) {
            Path candidato = dir.resolve(relativa);
            if (Files.isRegularFile(candidato)) {
                return candidato.toString();
            }
        }
        return "python";
    }

    private ResultadoBusqueda interpretar(String consulta, String texto, boolean guardar, int codigoSalida) {
        // El JSON es la última línea no vacía (por si alguna librería imprimiera algo antes).
        String json = "";
        for (String linea : texto.split("\\R")) {
            if (!linea.isBlank()) {
                json = linea.trim();
            }
        }
        if (json.isEmpty()) {
            throw new IllegalStateException("El buscador no devolvió nada (código " + codigoSalida
                    + "). ¿Están instaladas las dependencias? pip install requests pymongo python-dotenv");
        }
        JsonNode raiz;
        try {
            raiz = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Respuesta no válida del buscador: {}", json);
            throw new IllegalStateException("El buscador devolvió una respuesta que no se entiende. Revisa la consola de la app.");
        }
        if (raiz.hasNonNull("error")) {
            throw new IllegalStateException("El buscador falló: " + raiz.get("error").asText());
        }

        List<TiendaResultado> tiendas = new ArrayList<>();
        for (JsonNode t : raiz.path("tiendas")) {
            tiendas.add(new TiendaResultado(
                    t.path("tienda").asText(),
                    t.path("estado").asText(),
                    texto(t, "nombre"),
                    t.hasNonNull("precio") ? t.get("precio").asDouble() : null,
                    texto(t, "moneda"),
                    texto(t, "detalle"),
                    t.hasNonNull("precioOriginal") ? t.get("precioOriginal").asDouble() : null,
                    texto(t, "monedaOriginal"),
                    t.path("descuento").asInt(0),
                    t.hasNonNull("precioSinDescuento") ? t.get("precioSinDescuento").asDouble() : null));
        }
        return new ResultadoBusqueda(consulta, raiz.path("encontrado").asBoolean(), texto(raiz, "nombre"),
                texto(raiz, "genero"), texto(raiz, "guardado"), guardar, tiendas);
    }

    private static String texto(JsonNode nodo, String campo) {
        return nodo.hasNonNull(campo) ? nodo.get(campo).asText() : null;
    }
}
