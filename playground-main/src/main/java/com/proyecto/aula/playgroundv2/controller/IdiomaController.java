package com.proyecto.aula.playgroundv2.controller;

import java.io.IOException;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.LocaleResolver;

import com.proyecto.aula.playgroundv2.config.IdiomaLocaleResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cambia el idioma de la página (español / inglés).
 * <p>
 * {@code GET /idioma?codigo=en&volver=/buscar?nombre=witcher} guarda la elección en la cookie
 * {@code idioma} (1 año) y redirige a la página desde la que se pidió. Es público: no requiere login,
 * así que también funciona para visitantes. Funciona igual que {@code /moneda}.
 * </p>
 */
@Controller
@RequestMapping("/idioma")
public class IdiomaController {

    private final LocaleResolver localeResolver;

    public IdiomaController(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @GetMapping
    public void cambiar(@RequestParam("codigo") String codigo,
                        @RequestParam(value = "volver", defaultValue = "/") String volver,
                        HttpServletRequest peticion,
                        HttpServletResponse respuesta) throws IOException {
        String valido = IdiomaLocaleResolver.normalizar(codigo);
        if (valido != null) {
            localeResolver.setLocale(peticion, respuesta, Locale.forLanguageTag(valido));
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
