package com.proyecto.aula.playgroundv2.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.proyecto.aula.playgroundv2.dto.CambioContrasenaForm;
import com.proyecto.aula.playgroundv2.dto.PerfilUsuarioForm;
import com.proyecto.aula.playgroundv2.model.Usuario;
import com.proyecto.aula.playgroundv2.service.Mensajes;
import com.proyecto.aula.playgroundv2.service.UsuarioService;

import jakarta.validation.Valid;

/**
 * Controlador MVC de gestión del perfil del usuario autenticado.
 */
@Controller
@RequestMapping("/perfil")
public class PerfilController {

    private static final Logger logger = LoggerFactory.getLogger(PerfilController.class);
    private final UsuarioService usuarioService;
    private final Mensajes mensajes;
    
    /** Misma carpeta que usa el admin para las portadas; se sirve como /uploads/** (ver WebConfig). */
    @Value("${app.upload.dir:src/main/resources/static/uploads}")
    private String uploadDir;

    private static final long FOTO_MAX_BYTES = 3L * 1024 * 1024;   // 3 MB
    private static final long FONDO_MAX_BYTES = 5L * 1024 * 1024;  // 5 MB
    private static final Map<String, String> TIPOS_FOTO = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp", "image/gif", ".gif");

    public PerfilController(UsuarioService usuarioService, Mensajes mensajes) {
        this.usuarioService = usuarioService;
        this.mensajes = mensajes;
    }

    @GetMapping
    public String mostrarPerfil(Model model, Authentication authentication) {
        cargarModeloPerfil(model, authentication.getName());
        return "perfil";
    }

    @PostMapping
    public String actualizarPerfil(
            @Valid @ModelAttribute("perfilForm") PerfilUsuarioForm form,
            BindingResult result,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (result.hasErrors()) {
            cargarModeloPerfil(model, authentication.getName());
            return "perfil";
        }

        try {
            usuarioService.actualizarPerfil(authentication.getName(), form);
        } catch (IllegalArgumentException ex) {
            result.rejectValue("email", "perfil.error", mensajes.errorServicio(ex.getMessage()));
            cargarModeloPerfil(model, authentication.getName());
            return "perfil";
        }

        redirectAttributes.addFlashAttribute("mensajeExito", mensajes.get("perfil.actualizado"));
        return "redirect:/perfil";
    }

    @PostMapping("/cambiar-contrasena")
    public String cambiarContrasena(
            @Valid @ModelAttribute("contrasenaForm") CambioContrasenaForm form,
            BindingResult result,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (!result.hasFieldErrors("contraseniaNueva") && !result.hasFieldErrors("confirmarContrasenia")
                && !form.getContraseniaNueva().equals(form.getConfirmarContrasenia())) {
            result.rejectValue("confirmarContrasenia", "password.mismatch", "Las contraseñas no coinciden");
        }

        if (result.hasErrors()) {
            cargarModeloPerfil(model, authentication.getName());
            model.addAttribute("mostrarContrasena", true);
            return "perfil";
        }

        try {
            usuarioService.cambiarContrasena(authentication.getName(), form);
        } catch (IllegalArgumentException ex) {
            result.rejectValue("contraseniaActual", "contrasena.error", mensajes.errorServicio(ex.getMessage()));
            cargarModeloPerfil(model, authentication.getName());
            model.addAttribute("mostrarContrasena", true);
            return "perfil";
        }

        redirectAttributes.addFlashAttribute("mensajeExito", mensajes.get("perfil.contrasenaActualizada"));
        return "redirect:/perfil";
    }
    
