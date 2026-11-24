/**
 * Convierte y da formato a los precios para mostrarlos en la moneda que elija el usuario.
 * <p>
 * Todos los precios del catálogo están guardados en pesos colombianos (COP). Las tasas de cambio
 * las mantiene al día el script {@code playground-scraper/tasas_cambio.py} en la colección
 * {@code tasas_cambio} (documento {@code _id = "actual"}, tasas con base USD). Este servicio solo las
 * LEE (con una caché corta para no consultar Mongo en cada tarjeta de la página).
 * </p>
 * Se expone a Thymeleaf como {@code @monedas}:
 * <pre>th:text="${@monedas.formatear(juego.precioMinimo, monedaActual)}"</pre>
 * Si todavía no hay tasas guardadas, solo se ofrece COP y todo sigue funcionando.
 */
package com.proyecto.aula.playgroundv2.service;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service("monedas")
public class TasaCambioService {

    private static final Logger log = LoggerFactory.getLogger(TasaCambioService.class);

    /** Moneda en la que están guardados los precios del catálogo. */
    public static final String BASE = "COP";

    /** Monedas que se muestran sin decimales (igual que en tasas_cambio.py). */
    private static final Set<String> SIN_DECIMALES =
            Set.of("COP", "CLP", "JPY", "KRW", "VND", "PYG", "ISK", "HUF", "UGX");

    /** Cuánto tiempo se reutilizan las tasas leídas antes de volver a consultar Mongo. */
    private static final Duration CACHE = Duration.ofMinutes(5);

    private final MongoTemplate mongo;
    private final String coleccion;

    private volatile Datos datos = new Datos(Map.of(), null, null, Instant.EPOCH);

    public TasaCambioService(MongoTemplate mongo,
                             @Value("${app.monedas.coleccion:tasas_cambio}") String coleccion) {
        this.mongo = mongo;
        this.coleccion = coleccion;
    }

    /** Foto de las tasas leídas de Mongo. */
    private record Datos(Map<String, Double> tasas, String atribucion, Instant actualizadoEn, Instant leidoEn) {}

    // ------------------------------------------------------------------ consulta

    /** Códigos disponibles para el selector: COP primero y luego el resto en orden alfabético. */
    public List<String> monedasDisponibles() {
        Map<String, Double> t = actuales().tasas();
        List<String> lista = new ArrayList<>();
        lista.add(BASE);
        t.keySet().stream().filter(m -> !m.equals(BASE)).sorted().forEach(lista::add);
        return lista;
    }

    /** Devuelve el código si es una moneda con tasa conocida; si no, COP. Acepta {@code null}. */
    public String normalizar(String codigo) {
        if (codigo == null) return BASE;
        String c = codigo.trim().toUpperCase(Locale.ROOT);
        return (c.equals(BASE) || actuales().tasas().containsKey(c)) ? c : BASE;
    }

    /** {@code true} si el código existe en las tasas guardadas (o es COP). */
    public boolean esValida(String codigo) {
        if (codigo == null) return false;
        String c = codigo.trim().toUpperCase(Locale.ROOT);
        return c.equals(BASE) || actuales().tasas().containsKey(c);
    }

    /** Texto de atribución exigido por ExchangeRate-API, o {@code null} si la fuente no lo requiere. */
    public String atribucion() {
        return actuales().atribucion();
    }

    /** Cuándo se actualizaron las tasas por última vez ({@code null} si no hay). */
    public Instant actualizadoEn() {
        return actuales().actualizadoEn();
    }

    // ----------------------------------------------------------------- conversión

    /**
     * Convierte un monto en COP a la moneda indicada.
     * Si no hay tasa para esa moneda devuelve el mismo monto (la vista usará COP).
     */
    public double convertirDesdeCop(double montoCop, String moneda) {
        String destino = normalizar(moneda);
        if (destino.equals(BASE)) return montoCop;
        Map<String, Double> t = actuales().tasas();
        Double tasaCop = t.get(BASE);
        Double tasaDestino = t.get(destino);
        if (tasaCop == null || tasaDestino == null || tasaCop <= 0) return montoCop;
        return montoCop * tasaDestino / tasaCop;
    }

    /**
     * Texto listo para mostrar: {@code "59.900 COP"}, {@code "14,62 USD"}, {@code "Gratis"} o {@code "—"}.
     *
     * @param montoCop precio base en COP (puede ser {@code null})
     * @param moneda   moneda elegida por el usuario (se valida; si no existe se usa COP)
     */
    public String formatear(Double montoCop, String moneda) {
        if (montoCop == null) return "—";
        boolean ingles = "en".equals(LocaleContextHolder.getLocale().getLanguage());
        if (montoCop == 0) return ingles ? "Free" : "Gratis";
        String destino = normalizar(moneda);
        double valor = convertirDesdeCop(montoCop, destino);
        NumberFormat nf = NumberFormat.getNumberInstance(ingles ? Locale.US : Locale.forLanguageTag("es-CO"));
        int decimales = SIN_DECIMALES.contains(destino) ? 0 : 2;
        nf.setMinimumFractionDigits(decimales);
        nf.setMaximumFractionDigits(decimales);
        return nf.format(valor) + " " + destino;
    }

    // ----------------------------------------------------------------- lectura de Mongo

    private Datos actuales() {
        Datos d = datos;
        if (Duration.between(d.leidoEn(), Instant.now()).compareTo(CACHE) < 0) {
            return d;
        }
        synchronized (this) {
            d = datos;
            if (Duration.between(d.leidoEn(), Instant.now()).compareTo(CACHE) < 0) {
                return d;
            }
            datos = leer(d);
            return datos;
        }
    }

    /** Lee el documento de tasas; si Mongo falla conserva las últimas tasas buenas (o solo COP). */
    private Datos leer(Datos anterior) {
        try {
            Document doc = mongo.findOne(Query.query(Criteria.where("_id").is("actual")), Document.class, coleccion);
            if (doc == null || !(doc.get("tasas") instanceof Document crudas)) {
                return new Datos(Map.of(), null, null, Instant.now());
            }
            Map<String, Double> tasas = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : crudas.entrySet()) {
                if (e.getValue() instanceof Number n && n.doubleValue() > 0 && Double.isFinite(n.doubleValue())) {
                    tasas.put(e.getKey().toUpperCase(Locale.ROOT), n.doubleValue());
                }
            }
            // Sin tasa del COP no se puede convertir nada: se trabaja solo en COP.
            if (!tasas.containsKey(BASE)) {
                return new Datos(Map.of(), null, null, Instant.now());
            }
            Instant actualizado = doc.get("actualizadoEn") instanceof Date f ? f.toInstant() : null;
            return new Datos(Collections.unmodifiableMap(tasas), doc.getString("atribucion"), actualizado, Instant.now());
        } catch (RuntimeException e) {
            log.warn("No pude leer las tasas de cambio ({}): {}", coleccion, e.getMessage());
            return new Datos(anterior.tasas(), anterior.atribucion(), anterior.actualizadoEn(), Instant.now());
        }
    }
}