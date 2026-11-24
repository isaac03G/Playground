/**
 * Acceso a los textos traducidos ({@code messages*.properties}) desde Java y desde las plantillas.
 * <p>
 * Desde un controlador: {@code mensajes.get("wishlist.agregado")}. Desde Thymeleaf se expone como
 * {@code @mensajes}: {@code th:text="${@mensajes.genero(juego.genero)}"}.
 * El idioma sale de la petición actual (cookie {@code idioma}).
 * </p>
 */
package com.proyecto.aula.playgroundv2.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component("mensajes")
public class Mensajes {

    /** Mensajes en español que lanzan los servicios (y que también usa la API REST) -> clave del texto traducido. */
    private static final Map<String, String> ERRORES_SERVICIO = Map.of(
            "El nombre de usuario ya está en uso", "error.usuarioEnUso",
            "Ese correo ya está registrado", "error.correoRegistrado",
            "El email ya está en uso por otro usuario", "error.emailEnUso",
            "La contraseña actual es incorrecta", "error.contrasenaIncorrecta",
            "Las nuevas contraseñas no coinciden", "error.contrasenasNoCoinciden");

    private final MessageSource fuente;

    public Mensajes(MessageSource fuente) {
        this.fuente = fuente;
    }

    /** Texto de la clave en el idioma actual; si la clave no existe devuelve la propia clave. */
    public String get(String clave, Object... args) {
        Object[] argumentos = (args == null || args.length == 0) ? null : args;
        return fuente.getMessage(clave, argumentos, clave, LocaleContextHolder.getLocale());
    }

    /**
     * Traduce el nombre de un género guardado en español ("Acción" -> "Action").
     * Si no hay traducción para ese género, devuelve el nombre tal cual.
     */
    public String genero(String generoEs) {
        if (generoEs == null || generoEs.isBlank()) return generoEs;
        String base = Normalizer.normalize(generoEs.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return fuente.getMessage("genero." + base, null, generoEs, LocaleContextHolder.getLocale());
    }

    /** Traduce los mensajes de error conocidos de los servicios; los demás se devuelven sin cambios. */
    public String errorServicio(String mensajeEs) {
        String clave = mensajeEs == null ? null : ERRORES_SERVICIO.get(mensajeEs);
        return clave == null ? mensajeEs : get(clave);
    }
}
