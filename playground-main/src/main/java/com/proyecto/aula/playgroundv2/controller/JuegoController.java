package com.proyecto.aula.playgroundv2.controller;

import java.util.NoSuchElementException;

import com.proyecto.aula.playgroundv2.model.Juego;
import com.proyecto.aula.playgroundv2.model.Usuario;
import com.proyecto.aula.playgroundv2.service.HistorialPrecioService;
import com.proyecto.aula.playgroundv2.service.JuegoService;
import com.proyecto.aula.playgroundv2.service.Mensajes;
import com.proyecto.aula.playgroundv2.service.SteamGridDbService;
import com.proyecto.aula.playgroundv2.service.UsuarioService;
import com.proyecto.aula.playgroundv2.service.WishlistService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Controlador MVC para la consulta pública del catálogo de juegos.
 * <p>
 * Ruta base: {@code /juegos}. Renderiza vistas Thymeleaf (no devuelve JSON).
 * Según {@link com.proyecto.aula.playgroundv2.config.SecurityConfig}, todas las rutas
 * bajo {@code /juegos/**} son accesibles sin autenticación ({@code permitAll}).
 * </p>
 *
 * @see JuegoService
 * @see WishlistService
 */
@Controller
@RequestMapping("/juegos")
public class JuegoController {

    private final JuegoService juegosService;
    private final WishlistService wishlistService;
    private final UsuarioService usuarioService;
    private final Mensajes mensajes;
    private final SteamGridDbService steamGridDbService;
    private final HistorialPrecioService historialPrecioService;

    public JuegoController(JuegoService juegosService, WishlistService wishlistService,
                           UsuarioService usuarioService, Mensajes mensajes,
                           SteamGridDbService steamGridDbService,
                           HistorialPrecioService historialPrecioService) {
        this.juegosService = juegosService;
        this.wishlistService = wishlistService;
        this.usuarioService = usuarioService;
        this.mensajes = mensajes;
        this.steamGridDbService = steamGridDbService;
        this.historialPrecioService = historialPrecioService;
    }

    /**
     * Lista el catálogo de juegos con filtros opcionales y paginación.
     * <ul>
     *   <li><b>URL:</b> {@code GET /juegos}</li>
     *   <li><b>Acceso:</b> público (sin login)</li>
     *   <li><b>Parámetros:</b> {@code genero}, {@code desarrolladora} (opcionales);
     *       {@code page} (por defecto 0), {@code size} (por defecto 12)</li>
     *   <li><b>Retorno:</b> vista {@code juegos} con {@code Page<Juego>} en el modelo,
     *       más {@code generoSeleccionado} y {@code desarrolladoraSeleccionada}</li>
     * </ul>
     */
    @GetMapping
    public String listarJuegos(
            @RequestParam(required = false) String genero,
            @RequestParam(required = false) String desarrolladora,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            Model model) {
        Page<Juego> juegos = juegosService.filtrar(genero, desarrolladora, page, size);
        model.addAttribute("juegos", juegos);
        model.addAttribute("generoSeleccionado", genero);
        model.addAttribute("desarrolladoraSeleccionada", desarrolladora);
        return "juegos";
    }

    /**
     * Lista juegos filtrados por un género concreto (ruta amigable).
     * <ul>
     *   <li><b>URL:</b> {@code GET /juegos/genero/{genero}}</li>
     *   <li><b>Acceso:</b> público</li>
     *   <li><b>Parámetros:</b> {@code genero} en la ruta; {@code page}, {@code size} en query</li>
     *   <li><b>Retorno:</b> vista {@code juegos} con resultados paginados y {@code generoSeleccionado}</li>
     * </ul>
     */
    @GetMapping("/genero/{genero}")
    public String buscarPorGenero(@PathVariable String genero,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "12") int size,
                                  Model model) {
        Page<Juego> juegos = juegosService.filtrar(genero, null, page, size);
        model.addAttribute("juegos", juegos);
        model.addAttribute("generoSeleccionado", genero);
        return "juegos";
    }

    /**
     * Muestra la ficha de detalle de un juego, con juegos relacionados y estado de wishlist.
     * <ul>
     *   <li><b>URL:</b> {@code GET /juegos/{id}}</li>
     *   <li><b>Acceso:</b> público; si hay sesión iniciada, se calcula si el juego está en la wishlist del usuario</li>
     *   <li><b>Retorno:</b> vista {@code juego-detalle} con {@code juego}, {@code juegosRelacionados},
     *       {@code historial}/{@code historialJson} (precios de 12 meses) y {@code enWishlist}; si el id no existe, vista {@code error} con código 404</li>
     * </ul>
     */
    @GetMapping("/{id}")
    public String verDetalle(@PathVariable String id, Model model,
                             Authentication authentication, HttpServletResponse response) {
        Juego juego;
        try {
            juego = juegosService.obtenerPorId(id);
        } catch (NoSuchElementException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("error", mensajes.get("det.noEncontrado"));
            model.addAttribute("status", HttpStatus.NOT_FOUND.value());
            return "error";
        }

        model.addAttribute("juego", juego);
        model.addAttribute("bannerUrl", steamGridDbService.obtenerBanner(juego));

        // Historial de precios de los últimos 12 meses para la gráfica (vacío si aún no hay cambios registrados)
        HistorialPrecioService.Historial historial = historialPrecioService.obtener(juego.getJuegoId());
        model.addAttribute("historial", historial);
        model.addAttribute("historialJson", historialPrecioService.comoJson(historial));

        if (juego.getGenero() != null && !juego.getGenero().isBlank()) {
            try {
                model.addAttribute("juegosRelacionados",
                        juegosService.obtenerPorGenero(juego.getGenero(), 5));
            } catch (Exception e) {
                model.addAttribute("juegosRelacionados", java.util.List.of());
            }
        } else {
            model.addAttribute("juegosRelacionados", java.util.List.of());
        }

        boolean enWishlist = false;
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            try {
                Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
                enWishlist = wishlistService.contiene(usuario.getId(), id);
            } catch (Exception ignored) {
                // Usuario autenticado pero no encontrado en BD: mostrar detalle sin wishlist
            }
        }
        model.addAttribute("enWishlist", enWishlist);

        return "juego-detalle";
    }
}
