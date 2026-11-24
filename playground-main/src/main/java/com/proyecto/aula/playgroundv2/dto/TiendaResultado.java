/**
 * Resultado de la búsqueda de un juego en UNA tienda (Steam, GOG o Epic Games).
 * <p>
 * {@code estado}: {@code encontrado}, {@code sin_precio}, {@code no_encontrado} o {@code error}.
 * Es {@link Serializable} porque viaja en un atributo flash de la sesión.
 * </p>
 */
package com.proyecto.aula.playgroundv2.dto;

import java.io.Serializable;
import java.text.NumberFormat;
import java.util.Locale;

public class TiendaResultado implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String tienda;
    private final String estado;
    private final String nombre;
    private final Double precio;
    private final String moneda;
    private final String detalle;
    private final Double precioOriginal;
    private final String monedaOriginal;
    /** % de descuento vigente en la tienda (0 = sin descuento). */
    private final Integer descuento;
    /** Precio antes del descuento, en la misma moneda que {@code precio}; {@code null} si no hay descuento. */
    private final Double precioSinDescuento;

    public TiendaResultado(String tienda, String estado, String nombre, Double precio, String moneda,
                           String detalle, Double precioOriginal, String monedaOriginal) {
        this(tienda, estado, nombre, precio, moneda, detalle, precioOriginal, monedaOriginal, 0, null);
    }

    public TiendaResultado(String tienda, String estado, String nombre, Double precio, String moneda,
                           String detalle, Double precioOriginal, String monedaOriginal,
                           Integer descuento, Double precioSinDescuento) {
        this.tienda = tienda;
        this.estado = estado;
        this.nombre = nombre;
        this.precio = precio;
        this.moneda = moneda;
        this.detalle = detalle;
        this.precioOriginal = precioOriginal;
        this.monedaOriginal = monedaOriginal;
        this.descuento = descuento == null ? 0 : descuento;
        this.precioSinDescuento = precioSinDescuento;
    }

    public String getTienda() { return tienda; }
    public String getEstado() { return estado; }
    public String getNombre() { return nombre; }
    public Double getPrecio() { return precio; }
    public String getMoneda() { return moneda; }
    public String getDetalle() { return detalle; }
    public Double getPrecioOriginal() { return precioOriginal; }
    public String getMonedaOriginal() { return monedaOriginal; }
    public Integer getDescuento() { return descuento; }
    public Double getPrecioSinDescuento() { return precioSinDescuento; }

    /** ¿La tienda tiene este juego con descuento ahora mismo? */
    public boolean isConDescuento() { return descuento != null && descuento > 0; }

    /** "59.900 COP" (precio antes del descuento) o {@code null} si no hay descuento. */
    public String getPrecioSinDescuentoTexto() {
        if (precioSinDescuento == null || !isConDescuento()) return null;
        String codigo = (moneda == null || moneda.isBlank()) ? "COP" : moneda.toUpperCase();
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
        int decimales = "COP".equals(codigo) ? 0 : 2;
        nf.setMinimumFractionDigits(decimales);
        nf.setMaximumFractionDigits(decimales);
        return nf.format(precioSinDescuento) + " " + codigo;
    }

    /** "9,99 USD" cuando el precio se convirtió a COP; {@code null} si ya venía en COP. */
    public String getPrecioOriginalTexto() {
        if (precioOriginal == null || monedaOriginal == null || monedaOriginal.isBlank()) return null;
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(precioOriginal) + " " + monedaOriginal.toUpperCase();
    }

    /** "59.900 COP", "Gratis" o "—" si la tienda no muestra precio. */
    public String getPrecioTexto() {
        if (precio == null) return "—";
        if (precio == 0) return "Gratis";
        String codigo = (moneda == null || moneda.isBlank()) ? "COP" : moneda.toUpperCase();
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CO"));
        int decimales = "COP".equals(codigo) ? 0 : 2;
        nf.setMinimumFractionDigits(decimales);
        nf.setMaximumFractionDigits(decimales);
        return nf.format(precio) + " " + codigo;
    }
}