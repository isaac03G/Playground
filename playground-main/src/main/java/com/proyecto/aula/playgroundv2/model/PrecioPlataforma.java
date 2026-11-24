/**
 * Modelo embebido que representa el precio de un juego en una tienda o plataforma concreta.
 * <p>
 * No es un documento independiente en MongoDB: se persiste como elemento dentro de la lista
 * {@code precios} del documento {@link Juego}. Se usa en vistas y cálculos de precio mínimo.
 * </p>
 *
 * @see Juego#getPrecios()
 * @see Juego#getPrecioMinimo()
 */
package com.proyecto.aula.playgroundv2.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Par (plataforma, precio) asociado a un juego del catálogo.
 */
public class PrecioPlataforma {

    /** Nombre de la tienda o plataforma (por ejemplo Steam, Epic, GOG). */
    private String plataforma;

    /**
     * Precio base en pesos colombianos (COP); puede ser {@code null} si no está definido.
     * Los scrapers convierten a COP lo que llega en otra moneda (p. ej. GOG en USD).
     */
    private Double precio;

    /** Moneda de {@link #precio}: siempre {@code "COP"} (o {@code null} en documentos antiguos = COP). */
    private String moneda;

    /** Indica si el juego es gratis en esa tienda (lo escribe el scraper). */
    private Boolean esGratis;

    /** Precio tal como lo entrega la tienda, solo si estaba en otra moneda (p. ej. 9.99). */
    private Double precioOriginal;

    /** Moneda de {@link #precioOriginal} (p. ej. {@code "USD"}). */
    private String monedaOriginal;

    // --- Seguimiento de cambios: los escribe playground-scraper/actualizador.py (pueden ser null) ---

    /** Precio (COP) que tenía antes del último cambio detectado; {@code null} si nunca ha cambiado. */
    private Double precioAnterior;

    /** Porcentaje del último cambio: positivo = bajó de precio, negativo = subió. */
    private Integer descuento;

    /** Momento en que el precio cambió por última vez. */
    private Instant fechaCambio;

    /** Última vez que se verificó este precio directamente en la tienda. */
    private Instant ultimaRevision;
    /**
     * Enlace directo al juego en esa tienda (lo guardan los scrapers). Puede ser {@code null} en juegos
     * cargados antes de que existiera este campo: en ese caso {@link #urlTienda(String)} lleva a la
     * búsqueda del juego dentro de la tienda.
     */
    private String url;

    /** Días durante los que una bajada de precio se muestra como oferta en la web. */
    public static final int DIAS_OFERTA = 14;

    /** Constructor sin argumentos requerido por frameworks de serialización y MongoDB. */
    public PrecioPlataforma() {}

    /**
     * Constructor con todos los campos útiles para pruebas o creación programática.
     *
     * @param plataforma identificador legible de la tienda
     * @param precio     importe; puede ser {@code null}
     */
    public PrecioPlataforma(String plataforma, Double precio) {
        this.plataforma = plataforma;
        this.precio = precio;
    }

    public String getPlataforma() { return plataforma; }
    public void setPlataforma(String plataforma) { this.plataforma = plataforma; }

    public Double getPrecio() { return precio; }
    public void setPrecio(Double precio) { this.precio = precio; }

    public String getMoneda() { return moneda; }
    public void setMoneda(String moneda) { this.moneda = moneda; }

    public Boolean getEsGratis() { return esGratis; }
    public void setEsGratis(Boolean esGratis) { this.esGratis = esGratis; }

    public Double getPrecioOriginal() { return precioOriginal; }
    public void setPrecioOriginal(Double precioOriginal) { this.precioOriginal = precioOriginal; }

    public String getMonedaOriginal() { return monedaOriginal; }
    public void setMonedaOriginal(String monedaOriginal) { this.monedaOriginal = monedaOriginal; }

    public Double getPrecioAnterior() { return precioAnterior; }
    public void setPrecioAnterior(Double precioAnterior) { this.precioAnterior = precioAnterior; }

    public Integer getDescuento() { return descuento; }
    public void setDescuento(Integer descuento) { this.descuento = descuento; }

    public Instant getFechaCambio() { return fechaCambio; }
    public void setFechaCambio(Instant fechaCambio) { this.fechaCambio = fechaCambio; }

    public Instant getUltimaRevision() { return ultimaRevision; }
    public void setUltimaRevision(Instant ultimaRevision) { this.ultimaRevision = ultimaRevision; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    /**
     * Dirección a la que debe llevar el clic en esta tienda.
     * <ol>
     *   <li>El enlace directo guardado por el scraper, si existe (solo se aceptan http/https).</li>
     *   <li>Si no, la búsqueda del juego por nombre dentro de la propia tienda (Steam, GOG o Epic).</li>
     * </ol>
     *
     * @param nombreJuego nombre del juego, para armar la búsqueda de respaldo
     * @return URL absoluta, o {@code null} si la tienda no se reconoce y no hay enlace guardado
     */
    public String urlTienda(String nombreJuego) {
        if (url != null) {
            String u = url.trim();
            String bajo = u.toLowerCase();
            if (bajo.startsWith("https://") || bajo.startsWith("http://")) return u;
        }
        if (plataforma == null || nombreJuego == null || nombreJuego.isBlank()) return null;
        String q = URLEncoder.encode(nombreJuego.trim(), StandardCharsets.UTF_8);
        return switch (plataforma.trim()) {
            case "Steam" -> "https://store.steampowered.com/search/?term=" + q;
            case "GOG" -> "https://www.gog.com/en/games?query=" + q;
            case "Epic Games" -> "https://store.epicgames.com/es-ES/browse?q=" + q + "&sortBy=relevancy&sortDir=DESC&count=40";
            default -> null;
        };
    }

    /**
     * Indica si el precio bajó hace poco (en los últimos {@value #DIAS_OFERTA} días), para mostrarlo como oferta.
     * <p>No se persiste: es un valor calculado (no tiene campo propio).</p>
     */
    public boolean isBajoDePrecio() {
        return descuento != null && descuento > 0 && fechaCambio != null
                && fechaCambio.isAfter(Instant.now().minus(DIAS_OFERTA, ChronoUnit.DAYS));
    }
}