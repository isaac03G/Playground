/**
 * Entidad de dominio que representa un videojuego del catálogo.
 * <p>
 * Se persiste en la colección MongoDB {@code juegos}. Incluye metadatos descriptivos,
 * precios por plataforma embebidos ({@link PrecioPlataforma}) y URLs de medios.
 * Los métodos {@link #getPrecioMinimo()} y {@link #getPlataformaMinima()} calculan
 * valores derivados en memoria y no se almacenan en base de datos.
 * </p>
 *
 * @see PrecioPlataforma
 * @see com.proyecto.aula.playgroundv2.repository.JuegoRepository
 */
package com.proyecto.aula.playgroundv2.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Documento MongoDB de un juego: catálogo, búsquedas y detalle en la UI.
 */
@Document(collection = "juegos")
public class Juego {

    /** Identificador del documento; Spring Data MongoDB mapea {@code @Id} ↔ {@code _id} automáticamente. */
    @Id
    private String id;

    /** Título del juego; índice único para evitar duplicados en el catálogo. */
    @Indexed(unique = true)
    private String nombre;

    /** Resumen o ficha descriptiva mostrada en listados y detalle. */
    private String descripcion;

    /** Estudio o empresa desarrolladora. */
    private String desarrolladora;

    /** Fecha de lanzamiento comercial (sin zona horaria; solo calendario). */
    private LocalDate fechaDeLanzamiento;

    /** Género principal (acción, RPG, etc.) usado en filtros. */
    private String genero;

    /** Lista de precios por tienda/plataforma; documentos embebidos, no referencias. */
    private List<PrecioPlataforma> precios;

    /** URL de la imagen principal original (portada de la tienda o subida). */
    private String imagenUrl;

    /** Portada 3:4 (600×800) generada por los scrapers para las tarjetas del catálogo. */
    private String imagenNormalizadaUrl;

    /** SHA-256 de los bytes de la imagen original; evita reprocesar si no cambió. */
    private String imagenOriginalHash;

    /** URL realmente usada para generar la portada normalizada (vertical oficial u horizontal). */
    private String imagenFuenteUrl;

    /** Portada vertical oficial de la tienda (Steam library_600x900, Epic Tall, GOG coverVertical). */
    private String imagenVerticalUrl;

    /** true si la portada vertical falló; los scrapers no vuelven a intentarla. */
    private Boolean imagenVerticalNoDisponible;

    /** URLs de capturas adicionales para la galería del detalle. */
    private List<String> screenshots;

    /** Banner horizontal de SteamGridDB, cacheado junto al juego. */
    @JsonIgnore
    private String steamGridDbBannerUrl;

    /** Evita repetir búsquedas sin resultado en SteamGridDB. */
    @JsonIgnore
    private Boolean steamGridDbBannerBuscado;

    /** Indica si la búsqueda cacheada se hizo con una clave de SteamGridDB disponible. */
    @JsonIgnore
    private Boolean steamGridDbBannerSgdbConsultado;

    /** Constructor vacío requerido por Spring Data y deserialización. */
    public Juego() {}

    /**
     * Devuelve el menor precio entre todas las plataformas con precio no nulo.
     * <p>
     * No se persiste: es un valor calculado para comparativas y vistas.
     * </p>
     *
     * @return precio mínimo, o {@code 0.0} si no hay precios o la lista está vacía
     */
    public double getPrecioMinimo() {
        if (precios == null || precios.isEmpty()) return 0.0;
        return precios.stream()
                .filter(Objects::nonNull)          // un elemento nulo en la lista no debe tumbar la página
                .map(PrecioPlataforma::getPrecio)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .min()
                .orElse(0.0);
    }

    /**
     * Devuelve el nombre de la plataforma que ofrece el precio más bajo.
     * <p>
     * En empate, el stream de Java conserva el primer mínimo encontrado según el orden de la lista.
     * </p>
     *
     * @return nombre de plataforma con precio mínimo, o {@code null} si no hay precios válidos
     */
    public String getPlataformaMinima() {
        if (precios == null || precios.isEmpty()) return null;
        return precios.stream()
                .filter(p -> p != null && p.getPrecio() != null)
                .min(Comparator.comparing(PrecioPlataforma::getPrecio))
                .map(PrecioPlataforma::getPlataforma)
                .orElse(null);
    }

