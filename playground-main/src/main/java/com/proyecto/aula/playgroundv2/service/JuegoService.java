/**
 * Capa de servicio para la gestión de juegos en el catálogo.
 * <p>
 * Centraliza las operaciones de consulta (con filtros, búsqueda avanzada y paginación),
 * creación, lectura y eliminación de entidades {@link com.proyecto.aula.playgroundv2.model.Juego}.
 * Combina el repositorio Spring Data con {@link org.springframework.data.mongodb.core.MongoTemplate}
 * cuando hace falta consultas dinámicas por criterios múltiples.
 * </p>
 *
 * @author proyecto-aula
 * @see com.proyecto.aula.playgroundv2.repository.JuegoRepository
 */
package com.proyecto.aula.playgroundv2.service;

import java.util.NoSuchElementException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import com.proyecto.aula.playgroundv2.model.Juego;
import com.proyecto.aula.playgroundv2.repository.JuegoRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.proyecto.aula.playgroundv2.model.PrecioPlataforma;

/**
 * Servicio de dominio para juegos: filtrado, búsqueda, CRUD y agregaciones simples.
 * <p>
 * Los métodos de listado devuelven {@link Page} o listas acotadas según el caso de uso
 * (API REST, vistas Thymeleaf, wishlist, etc.).
 * </p>
 */
@Service
public class JuegoService {

    /** Repositorio Spring Data para operaciones CRUD y consultas derivadas por nombre de método. */
    private final JuegoRepository juegosRepository;

    /** Plantilla MongoDB para consultas dinámicas (regex, criterios anidados en precios). */
    private final MongoTemplate mongoTemplate;

    /**
     * Inyección de dependencias por constructor (recomendado en Spring).
     *
     * @param juegosRepository acceso a la colección de juegos
     * @param mongoTemplate      ejecutor de consultas Mongo personalizadas
     */
    public JuegoService(JuegoRepository juegosRepository, MongoTemplate mongoTemplate) {
        this.juegosRepository = juegosRepository;
        this.mongoTemplate = mongoTemplate;
    }

    // --- Consultas con filtros simples ---

    /**
     * Filtra juegos por género y/o desarrolladora con paginación.
     * <p>
     * Cómo funciona: evalúa qué parámetros vienen informados y delega en el método
     * del repositorio más específico; si ninguno viene, devuelve todos los juegos paginados.
     * </p>
     *
     * @param genero         género a filtrar (opcional, insensible a mayúsculas en repo)
     * @param desarrolladora desarrolladora a filtrar (opcional)
     * @param page           índice de página (base 0)
     * @param size           tamaño de página
     * @return página de juegos que cumplen los criterios
     */
    public Page<Juego> filtrar(String genero, String desarrolladora, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        boolean hasGenero = genero != null && !genero.isBlank();
        boolean hasDev = desarrolladora != null && !desarrolladora.isBlank();

        if (hasGenero && hasDev)
            return juegosRepository.findByGeneroAndDesarrolladora(genero, desarrolladora, pageable);
        if (hasGenero)
            return juegosRepository.findByGeneroIgnoreCase(genero, pageable);
        if (hasDev)
            return juegosRepository.findByDesarrolladoraIgnoreCase(desarrolladora, pageable);
        return juegosRepository.findAll(pageable);
    }

    // --- Búsqueda avanzada con MongoTemplate ---

    /**
     * Búsqueda avanzada por nombre, género y plataforma de venta (dentro del array de precios).
     * <p>
     * Cómo funciona: construye un {@link Criteria} dinámico; el nombre y género usan regex
     * case-insensitive; la plataforma coincide con el campo anidado {@code precios.plataforma}.
     * Primero cuenta el total con {@code count}, luego aplica skip/limit vía {@link PageRequest}.
     * </p>
     *
     * @param nombre           fragmento del nombre (opcional)
     * @param genero           fragmento del género (opcional)
     * @param plataformaVenta  nombre exacto de plataforma en precios (opcional)
     * @param page             índice de página
     * @param size             tamaño de página
     * @return página construida manualmente con {@link PageImpl}
     */
    public Page<Juego> buscarAvanzado(String nombre, String genero, String plataformaVenta,
                                       int page, int size) {
        return buscarAvanzado(nombre, genero, plataformaVenta, null, null, null, page, size);
    }

