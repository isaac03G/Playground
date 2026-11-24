package com.proyecto.aula.playgroundv2.controller;

import com.proyecto.aula.playgroundv2.model.*;
import com.proyecto.aula.playgroundv2.service.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.ResponseEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador MVC de la lista de deseos (wishlist) del usuario.
 * <p>
 * Ruta base: {@code /wishlist}. Todas las operaciones requieren usuario autenticado
 * ({@code authenticated} en {@link com.proyecto.aula.playgroundv2.config.SecurityConfig}).
 * Las acciones de agregar/eliminar redirigen de vuelta al detalle del juego o a la wishlist.
 * </p>
 *
 * @see WishlistService
 */
@Controller
@RequestMapping("/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;
    private final UsuarioService usuarioService;
    private final Mensajes mensajes;
    private final TasaCambioService monedas;

    public WishlistController(WishlistService wishlistService, UsuarioService usuarioService,
                              Mensajes mensajes, TasaCambioService monedas) {
        this.wishlistService = wishlistService;
        this.usuarioService = usuarioService;
        this.mensajes = mensajes;
        this.monedas = monedas;
    }

    /**
     * Muestra todos los juegos guardados en la wishlist del usuario en sesión.
     * <ul>
     *   <li><b>URL:</b> {@code GET /wishlist}</li>
     *   <li><b>Acceso:</b> usuario autenticado</li>
     *   <li><b>Retorno:</b> vista {@code wishlist} con {@code List<Juego>} en el atributo {@code juegos}</li>
     * </ul>
     */
    @GetMapping
    public String verWishlist(Authentication authentication,
                              @CookieValue(name = "moneda", required = false) String moneda,
                              Model model) {
        Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
        List<Juego> juegos = wishlistService.obtenerJuegos(usuario.getId());
        model.addAttribute("juegos", juegos);
        model.addAllAttributes(resumenTotales(juegos, moneda));
        return "wishlist";
    }

    /**
     * Añade un juego a la wishlist del usuario autenticado.
     * <ul>
     *   <li><b>URL:</b> {@code POST /wishlist/agregar/{juegoId}}</li>
     *   <li><b>Acceso:</b> usuario autenticado</li>
     *   <li><b>Retorno:</b> redirección a {@code /juegos/{juegoId}} con mensaje flash de confirmación</li>
     * </ul>
     */
    @PostMapping("/agregar/{juegoId}")
    public String agregar(@PathVariable String juegoId, Authentication authentication,
                          RedirectAttributes ra) {
        Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
        wishlistService.agregar(usuario.getId(), juegoId);
        ra.addFlashAttribute("mensaje", mensajes.get("wishlist.agregado"));
        return "redirect:/juegos/" + juegoId;
    }

    /**
     * Elimina un juego de la wishlist del usuario autenticado.
     * <ul>
     *   <li><b>URL:</b> {@code POST /wishlist/eliminar/{juegoId}}</li>
     *   <li><b>Acceso:</b> usuario autenticado</li>
     *   <li><b>Retorno:</b> redirección a {@code /wishlist} con mensaje flash</li>
     * </ul>
     */
    @PostMapping("/eliminar/{juegoId}")
    public String eliminar(@PathVariable String juegoId, Authentication authentication,
                           RedirectAttributes ra) {
        Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
        wishlistService.eliminar(usuario.getId(), juegoId);
        ra.addFlashAttribute("mensaje", mensajes.get("wishlist.eliminado"));
        return "redirect:/wishlist";
    }

    @PostMapping("/eliminar/{juegoId}/ajax")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> eliminarAjax(
            @PathVariable String juegoId,
            Authentication authentication,
            @CookieValue(name = "moneda", required = false) String moneda) {
        Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
        wishlistService.eliminar(usuario.getId(), juegoId);

        List<Juego> juegosRestantes = wishlistService.obtenerJuegos(usuario.getId());
        Map<String, Object> resumen = resumenTotales(juegosRestantes, moneda);
        resumen.put("count", Integer.toString(juegosRestantes.size()));
        return ResponseEntity.ok(resumen);
    }

    @GetMapping("/totales")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> obtenerTotales(
            Authentication authentication,
            @CookieValue(name = "moneda", required = false) String moneda) {
        Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
        return ResponseEntity.ok(resumenTotales(wishlistService.obtenerJuegos(usuario.getId()), moneda));
    }

    private Map<String, Object> resumenTotales(List<Juego> juegos, String moneda) {
        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("totalSteam", calcularTotal(juegos, "Steam", moneda));
        resumen.put("juegosSteam", nombresPorPlataforma(juegos, "Steam"));
        resumen.put("totalGog", calcularTotal(juegos, "GOG", moneda));
        resumen.put("juegosGog", nombresPorPlataforma(juegos, "GOG"));
        resumen.put("totalEpic", calcularTotal(juegos, "Epic Games", moneda));
        resumen.put("juegosEpic", nombresPorPlataforma(juegos, "Epic Games"));
        return resumen;
    }

    /**
     * Nombres de los juegos que realmente tienen una entrada de precio para la plataforma.
     * La presencia de la entrada indica disponibilidad; no se asume que todos los juegos
     * de la wishlist estén en las tres tiendas.
     */
    private List<String> nombresPorPlataforma(List<Juego> juegos, String plataforma) {
        return juegos.stream()
                .filter(juego -> juego.getPrecios() != null)
                .filter(juego -> juego.getPrecios().stream()
                        .anyMatch(precio -> precio != null
                                && precio.getPlataforma() != null
                                && plataforma.equalsIgnoreCase(precio.getPlataforma().trim())))
                .map(Juego::getNombre)
                .filter(nombre -> nombre != null && !nombre.isBlank())
                .toList();
    }

    private String calcularTotal(List<Juego> juegos, String plataforma, String moneda) {
        double totalCop = juegos.stream()
                .filter(juego -> juego.getPrecios() != null)
                .flatMap(juego -> juego.getPrecios().stream())
                .filter(precio -> precio != null && precio.getPlataforma() != null
                        && plataforma.equalsIgnoreCase(precio.getPlataforma().trim()))
                .map(PrecioPlataforma::getPrecio)
                .filter(precio -> precio != null)
                .mapToDouble(Double::doubleValue)
                .sum();
        return monedas.formatear(totalCop, moneda);
    }
}
