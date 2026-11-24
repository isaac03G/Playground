/**
 * Arma el historial de precios de un juego (últimos 12 meses) para la gráfica de la ficha de detalle.
 * <p>
 * Los datos salen de la colección {@code precios_historial}, que escribe
 * {@code playground-scripts/actualizador.py}: una fila por cada cambio de precio detectado
 * ({@code motivo}: {@code base} = punto de partida, {@code bajo} = bajó, {@code subio} = subió).
 * Aquí se convierten esas filas en una serie «escalonada» por tienda: el precio se mantiene hasta
 * que aparece el siguiente cambio.
 * </p>
 * Se expone a Thymeleaf/controlador como {@code historialPrecios}.
 */
package com.proyecto.aula.playgroundv2.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service("historialPrecios")
public class HistorialPrecioService {

    private static final Logger log = LoggerFactory.getLogger(HistorialPrecioService.class);

    /** Colección donde el actualizador guarda cada cambio de precio. */
    public static final String COLECCION = "precios_historial";

    /** Rango que muestra la gráfica. */
    public static final int DIAS = 365;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MongoTemplate mongo;

    public HistorialPrecioService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Un punto de la serie: precio en COP desde {@code fecha} (el motivo solo se pinta en cambios reales). */
    public record Punto(long t, double precio, String motivo) { }

    /** Serie de una tienda. */
    public record Serie(String tienda, List<Punto> puntos) { }

    /** Dato destacado (precio mínimo o máximo del año). */
    public record Extremo(String tienda, double precio, long t) { }

    /** Todo lo que necesita la vista. */
    public record Historial(long desde, long hasta, List<Serie> series, int bajadas, int subidas,
                            Integer mayorBajadaPct, Extremo minimo, Extremo maximo) {
        public boolean vacio() {
            return series == null || series.isEmpty();
        }
    }

    /** Historial vacío (el detalle muestra el mensaje «aún no hay cambios»). */
    public static Historial sinDatos() {
        long ahora = Instant.now().toEpochMilli();
        return new Historial(ahora - DIAS * 86_400_000L, ahora, List.of(), 0, 0, null, null, null);
    }

    /**
     * Construye el historial de los últimos {@value #DIAS} días de un juego.
     * Si Mongo falla o no hay datos devuelve un historial vacío: la ficha nunca debe romperse por esto.
     */
    public Historial obtener(String juegoId) {
        if (juegoId == null || juegoId.isBlank()) return sinDatos();
        try {
            return construir(juegoId);
        } catch (Exception e) {
            log.warn("No se pudo leer el historial de precios de {}: {}", juegoId, e.getMessage());
            return sinDatos();
        }
    }

    /** El historial ya serializado a JSON para pasarlo a la gráfica (JavaScript). */
    public String comoJson(Historial h) {
        try {
            return JSON.writeValueAsString(h);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------------------------------

    private Historial construir(String juegoId) {
        Instant hasta = Instant.now();
        Instant desde = hasta.minus(DIAS, ChronoUnit.DAYS);

        // actualizador.py guarda juegoId como ObjectId; otros scripts lo guardan como texto
        List<Object> ids = new ArrayList<>();
        ids.add(juegoId);
        if (ObjectId.isValid(juegoId)) ids.add(new ObjectId(juegoId));

        Query q = new Query(Criteria.where("juegoId").in(ids)).with(Sort.by("fecha").ascending());
        List<Document> filas = mongo.find(q, Document.class, COLECCION);
        if (filas.isEmpty()) return sinDatos();

        Map<String, List<Document>> porTienda = new LinkedHashMap<>();
        for (Document f : filas) {
            String tienda = f.getString("plataforma");
            if (tienda == null || fecha(f) == null || precio(f) == null) continue;
            porTienda.computeIfAbsent(tienda, k -> new ArrayList<>()).add(f);
        }

        List<Serie> series = new ArrayList<>();
        int bajadas = 0, subidas = 0;
        Integer mayorBajada = null;
        Extremo minimo = null, maximo = null;
        long tDesde = desde.toEpochMilli(), tHasta = hasta.toEpochMilli();

        for (Map.Entry<String, List<Document>> e : porTienda.entrySet()) {
            String tienda = e.getKey();
            List<Punto> puntos = new ArrayList<>();
            Document previo = null;      // último evento anterior a la ventana: fija el precio con el que arranca el año
            double anterior = Double.NaN;

            for (Document f : e.getValue()) {
                long t = fecha(f).getTime();
                double p = precio(f);
                String motivo = f.getString("motivo");
                if (t < tDesde) { previo = f; continue; }
                if (t > tHasta) continue;

                if (puntos.isEmpty()) {
                    // primer punto de la ventana: si había un precio vigente antes, la línea arranca con él
                    if (previo != null && t > tDesde) {
                        puntos.add(new Punto(tDesde, precio(previo), "base"));
                        anterior = precio(previo);
                    }
                }
                puntos.add(new Punto(t, p, motivo));

                boolean cambio = "bajo".equals(motivo) || "subio".equals(motivo);
                if (cambio && !Double.isNaN(anterior) && anterior > 0) {
                    if (p < anterior) {
                        bajadas++;
                        int pct = (int) Math.round((anterior - p) / anterior * 100);
                        if (mayorBajada == null || pct > mayorBajada) mayorBajada = pct;
                    } else if (p > anterior) {
                        subidas++;
                    }
                }
                if (minimo == null || p < minimo.precio()) minimo = new Extremo(tienda, p, t);
                if (maximo == null || p > maximo.precio()) maximo = new Extremo(tienda, p, t);
                anterior = p;
            }

            if (puntos.isEmpty() && previo != null) {
                // sin cambios en todo el año: línea plana con el precio vigente
                puntos.add(new Punto(tDesde, precio(previo), "base"));
                double p = precio(previo);
                if (minimo == null || p < minimo.precio()) minimo = new Extremo(tienda, p, tDesde);
                if (maximo == null || p > maximo.precio()) maximo = new Extremo(tienda, p, tDesde);
            }
            if (puntos.isEmpty()) continue;

            // la línea llega hasta hoy con el último precio conocido
            Punto ultimo = puntos.get(puntos.size() - 1);
            if (ultimo.t() < tHasta) puntos.add(new Punto(tHasta, ultimo.precio(), "hoy"));
            series.add(new Serie(tienda, puntos));
        }

        if (series.isEmpty()) return sinDatos();
        return new Historial(tDesde, tHasta, series, bajadas, subidas, mayorBajada, minimo, maximo);
    }

    private static Date fecha(Document f) {
        Object o = f.get("fecha");
        return o instanceof Date d ? d : null;
    }

    /** Precio en COP (si la fila no trae precioCOP se usa precio). */
    private static Double precio(Document f) {
        Object o = f.get("precioCOP") != null ? f.get("precioCOP") : f.get("precio");
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
