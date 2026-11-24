package com.proyecto.aula.playgroundv2.controller;

import com.proyecto.aula.playgroundv2.service.JuegoService;
import com.proyecto.aula.playgroundv2.service.TasaCambioService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import com.proyecto.aula.playgroundv2.model.Juego;

/**
 * Controlador MVC del buscador avanzado de juegos.
 * <p>
 * Ruta base: {@code /buscar}. Permite combinar filtros por nombre, género y plataforma de venta.
 * La ruta {@code /buscar} está en {@code permitAll} en la configuración de seguridad.
 * </p>
 *
 * @see JuegoService#buscarAvanzado
 */
@Controller
@RequestMapping("/buscar")
public class BuscadorController {

    private final JuegoService juegoService;
    private final TasaCambioService monedas;

    public BuscadorController(JuegoService juegoService, TasaCambioService monedas) {
        this.juegoService = juegoService;
        this.monedas = monedas;
    }

    /**
     * Ejecuta una búsqueda avanzada (nombre, género, tienda y rango de precio) y muestra los resultados paginados.
     * Los precios {@code precioMin}/{@code precioMax} van en la moneda que el usuario eligió (cookie {@code moneda}).
     * <ul>
     *   <li><b>URL:</b> {@code GET /buscar}</li>
     *   <li><b>Acceso:</b> público (sin login)</li>
     *   <li><b>Parámetros:</b> {@code nombre}, {@code genero}, {@code plataformaVenta} (todos opcionales);
     *       {@code page} (0), {@code size} (12)</li>
     *   <li><b>Retorno:</b> vista {@code buscador} con {@code resultados} ({@code Page<Juego>})
     *       y los criterios de búsqueda en el modelo para repoblar el formulario</li>
     * </ul>
     */
    @GetMapping
    public String buscar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) String genero,
            @RequestParam(required = false) String plataformaVenta,
            @RequestParam(required = false) Double precioMin,
            @RequestParam(required = false) Double precioMax,
            @RequestParam(required = false) String orden,
            @CookieValue(name = "moneda", required = false) String cookieMoneda,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            Model model) {

        // El usuario escribe los precios en la moneda que está viendo; la base de datos guarda COP.
        String moneda = monedas.normalizar(cookieMoneda);
        double factor = monedas.convertirDesdeCop(1.0, moneda);          // 1 COP = factor [moneda]
        if (factor <= 0) factor = 1.0;
        double holgura = "COP".equals(moneda) ? 0.5 : 0.005;              // lo que se ve redondeado no debe quedar fuera
        Double minCop = precioMin == null ? null : Math.max(0, (precioMin - holgura) / factor);
        Double maxCop = precioMax == null ? null : Math.max(0, (precioMax + holgura) / factor);
        if (minCop != null && maxCop != null && minCop > maxCop) {        // escribió el rango al revés
            Double t = minCop; minCop = maxCop; maxCop = t;
            Double u = precioMin; precioMin = precioMax; precioMax = u;
        }

        Page<Juego> resultados = juegoService.buscarAvanzado(nombre, genero, plataformaVenta,
                minCop, maxCop, orden, Math.max(page, 0), size);

        model.addAttribute("resultados", resultados);
        model.addAttribute("nombre", nombre);
        model.addAttribute("genero", genero);
        model.addAttribute("plataformaVenta", plataformaVenta);
        model.addAttribute("precioMin", precioMin);
        model.addAttribute("precioMax", precioMax);
        model.addAttribute("orden", orden);

        return "buscador";
    }
}
