/**
 * Pone en el modelo de TODAS las vistas la moneda que eligió el usuario y las monedas disponibles,
 * para que el selector de la barra de navegación y los precios funcionen sin tocar cada controlador.
 * <ul>
 *   <li>{@code monedaActual}: código de la moneda elegida (cookie {@code moneda}); COP por defecto</li>
 *   <li>{@code monedasDisponibles}: códigos que tienen tasa guardada</li>
 * </ul>
 */
package com.proyecto.aula.playgroundv2.config;

import java.util.List;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.proyecto.aula.playgroundv2.service.TasaCambioService;

@ControllerAdvice
public class MonedaAdvice {

    private final TasaCambioService monedas;

    public MonedaAdvice(TasaCambioService monedas) {
        this.monedas = monedas;
    }

    @ModelAttribute("monedaActual")
    public String monedaActual(@CookieValue(name = "moneda", required = false) String cookie) {
        return monedas
        .normalizar(cookie);
    }

    @ModelAttribute("monedasDisponibles")
    public List<String> monedasDisponibles() {
        return monedas.monedasDisponibles();
    }
}