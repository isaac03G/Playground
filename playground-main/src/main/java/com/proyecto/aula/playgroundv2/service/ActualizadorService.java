package com.proyecto.aula.playgroundv2.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Ejecuta playground-scraper/actualizador.py desde el panel admin y guarda su salida en memoria
 * (ultimas {@value #MAX_LINEAS} lineas) para mostrarla en la pagina. Solo permite UNA ejecucion a la vez.
 * Reutiliza app.scraper.dir y app.scraper.python, igual que ScraperService.
 */
@Service
public class ActualizadorService {

    private static final String SCRIPT = "actualizador.py";
    private static final int MAX_LINEAS = 3000;
    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    /** modo -> argumentos extra del actualizador (nunca se usa --cada: no terminaria). */
    public static final Map<String, String[]> MODOS = new LinkedHashMap<>();
    public static final Map<String, String> TEXTOS = new LinkedHashMap<>();
    static {
        MODOS.put("completa", new String[] {"--con-tasas"});
        TEXTOS.put("completa", "Pasada completa (tasas + precios + juegos nuevos)");
        MODOS.put("automatico", new String[0]);
        TEXTOS.put("automatico", "Actualizacion automatica (precios + descubrimiento)");
        MODOS.put("precios", new String[] {"--solo", "actualizar"});
        TEXTOS.put("precios", "Solo refrescar precios");
        MODOS.put("todos", new String[] {"--solo", "actualizar", "--lote", "100000", "--antiguedad", "0"});
        TEXTOS.put("todos", "Revisar TODOS los juegos (puede tardar mucho)");
        MODOS.put("descubrir", new String[] {"--solo", "descubrir"});
        TEXTOS.put("descubrir", "Solo descubrir juegos nuevos");
        MODOS.put("simular", new String[] {"--simular"});
        TEXTOS.put("simular", "Simulacion (no escribe en la base de datos)");
        MODOS.put("estado", new String[] {"--ver"});
        TEXTOS.put("estado", "Ver estado del catalogo y ultimos cambios");
    }

    @Value("${app.scraper.dir:../playground-scraper}")
    private String scraperDir;

    @Value("${app.scraper.python:}")
    private String pythonConfigurado;

    private final List<String> lineas = new ArrayList<>();
    private long descartadas = 0;                 // lineas borradas del inicio (los indices son absolutos)
    private Process proceso;
    private String estado = "inactivo";           // inactivo | ejecutando | terminado | error | detenido
    private String descripcion = "";
    private String modo;
    private Instant inicio, fin;
    private boolean detenidoPorUsuario;

    public synchronized boolean enEjecucion() {
        return proceso != null && proceso.isAlive();
    }

    /** Lanza el actualizador. Lanza IllegalStateException/IllegalArgumentException con un mensaje legible. */
    public synchronized void iniciar(String modo) {
        if (enEjecucion()) {
            throw new IllegalStateException("Ya hay una actualizacion en curso: espera a que termine o detenla.");
        }
        String[] extra = MODOS.get(modo);
        if (extra == null) {
            throw new IllegalArgumentException("Modo de actualizacion no valido.");
        }
        Path dir = Paths.get(scraperDir).toAbsolutePath().normalize();
        if (!Files.exists(dir.resolve(SCRIPT))) {
            throw new IllegalStateException("No encuentro " + SCRIPT + " en " + dir
                    + ". Revisa app.scraper.dir en application.properties.");
        }
        List<String> cmd = new ArrayList<>(List.of(python(dir), "-u", SCRIPT));   // -u: salida sin buffer
        cmd.addAll(Arrays.asList(extra));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONUNBUFFERED", "1");
        lineas.clear();
        descartadas = 0;
        detenidoPorUsuario = false;
        try {
            proceso = pb.start();
        } catch (IOException e) {
            proceso = null;
            throw new IllegalStateException("No pude ejecutar Python (" + cmd.get(0)
                    + "). Revisa app.scraper.python. Detalle: " + e.getMessage());
        }
        estado = "ejecutando";
        this.modo = modo;
        descripcion = TEXTOS.get(modo);
        inicio = Instant.now();
        fin = null;
        agregar("[panel] " + HORA.format(inicio) + " - Iniciado: " + descripcion);
        Process p = proceso;
        Thread t = new Thread(() -> leer(p), "actualizador-log");
        t.setDaemon(true);
        t.start();
    }

    private void leer(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                agregar(l);
            }
        } catch (IOException e) {
            agregar("[panel] Error leyendo la salida: " + e.getMessage());
        }
        int codigo;
        try {
            codigo = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            codigo = -1;
        }
        synchronized (this) {
            if (p != proceso) {
                return;                            // ya empezo otra ejecucion
            }
            fin = Instant.now();
            estado = detenidoPorUsuario ? "detenido" : (codigo == 0 ? "terminado" : "error");
            agregar("[panel] " + HORA.format(fin) + " - Fin (codigo de salida " + codigo + ")");
        }
    }

    private synchronized void agregar(String linea) {
        lineas.add(linea);
        if (lineas.size() > MAX_LINEAS) {
            lineas.remove(0);
            descartadas++;
        }
    }

    /** Detiene la ejecucion en curso. Devuelve false si no habia nada corriendo. */
    public synchronized boolean detener() {
        if (!enEjecucion()) {
            return false;
        }
        detenidoPorUsuario = true;
        proceso.descendants().forEach(ProcessHandle::destroy);
        proceso.destroy();
        return true;
    }

    @PreDestroy
    public synchronized void alApagar() {
        if (enEjecucion()) {
            proceso.descendants().forEach(ProcessHandle::destroy);
            proceso.destroy();
        }
    }

    /** Estado + lineas nuevas desde el indice absoluto {@code desde} (para el sondeo de la pagina). */
    public synchronized Map<String, Object> estado(long desde) {
        long desdeReal = Math.max(desde, descartadas);
        int ini = (int) Math.min(lineas.size(), desdeReal - descartadas);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estado", estado);
        m.put("modo", modo);
        m.put("descripcion", descripcion);
        m.put("inicio", inicio == null ? null : HORA.format(inicio));
        m.put("fin", fin == null ? null : HORA.format(fin));
        m.put("lineas", new ArrayList<>(lineas.subList(ini, lineas.size())));
        m.put("siguiente", descartadas + lineas.size());
        return m;
    }

    /** Python del entorno virtual del scraper si existe; si no, el configurado o simplemente "python". */
    private String python(Path dir) {
        if (pythonConfigurado != null && !pythonConfigurado.isBlank()) {
            return pythonConfigurado.trim();
        }
        for (String relativa : new String[] {".venv/Scripts/python.exe", ".venv/bin/python"}) {
            Path candidato = dir.resolve(relativa);
            if (Files.exists(candidato)) {
                return candidato.toString();
            }
        }
        return "python";
    }
}