    /** Cambia la foto de perfil con una imagen elegida desde el PC (POST /perfil/foto). */
    @PostMapping("/foto")
    public String cambiarFoto(@RequestParam("foto") MultipartFile foto,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        String extension = foto.isEmpty() ? null : TIPOS_FOTO.get(foto.getContentType());
        if (extension == null) {
            redirectAttributes.addFlashAttribute("mensajeError", mensajes.get("perfil.fotoFormato"));
            return "redirect:/perfil";
        }
        if (foto.getSize() > FOTO_MAX_BYTES) {
            redirectAttributes.addFlashAttribute("mensajeError", mensajes.get("perfil.fotoMax"));
            return "redirect:/perfil";
        }

        try {
            Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
            String fotoAnterior = usuario.getFotoUrl();
            String fotoUrl = guardarImagen(foto, usuario, "perfiles", extension);
            usuario.setFotoUrl(fotoUrl);
            usuarioService.guardarUsuario(usuario);
            borrarImagenAnterior(fotoAnterior, "perfiles");
            redirectAttributes.addFlashAttribute("mensajeExito", mensajes.get("perfil.fotoActualizada"));
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("mensajeError", mensajes.get("perfil.fotoError"));
        }
        return "redirect:/perfil";
    }
    
    @GetMapping("/fondo")
    public String volverAlPerfilDesdeFondo() {
        return "redirect:/perfil";
    }

    /** Guarda el fondo elegido por el usuario (POST /perfil/fondo). */
    @PostMapping("/fondo")
    public String cambiarFondo(@RequestParam("fondo") MultipartFile fondo,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        String extension = fondo.isEmpty() ? null : TIPOS_FOTO.get(fondo.getContentType());
        if (extension == null) {
            redirectAttributes.addFlashAttribute("mensajeError", mensajes.get("perfil.fondoFormato"));
            return "redirect:/perfil";
        }
        if (fondo.getSize() > FONDO_MAX_BYTES) {
            redirectAttributes.addFlashAttribute("mensajeError", mensajes.get("perfil.fondoMax"));
            return "redirect:/perfil";
        }

        try {
            Usuario usuario = usuarioService.obtenerPorUsername(authentication.getName());
            String fondoAnterior = usuario.getFondoUrl();
            String fondoUrl = guardarImagen(fondo, usuario, "perfiles/fondos", extension);
            usuario.setFondoUrl(fondoUrl);
            usuarioService.guardarUsuario(usuario);
            borrarImagenAnterior(fondoAnterior, "perfiles/fondos");
            redirectAttributes.addFlashAttribute("mensajeExito", mensajes.get("perfil.fondoActualizado"));
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("mensajeError", mensajes.get("perfil.fondoError"));
        }
        return "redirect:/perfil";
    }

    private String guardarImagen(MultipartFile imagen, Usuario usuario, String directorio, String extension)
            throws IOException {
        String nombre = directorio + "/" + usuario.getId() + "-" + System.currentTimeMillis() + extension;
        Path destino = Paths.get(uploadDir).resolve(nombre);
        Files.createDirectories(destino.getParent());
        try (InputStream in = imagen.getInputStream()) {
            Files.copy(in, destino, StandardCopyOption.REPLACE_EXISTING);
        }
        return "/uploads/" + nombre;
    }

    /** Solo elimina imágenes anteriores almacenadas por la aplicación dentro del directorio esperado. */
    private void borrarImagenAnterior(String imagenUrl, String directorio) {
        String prefijo = "/uploads/" + directorio + "/";
        if (imagenUrl == null || !imagenUrl.startsWith(prefijo)) return;
        Path raizUploads = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path imagen = raizUploads.resolve(imagenUrl.substring("/uploads/".length())).normalize();
        if (!imagen.startsWith(raizUploads.resolve(directorio).normalize())) return;
        try {
            Files.deleteIfExists(imagen);
        } catch (IOException e) {
            logger.warn("No se pudo borrar una imagen anterior del perfil", e);
        }
    }

    private void cargarModeloPerfil(Model model, String username) {
        Usuario usuario = usuarioService.obtenerPorUsername(username);

        if (!model.containsAttribute("perfilForm")) {
            model.addAttribute("perfilForm", usuarioService.toPerfilForm(usuario));
        }
        if (!model.containsAttribute("contrasenaForm")) {
            model.addAttribute("contrasenaForm", new CambioContrasenaForm());
        }

        model.addAttribute("usuario", usuario);
        model.addAttribute("perfilFondo", usuario.getFondoUrl());
    }
}
