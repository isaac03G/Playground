package com.proyecto.aula.playgroundv2.controller;

import com.proyecto.aula.playgroundv2.dto.ResultadoBusqueda;
import com.proyecto.aula.playgroundv2.model.Juego;
import com.proyecto.aula.playgroundv2.model.PrecioPlataforma;
import com.proyecto.aula.playgroundv2.service.JuegoService;
import com.proyecto.aula.playgroundv2.service.ScraperService;
import com.proyecto.aula.playgroundv2.service.UsuarioService;
import com.proyecto.aula.playgroundv2.service.WishlistService;
import com.proyecto.aula.playgroundv2.model.Usuario;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;

/**
 * Panel de administración web para gestionar el catálogo de juegos.
 * <p>
 * Ruta base: {@code /admin}. Solo usuarios con rol {@code ADMIN} pueden acceder
 * ({@code hasRole('ADMIN')} en la configuración de seguridad). Renderiza vistas Thymeleaf
 * y procesa formularios multipart para imágenes de juegos.
 * </p>
 *
 * @see JuegoService
 * @see UsuarioService
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final JuegoService juegoService;
    private final UsuarioService usuarioService;
    private final ScraperService scraperService;
    private final WishlistService wishlistService;

    @Value("${app.upload.dir:src/main/resources/static/uploads}")
    private String uploadDir;

    public AdminController(JuegoService juegoService, UsuarioService usuarioService,
                           ScraperService scraperService, WishlistService wishlistService) {
        this.juegoService = juegoService;
        this.usuarioService = usuarioService;
        this.scraperService = scraperService;
        this.wishlistService = wishlistService;
    }

    /**
     * Busca un juego por nombre en Steam, GOG y Epic Games y muestra en qué tiendas está.
     * <ul>
     *   <li><b>URL:</b> {@code POST /admin/buscar-juego}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN} (regla {@code /admin/**})</li>
     *   <li><b>accion=guardar:</b> además guarda el juego y sus precios; <b>accion=probar</b> (por defecto):
     *       solo muestra el resultado, sin escribir nada</li>
     * </ul>
     */
    @PostMapping("/buscar-juego")
    public String buscarJuego(@RequestParam("nombre") String nombre,
                              @RequestParam(value = "accion", defaultValue = "probar") String accion,
                              RedirectAttributes ra) {
        try {
            ResultadoBusqueda resultado = scraperService.buscar(nombre, "guardar".equals(accion));
            ra.addFlashAttribute("resultadoBusqueda", resultado);
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin";
    }

    /**
     * Panel principal con estadísticas y listado de juegos.
     * <ul>
     *   <li><b>URL:</b> {@code GET /admin?vista=usuarios|juegos&q=texto&page=0}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN}</li>
     *   <li><b>Retorno:</b> vista {@code admin-dashboard} con los totales de las tarjetas y la lista
     *       paginada (y filtrada por {@code q}) de usuarios o de juegos según {@code vista}</li>
     * </ul>
     */
    @GetMapping
    public String dashboard(@RequestParam(value = "vista", defaultValue = "juegos") String vista,
                            @RequestParam(value = "q", required = false) String q,
                            @RequestParam(value = "page", defaultValue = "0") int page,
                            Model model) {
        // La tarjeta elegida decide qué lista se muestra: "usuarios" o "juegos" (por defecto)
        boolean verUsuarios = "usuarios".equals(vista);
        String qLimpio = (q == null) ? "" : q.trim();
        String filtro = qLimpio.isEmpty() ? null : Pattern.quote(qLimpio);
        int pag = Math.max(page, 0);

        if (verUsuarios) {
            // Clientes (sin admins) paginados de 10 en 10, con su wishlist: solo se consulta la wishlist
            // de los usuarios de la página actual, no la de todos.
            Page<Usuario> pagina = usuarioService.buscarClientes(filtro, pag, 10);
            List<Map<String, Object>> usuariosRegistrados = new ArrayList<>();
            for (Usuario usuario : pagina.getContent()) {
                Map<String, Object> registro = new LinkedHashMap<>();
                registro.put("usuario", usuario);
                registro.put("juegosWishlist", wishlistService.obtenerJuegos(usuario.getId()));
                usuariosRegistrados.add(registro);
            }
            model.addAttribute("pagina", pagina);
            model.addAttribute("usuariosRegistrados", usuariosRegistrados);
        } else {
            // Juegos con buscador por nombre y paginación (15 por página)
            Page<Juego> pagina = juegoService.buscarAvanzado(filtro, null, null, null, null, "nombre", pag, 15);
            model.addAttribute("pagina", pagina);
            model.addAttribute("juegos", pagina.getContent());
        }

        model.addAttribute("vista", verUsuarios ? "usuarios" : "juegos");
        model.addAttribute("q", qLimpio);
        model.addAttribute("totalUsuarios", usuarioService.contarTotal());
        model.addAttribute("totalJuegos", juegoService.contarTotal());
        // admin-dashboard.html usa masVendidos; si falta, Thymeleaf lanza NPE al evaluar .isEmpty()
        model.addAttribute("masVendidos", List.of());
        return "admin-dashboard";
    }

    /**
     * Formulario vacío para crear un juego nuevo.
     * <ul>
     *   <li><b>URL:</b> {@code GET /admin/juego/nuevo}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN}</li>
     *   <li><b>Retorno:</b> vista {@code admin-juego-formulario} con entidad {@code Juego} vacía</li>
     * </ul>
     */
    @GetMapping("/juego/nuevo")
    public String formularioNuevo(Model model) {
        model.addAttribute("juego", new Juego());
        return "admin-juego-formulario";
    }

    /**
     * Formulario de edición cargado con los datos de un juego existente.
     * <ul>
     *   <li><b>URL:</b> {@code GET /admin/juego/{id}/editar}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN}</li>
     *   <li><b>Retorno:</b> vista {@code admin-juego-formulario} con el {@code Juego} solicitado</li>
     * </ul>
     */
    @GetMapping("/juego/{id}/editar")
    public String formularioEditar(@PathVariable String id, Model model) {
        model.addAttribute("juego", juegoService.obtenerPorId(id));
        return "admin-juego-formulario";
    }

    /**
     * Crea o actualiza un juego desde el formulario de administración.
     * <ul>
     *   <li><b>URL:</b> {@code POST /admin/juego/guardar}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN}</li>
     *   <li><b>Cuerpo:</b> parámetros de formulario (nombre, descripción, género, imagen URL/archivo,
     *       precios por plataforma, etc.)</li>
     *   <li><b>Retorno:</b> redirección a {@code /admin} con mensaje flash de éxito o error de subida de imagen</li>
     * </ul>
     */
    @PostMapping("/juego/guardar")
    public String guardar(
            @RequestParam(value = "juegoId",       required = false) String juegoId,
            @RequestParam(value = "nombre",         required = false) String nombre,
            @RequestParam(value = "descripcion",    required = false) String descripcion,
            @RequestParam(value = "desarrolladora", required = false) String desarrolladora,
            @RequestParam(value = "genero",         required = false) String genero,
            @RequestParam(value = "imagenUrl",      required = false) String imagenUrlTexto,
            @RequestParam(value = "fechaDeLanzamiento", required = false) String fechaStr,
            @RequestParam(value = "imagenArchivo",  required = false) MultipartFile imagenArchivo,
            @RequestParam(value = "plataformaNombre", required = false) List<String> nombresPlat,
            @RequestParam(value = "plataformaPrecio", required = false) List<String> preciosPlat,
            RedirectAttributes ra) {

        // --- Imagen: archivo subido tiene prioridad sobre URL ---
        String imagenUrl = emptyToNull(imagenUrlTexto);
        if (imagenArchivo != null && !imagenArchivo.isEmpty()) {
            try {
                imagenUrl = guardarImagen(imagenArchivo);
            } catch (IOException e) {
                ra.addFlashAttribute("error", "Error al subir la imagen: " + e.getMessage());
            }
        }

        // --- Fecha ---
        LocalDate fecha = null;
        if (fechaStr != null && !fechaStr.isBlank()) {
            try { fecha = LocalDate.parse(fechaStr); } catch (Exception ignored) {}
        }

                // --- Precios que ya tenía el juego (para no perder los datos que puso el scraper) ---
        Map<String, PrecioPlataforma> preciosPrevios = new HashMap<>();
        if (juegoId != null && !juegoId.isBlank()) {
            try {
                List<PrecioPlataforma> previos = juegoService.obtenerPorId(juegoId).getPrecios();
                if (previos != null) {
                    for (PrecioPlataforma pp : previos) {
                        if (pp.getPlataforma() != null) preciosPrevios.put(pp.getPlataforma(), pp);
                    }
                }
            } catch (RuntimeException ignored) {
                // juego nuevo o id inexistente: no hay nada que conservar
            }
        }

        // --- Lista de precios por plataforma (el formulario trabaja siempre en COP) ---
        List<PrecioPlataforma> listaPrecios = new ArrayList<>();
        if (nombresPlat != null && preciosPlat != null) {
            for (int i = 0; i < nombresPlat.size(); i++) {
                String n = nombresPlat.get(i);
                if (n != null && !n.isBlank() && i < preciosPlat.size()) {
                    try {
                        double p = Double.parseDouble(preciosPlat.get(i));
                        PrecioPlataforma nuevo = new PrecioPlataforma(n.trim(), p);
                        nuevo.setMoneda("COP");
                        nuevo.setEsGratis(p == 0);
                        PrecioPlataforma previo = preciosPrevios.get(n.trim());
                        // Si el administrador no tocó el valor, se conserva el precio original de la tienda
                        // (así tasas_cambio.py puede seguir recalculándolo con la tasa del día).
                        if (previo != null && previo.getPrecio() != null && previo.getPrecio() == p) {
                            nuevo.setPrecioOriginal(previo.getPrecioOriginal());
                            nuevo.setMonedaOriginal(previo.getMonedaOriginal());
                            // Lo mismo con el seguimiento de cambios que escribe actualizador.py
                            nuevo.setPrecioAnterior(previo.getPrecioAnterior());
                            nuevo.setDescuento(previo.getDescuento());
                            nuevo.setFechaCambio(previo.getFechaCambio());
                            nuevo.setUltimaRevision(previo.getUltimaRevision());
                        }
                        listaPrecios.add(nuevo);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        // --- Construir entidad ---
        Juego juego = new Juego();
        juego.setJuegoId(emptyToNull(juegoId));
        juego.setNombre(nombre);
        juego.setDescripcion(descripcion);
        juego.setDesarrolladora(desarrolladora);
        juego.setGenero(genero);
        juego.setFechaDeLanzamiento(fecha);
        juego.setImagenUrl(imagenUrl);
        juego.setPrecios(listaPrecios.isEmpty() ? null : listaPrecios);

        juegoService.crear(juego);
        ra.addFlashAttribute("mensaje", "Juego guardado correctamente.");
        return "redirect:/admin";
    }

    /**
     * Elimina un juego del catálogo.
     * <ul>
     *   <li><b>URL:</b> {@code POST /admin/juego/{id}/eliminar}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN}</li>
     *   <li><b>Retorno:</b> redirección a {@code /admin} con mensaje flash</li>
     * </ul>
     */
    @PostMapping("/juego/{id}/eliminar")
    public String eliminar(@PathVariable String id, RedirectAttributes ra) {
        juegoService.eliminar(id);
        ra.addFlashAttribute("mensaje", "Juego eliminado.");
        return "redirect:/admin";
    }

    /**
     * Borra la imagen local de un juego y limpia su URL en base de datos.
     * <ul>
     *   <li><b>URL:</b> {@code POST /admin/juego/{id}/eliminar-imagen}</li>
     *   <li><b>Acceso:</b> rol {@code ADMIN}</li>
     *   <li><b>Retorno:</b> redirección a {@code /admin/juego/{id}/editar} con mensaje flash de éxito o error</li>
     * </ul>
     */
    @PostMapping("/juego/{id}/eliminar-imagen")
    public String eliminarImagen(@PathVariable String id, RedirectAttributes ra) {
        try {
            Juego juego = juegoService.obtenerPorId(id);
            String imagenUrl = juego.getImagenUrl();

            // Solo borrar del disco si es una imagen subida (empieza con /uploads/)
            if (imagenUrl != null && imagenUrl.startsWith("/uploads/")) {
                String nombreArchivo = imagenUrl.substring("/uploads/".length());
                Path ruta = Paths.get(uploadDir).resolve(nombreArchivo);
                Files.deleteIfExists(ruta);
            }

            // Limpiar la URL en la base de datos
            juego.setImagenUrl(null);
            juegoService.crear(juego);

            ra.addFlashAttribute("mensaje", "Imagen eliminada correctamente.");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "Error al eliminar la imagen: " + e.getMessage());
        }
        return "redirect:/admin/juego/" + id + "/editar";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Guarda la imagen en disco y devuelve la URL pública relativa.
     * El archivo se almacena en static/uploads/ y se sirve como /uploads/{nombre}.
     */
    private String guardarImagen(MultipartFile archivo) throws IOException {
        // Nombre único: timestamp + nombre original saneado
        String nombreOriginal = Objects.requireNonNullElse(archivo.getOriginalFilename(), "imagen");
        String extension = "";
        int punto = nombreOriginal.lastIndexOf('.');
        if (punto >= 0) extension = nombreOriginal.substring(punto).toLowerCase();

        String nombreFinal = System.currentTimeMillis() + "_" +
                nombreOriginal.replaceAll("[^a-zA-Z0-9._-]", "_");

        Path destino = Paths.get(uploadDir).resolve(nombreFinal);
        Files.createDirectories(destino.getParent());
        Files.copy(archivo.getInputStream(), destino, StandardCopyOption.REPLACE_EXISTING);

        return "/uploads/" + nombreFinal;
    }

    /** Convierte cadenas vacías o en blanco en {@code null} para persistencia opcional. */
    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