    /** Tiendas que siempre se muestran en las tarjetas comparadoras, en este orden. */
    private static final List<String> TIENDAS_PRINCIPALES = List.of("Steam", "Epic Games", "GOG");

    /**
     * Las tres tiendas principales (Steam, Epic Games, GOG) en orden fijo, para pintar siempre las mismas filas
     * en las tarjetas. Si el juego no está en una tienda se devuelve una fila con precio {@code null} (se muestra
     * como «—»). Las tiendas extra, si las hubiera, van al final. No se persiste ni sale en el JSON de la API.
     */
    @JsonIgnore
    public List<PrecioPlataforma> getTiendasOrdenadas() {
        List<PrecioPlataforma> salida = new ArrayList<>();
        for (String tienda : TIENDAS_PRINCIPALES) {
            PrecioPlataforma encontrada = null;
            if (precios != null) {
                for (PrecioPlataforma p : precios) {
                    if (p != null && tienda.equalsIgnoreCase(p.getPlataforma())) { encontrada = p; break; }
                }
            }
            salida.add(encontrada != null ? encontrada : new PrecioPlataforma(tienda, null));
        }
        if (precios != null) {
            for (PrecioPlataforma p : precios) {
                if (p != null && p.getPlataforma() != null
                        && TIENDAS_PRINCIPALES.stream().noneMatch(t -> t.equalsIgnoreCase(p.getPlataforma()))) {
                    salida.add(p);
                }
            }
        }
        return salida;
    }

    /** Cantidad de tiendas que tienen precio para este juego. */
    public int getTiendasConPrecio() {
        if (precios == null) return 0;
        return (int) precios.stream().filter(p -> p != null && p.getPrecio() != null).count();
    }

    /** Precio más alto entre las tiendas ({@code 0.0} si no hay precios). */
    public double getPrecioMaximo() {
        if (precios == null) return 0.0;
        return precios.stream()
                .filter(p -> p != null && p.getPrecio() != null)
                .mapToDouble(PrecioPlataforma::getPrecio)
                .max()
                .orElse(0.0);
    }

    /**
     * Diferencia entre la tienda más cara y la más barata. Es {@code 0.0} si el juego tiene precio en menos
     * de dos tiendas (no hay nada que comparar). No se persiste.
     */
    public double getDiferenciaPrecios() {
        return getTiendasConPrecio() < 2 ? 0.0 : getPrecioMaximo() - getPrecioMinimo();
    }

    /**
     * Cuánto más barata es la mejor tienda respecto a la más cara, en porcentaje entero (0-100).
     * Ejemplo: 60 000 en Steam y 40 000 en GOG -> 33. {@code 0} si no hay diferencia.
     */
    public int getPorcentajeAhorro() {
        double max = getPrecioMaximo();
        return (max <= 0 || getDiferenciaPrecios() <= 0) ? 0 : (int) Math.round(getDiferenciaPrecios() / max * 100);
    }

