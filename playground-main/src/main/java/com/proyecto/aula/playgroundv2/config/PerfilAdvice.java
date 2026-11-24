package com.proyecto.aula.playgroundv2.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.proyecto.aula.playgroundv2.model.Usuario;
import com.proyecto.aula.playgroundv2.repository.UsuarioRepository;

/**
 * Pone en el modelo de TODAS las vistas la inicial y la foto del usuario con sesión iniciada,
 * para el botón de perfil de la barra de navegación.
 */
@ControllerAdvice
public class PerfilAdvice {

    private final UsuarioRepository usuarios;

    public PerfilAdvice(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @ModelAttribute
    public void avatar(Authentication authentication, Model model) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        String nombre = authentication.getName();
        if (nombre == null || nombre.isBlank()) return;

        model.addAttribute("avatarInicial", nombre.substring(0, 1).toUpperCase());
        usuarios.findByUsername(nombre)
                .or(() -> usuarios.findByEmail(nombre))
                .map(Usuario::getFotoUrl)
                .filter(f -> !f.isBlank())
                .ifPresent(f -> model.addAttribute("avatarFoto", f));
    }
}