    /**
     * Igual que la búsqueda avanzada, pero además filtra y ordena por precio.
     * <ul>
     *   <li><b>precioMinCop / precioMaxCop:</b> rango en COP (la moneda base de la base de datos). Un juego entra si
     *       ALGUNA de sus tiendas tiene un precio dentro del rango; si además se eligió una tienda, tiene que ser el
     *       precio de ESA tienda (se compara dentro del mismo elemento de {@code precios}).</li>
     *   <li><b>orden:</b> {@code precio_asc}, {@code precio_desc}, {@code nombre} o {@code null} (sin orden). Por
     *       precio, Mongo ordena por el precio más bajo de cada juego (asc) o el más alto (desc), y deja fuera los
     *       juegos que no tienen ningún precio.</li>
     * </ul>
     */
    public Page<Juego> buscarAvanzado(String nombre, String genero, String plataformaVenta,
                                       Double precioMinCop, Double precioMaxCop, String orden,
                                       int page, int size) {
        Criteria criteria = new Criteria();
        boolean hayPrecio = precioMinCop != null || precioMaxCop != null;
        boolean hayPlataforma = plataformaVenta != null && !plataformaVenta.isBlank();

        if (nombre != null && !nombre.isBlank())
            criteria.and("nombre").regex(nombre.trim(), "i");
        if (genero != null && !genero.isBlank())
            criteria.and("genero").regex(genero.trim(), "i");
        if (hayPrecio) {
            // Precio y tienda dentro del MISMO elemento del array: "Steam entre 20.000 y 60.000"
            Criteria tienda = Criteria.where("precio");
            if (precioMinCop != null && precioMaxCop != null) tienda.gte(precioMinCop).lte(precioMaxCop);
            else if (precioMinCop != null) tienda.gte(precioMinCop);
            else tienda.lte(precioMaxCop);
            if (hayPlataforma) tienda.and("plataforma").is(plataformaVenta.trim());
            criteria.and("precios").elemMatch(tienda);
        } else if (hayPlataforma) {
            criteria.and("precios.plataforma").is(plataformaVenta.trim());
        }

        boolean porPrecio = "precio_asc".equals(orden) || "precio_desc".equals(orden);
        if (porPrecio && !hayPrecio) {
            criteria.and("precios.precio").exists(true);   // sin precio no hay nada que ordenar
        }

        Sort sort = Sort.unsorted();
        if (porPrecio) {
            Sort.Direction dir = "precio_desc".equals(orden) ? Sort.Direction.DESC : Sort.Direction.ASC;
            sort = Sort.by(new Sort.Order(dir, "precios.precio"), Sort.Order.asc("id"));   // "id": desempate estable
        } else if ("nombre".equals(orden)) {
            sort = Sort.by(Sort.Order.asc("nombre"), Sort.Order.asc("id"));
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, Juego.class);

        PageRequest pagina = PageRequest.of(page, size, sort);
        query.with(pagina);
        List<Juego> results = mongoTemplate.find(query, Juego.class);

        return new PageImpl<>(results, pagina, total);
    }

    // --- Operaciones CRUD y listados ---

    /**
     * Persiste un juego nuevo o actualiza uno existente si ya trae {@code id}.
     *
     * @param juego entidad a guardar
     * @return juego persistido (con id asignado si era nuevo)
     */
    public Juego crear(Juego juego) {
        return juegosRepository.save(juego);
    }

    /**
     * Obtiene un juego por identificador MongoDB.
     *
     * @param id identificador del documento
     * @return juego encontrado
     * @throws NoSuchElementException si no existe
     */
    public Juego obtenerPorId(String id) {
        return juegosRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Juego no encontrado: " + id));
    }

    /**
     * Elimina un juego por id.
     *
     * @param id identificador del juego
     */
    public void eliminar(String id) {
        juegosRepository.deleteById(id);
    }

    /**
     * Devuelve todos los juegos sin paginación (uso interno o exportaciones pequeñas).
     */
    public Iterable<Juego> obtenerTodos() {
        return juegosRepository.findAll();
    }

    /**
     * Lista todos los juegos con paginación estándar de Spring Data.
     *
     * @param page índice de página
     * @param size elementos por página
     */
    public Page<Juego> obtenerTodosPaginados(int page, int size) {
        return juegosRepository.findAll(PageRequest.of(page, size));
    }

    /**
     * Obtiene hasta {@code limite} juegos de un género concreto (p. ej. carrusel en home).
     *
     * @param genero  género a buscar
     * @param limite  máximo de resultados (solo primera página)
     */
    public List<Juego> obtenerPorGenero(String genero, int limite) {
        return juegosRepository.findByGeneroIgnoreCase(genero, PageRequest.of(0, limite)).getContent();
    }

    /**
     * Resuelve varios juegos a partir de una lista de ids (p. ej. wishlist).
     *
     * @param ids lista de identificadores MongoDB
     * @return juegos encontrados (puede ser menor que ids si alguno no existe)
     */
    public List<Juego> obtenerPorIds(List<String> ids) {
        return juegosRepository.findByIdIn(ids);
    }

    /**
     * Cuenta el total de documentos en la colección de juegos.
     *
     * @return número total de juegos
     */
    /**
     * Juegos con una bajada de precio reciente (descuento > 0 y cambio en los últimos
     * {@link PrecioPlataforma#DIAS_OFERTA} días), los de mayor descuento primero.
     * Es el mismo criterio de {@code PrecioPlataforma.isBajoDePrecio()}, que pinta la marca «↓ %» en la tarjeta.
     */
    public List<Juego> obtenerEnOferta(int limite) {
        Instant desde = Instant.now().minus(PrecioPlataforma.DIAS_OFERTA, ChronoUnit.DAYS);
        Criteria oferta = Criteria.where("descuento").gt(0).and("fechaCambio").gte(desde);
        Query query = new Query(Criteria.where("precios").elemMatch(oferta))
                .with(Sort.by(Sort.Order.desc("precios.fechaCambio")))
                .limit(Math.max(limite, 1) * 4);          // margen para reordenar por descuento
        List<Juego> lista = new ArrayList<>(mongoTemplate.find(query, Juego.class));
        lista.sort(Comparator.comparingInt(Juego::getMayorBajada).reversed());
        return lista.size() > limite ? new ArrayList<>(lista.subList(0, limite)) : lista;
    }

    public long contarTotal() { return juegosRepository.count(); }
}
