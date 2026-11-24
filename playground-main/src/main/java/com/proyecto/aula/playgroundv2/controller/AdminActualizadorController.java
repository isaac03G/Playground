package com.proyecto.aula.playgroundv2.controller;

import com.proyecto.aula.playgroundv2.service.ActualizadorService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Botones del panel admin para ejecutar actualizador.py y ver su registro.
 * Esta bajo /admin/**, que SecurityConfig ya limita al rol ADMIN.
 */
@Controller
@RequestMapping("/admin/actualizador")
public class AdminActualizadorController {

    private final ActualizadorService actualizador;

    public AdminActualizadorController(ActualizadorService actualizador) {
        this.actualizador = actualizador;
    }

    @PostMapping("/iniciar")
    public String iniciar(@RequestParam String modo, RedirectAttributes ra) {
        try {
            actualizador.iniciar(modo);
            ra.addFlashAttribute("mensaje", "Actualizacion iniciada. El registro aparece abajo.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin#actualizador";
    }

    @PostMapping("/detener")
    public String detener(RedirectAttributes ra) {
        if (actualizador.detener()) {
            ra.addFlashAttribute("mensaje", "Se pidio detener la actualizacion.");
        } else {
            ra.addFlashAttribute("error", "No hay ninguna actualizacion en curso.");
        }
        return "redirect:/admin#actualizador";
    }

    /** JSON que consulta la pagina cada pocos segundos: estado + lineas nuevas del registro. */
    @GetMapping("/estado")
    @ResponseBody
    public Map<String, Object> estado(@RequestParam(defaultValue = "0") long desde) {
        return actualizador.estado(desde);
    }
}
