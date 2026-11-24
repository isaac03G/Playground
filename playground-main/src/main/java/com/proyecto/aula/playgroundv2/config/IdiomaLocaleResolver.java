/**
 * Decide en qué idioma se ve la página, según la cookie {@code idioma} (igual que la cookie {@code moneda}).
 * <ul>
 *   <li>Idiomas disponibles: {@code es} y {@code en}</li>
 *   <li>Sin cookie (o con un valor raro) se usa español, como estaba la página antes</li>
 *   <li>Cualquier controlador o plantilla recibe ese idioma a través de {@code Locale} / {@code #locale}</li>
 * </ul>
 */
package com.proyecto.aula.playgroundv2.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.servlet.LocaleResolver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class IdiomaLocaleResolver implements LocaleResolver {

    public static final String COOKIE = "idioma";
    public static final List<String> SOPORTADOS = List.of("es", "en");
    public static final String POR_DEFECTO = "es";

    /** Devuelve el código ("es"/"en") si está soportado; si no, {@code null}. Acepta {@code null}. */
    public static String normalizar(String codigo) {
        if (codigo == null) return null;
        String c = codigo.trim().toLowerCase(Locale.ROOT);
        return SOPORTADOS.contains(c) ? c : null;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName())) {
                    String valido = normalizar(c.getValue());
                    if (valido != null) return Locale.forLanguageTag(valido);
                }
            }
        }
        return Locale.forLanguageTag(POR_DEFECTO);
    }

    /** Guarda la elección en la cookie {@code idioma} (1 año). */
    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        if (response == null) return;
        String codigo = locale == null ? null : normalizar(locale.getLanguage());
        if (codigo == null) codigo = POR_DEFECTO;
        ResponseCookie cookie = ResponseCookie.from(COOKIE, codigo)
                .path("/")
                .maxAge(Duration.ofDays(365))
                .httpOnly(true)
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
