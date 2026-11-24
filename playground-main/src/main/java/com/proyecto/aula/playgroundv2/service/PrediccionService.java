package com.proyecto.aula.playgroundv2.service;

import com.proyecto.aula.playgroundv2.model.PrediccionDescuento;
import com.proyecto.aula.playgroundv2.repository.PrediccionDescuentoRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Consulta las predicciones de descuento (bean prediccionService, usable desde Thymeleaf).
 * Guarda todo en memoria 5 minutos para no consultar MongoDB por cada tarjeta.
 * Ofrece datos por juego (mayor probabilidad entre sus tiendas) y por tienda.
 */
@Service("prediccionService")
public class PrediccionService {

    private static final long CACHE_MS = 5 * 60 * 1000L;

    private record Datos(Map<String, Double> porJuego, Map<String, Double> porTienda,
                         Set<String> juegosProbables, Set<String> tiendasProbables) { }

    private final PrediccionDescuentoRepository repo;
    private volatile Datos datos = new Datos(Map.of(), Map.of(), Set.of(), Set.of());
    private volatile long cargadoEn = 0;

    public PrediccionService(PrediccionDescuentoRepository repo) {
        this.repo = repo;
    }

    private static String clave(String juegoId, String plataforma) {
        return juegoId + "|" + (plataforma == null ? "" : plataforma.trim().toLowerCase());
    }

    private Datos datos() {
        if (System.currentTimeMillis() - cargadoEn > CACHE_MS) {
            synchronized (this) {
                if (System.currentTimeMillis() - cargadoEn > CACHE_MS) {
                    Map<String, Double> pj = new HashMap<>();
                    Map<String, Double> pt = new HashMap<>();
                    Set<String> jp = new HashSet<>();
                    Set<String> tp = new HashSet<>();
                    try {
                        for (PrediccionDescuento p : repo.findAll()) {
                            if (p.getJuegoId() == null) continue;
                            String k = clave(p.getJuegoId(), p.getPlataforma());
                            pj.merge(p.getJuegoId(), p.getProbabilidad(), Math::max);
                            pt.merge(k, p.getProbabilidad(), Math::max);
                            if (p.isProbableDescuento()) {
                                jp.add(p.getJuegoId());
                                tp.add(k);
                            }
                        }
                    } catch (Exception e) {
                        pj.clear(); pt.clear(); jp.clear(); tp.clear();   // si falla, la web sigue sin mostrar nada
                    }
                    datos = new Datos(pj, pt, jp, tp);
                    cargadoEn = System.currentTimeMillis();
                }
            }
        }
        return datos;
    }

    private static Integer pct(Double p) {
        return p == null ? null : (int) Math.round(p * 100);
    }

    /** El modelo cree probable una bajada de precio en alguna tienda de este juego? */
    public boolean probable(String juegoId) {
        return juegoId != null && datos().juegosProbables().contains(juegoId);
    }

    /** Mayor probabilidad (0-100) entre las tiendas del juego, o null si no hay prediccion. */
    public Integer porcentaje(String juegoId) {
        return juegoId == null ? null : pct(datos().porJuego().get(juegoId));
    }

    /** Probable una bajada en esta tienda concreta? */
    public boolean probableTienda(String juegoId, String plataforma) {
        return juegoId != null && datos().tiendasProbables().contains(clave(juegoId, plataforma));
    }

    /** Probabilidad (0-100) para esta tienda, o null si no hay prediccion. */
    public Integer porcentajeTienda(String juegoId, String plataforma) {
        return juegoId == null ? null : pct(datos().porTienda().get(clave(juegoId, plataforma)));
    }
}