    /**
     * Mayor porcentaje de bajada entre las tiendas cuyo precio bajó hace poco
     * (ver {@link PrecioPlataforma#isBajoDePrecio()}). Sirve para la etiqueta de oferta de las tarjetas.
     * <p>
     * No se persiste: es un valor calculado en memoria.
     * </p>
     *
     * @return porcentaje (1-100), o {@code 0} si ninguna tienda tiene una bajada reciente
     */
    public int getMayorBajada() {
        if (precios == null || precios.isEmpty()) return 0;
        return precios.stream()
                .filter(p -> p != null && p.isBajoDePrecio())
                .map(PrecioPlataforma::getDescuento)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    /**
     * Porcentaje de descuento que se muestra para una tienda en las tarjetas y en la ficha del juego
     * (así TODOS los juegos lo muestran):
     * <ul>
     *   <li>si la tienda tuvo una bajada de precio reciente ({@link PrecioPlataforma#isBajoDePrecio()}), ese %;</li>
     *   <li>si no, cuánto más barata es frente a la tienda más cara de este mismo juego;</li>
     *   <li>{@code 0} si la tienda no tiene precio, el juego solo está en una tienda o todas cuestan lo mismo.</li>
     * </ul>
     * No se persiste. Se usa desde Thymeleaf: {@code juego.porcentajeTienda(t)}.
     */
    public int porcentajeTienda(PrecioPlataforma p) {
        if (p == null || p.getPrecio() == null) return 0;
        if (p.isBajoDePrecio()) return p.getDescuento();
        double max = getPrecioMaximo();
        if (getTiendasConPrecio() < 2 || max <= 0) return 0;
        return (int) Math.round((max - p.getPrecio()) / max * 100);
    }

    /** Mayor {@link #porcentajeTienda} entre las tiendas: es el que sale en la etiqueta sobre la imagen. */
    public int getMejorDescuento() {
        if (precios == null) return 0;
        return precios.stream()
                .filter(Objects::nonNull)
                .mapToInt(this::porcentajeTienda)
                .max()
                .orElse(0);
    }

    /**
     * Alias semántico de {@link #id} para plantillas Thymeleaf y servicios legacy.
     * Evita romper referencias que usan {@code juegoId} en lugar de {@code id}.
     */
    public String getJuegoId() { return id; }

    /**
     * Establece el identificador mediante el alias {@code juegoId}.
     *
     * @param juegoId mismo valor que {@link #id}
     */
    public void setJuegoId(String juegoId) { this.id = juegoId; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getDesarrolladora() { return desarrolladora; }
    public void setDesarrolladora(String desarrolladora) { this.desarrolladora = desarrolladora; }

    public LocalDate getFechaDeLanzamiento() { return fechaDeLanzamiento; }
    public void setFechaDeLanzamiento(LocalDate fechaDeLanzamiento) { this.fechaDeLanzamiento = fechaDeLanzamiento; }

    public String getGenero() { return genero; }
    public void setGenero(String genero) { this.genero = genero; }

    public List<PrecioPlataforma> getPrecios() { return precios; }
    public void setPrecios(List<PrecioPlataforma> precios) { this.precios = precios; }

    public String getImagenUrl() { return imagenUrl; }
    public void setImagenUrl(String imagenUrl) { this.imagenUrl = imagenUrl; }

    public String getImagenNormalizadaUrl() { return imagenNormalizadaUrl; }
    public void setImagenNormalizadaUrl(String imagenNormalizadaUrl) { this.imagenNormalizadaUrl = imagenNormalizadaUrl; }

    public String getImagenOriginalHash() { return imagenOriginalHash; }
    public void setImagenOriginalHash(String imagenOriginalHash) { this.imagenOriginalHash = imagenOriginalHash; }

    public String getImagenFuenteUrl() { return imagenFuenteUrl; }
    public void setImagenFuenteUrl(String imagenFuenteUrl) { this.imagenFuenteUrl = imagenFuenteUrl; }

    public String getImagenVerticalUrl() { return imagenVerticalUrl; }
    public void setImagenVerticalUrl(String imagenVerticalUrl) { this.imagenVerticalUrl = imagenVerticalUrl; }

    public Boolean getImagenVerticalNoDisponible() { return imagenVerticalNoDisponible; }
    public void setImagenVerticalNoDisponible(Boolean imagenVerticalNoDisponible) { this.imagenVerticalNoDisponible = imagenVerticalNoDisponible; }

    /**
     * URL para las tarjetas del catálogo: portada normalizada si existe; si no, la original.
     * La ficha de detalle debe seguir usando {@link #getImagenUrl()}.
     */
    public String getImagenCatalogoUrl() {
        if (imagenNormalizadaUrl != null && !imagenNormalizadaUrl.isBlank()) {
            return imagenNormalizadaUrl;
        }
        return imagenUrl;
    }

    public List<String> getScreenshots() { return screenshots; }
    public void setScreenshots(List<String> screenshots) { this.screenshots = screenshots; }

    @JsonIgnore
    public String getSteamGridDbBannerUrl() { return steamGridDbBannerUrl; }
    public void setSteamGridDbBannerUrl(String steamGridDbBannerUrl) { this.steamGridDbBannerUrl = steamGridDbBannerUrl; }

    @JsonIgnore
    public Boolean getSteamGridDbBannerBuscado() { return steamGridDbBannerBuscado; }
    public void setSteamGridDbBannerBuscado(Boolean steamGridDbBannerBuscado) { this.steamGridDbBannerBuscado = steamGridDbBannerBuscado; }

    @JsonIgnore
    public Boolean getSteamGridDbBannerSgdbConsultado() { return steamGridDbBannerSgdbConsultado; }
    public void setSteamGridDbBannerSgdbConsultado(Boolean steamGridDbBannerSgdbConsultado) { this.steamGridDbBannerSgdbConsultado = steamGridDbBannerSgdbConsultado; }
}
