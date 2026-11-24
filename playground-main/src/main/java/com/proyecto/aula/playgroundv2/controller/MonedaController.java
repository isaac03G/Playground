package com.proyecto.aula.playgroundv2.controller;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.proyecto.aula.playgroundv2.service.TasaCambioService;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Cambia la moneda en la que el usuario ve los precios.
 * <p>
 * {@code GET /moneda?codigo=USD&volver=/buscar?nombre=witcher} guarda la elección en la cookie
 * {@code moneda} (1 año) y redirige a la página desde la que se pidió. Es público: no requiere login,
 * así que también funciona para visitantes. Los precios del catálogo no cambian: solo cómo se muestran.
 * </p>
 */
@Controller
@RequestMapping("/moneda")
public class MonedaController {

    private final TasaCambioService monedas;

    public MonedaController(TasaCambioService monedas) {
        this.monedas = monedas;
    }

    @GetMapping
    public void cambiar(@RequestParam("codigo") String codigo,
                        @RequestParam(value = "volver", defaultValue = "/") String volver,
                        HttpServletResponse respuesta) throws IOException {
        if (monedas.esValida(codigo)) {
            ResponseCookie cookie = ResponseCookie.from("moneda", monedas.normalizar(codigo))
                    .path("/")
                    .maxAge(Duration.ofDays(365))
                    .httpOnly(true)
                    .sameSite("Lax")
                    .build();
            respuesta.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        respuesta.sendRedirect(destinoSeguro(volver));
    }

    /** Solo rutas internas ("/algo"): evita que alguien use este enlace para redirigir a otro sitio. */
    private static String destinoSeguro(String volver) {
        if (volver == null || !volver.startsWith("/") || volver.startsWith("//")
                || volver.contains("\\") || volver.chars().anyMatch(Character::isISOControl)) {
            return "/";
        }
        return volver;
    }
}