/**
 * Resultado completo de buscar un juego por nombre en las tiendas (lo muestra el panel admin).
 */
package com.proyecto.aula.playgroundv2.dto;

import java.io.Serializable;
import java.util.List;

public class ResultadoBusqueda implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String consulta;
    private final boolean encontrado;
    private final String nombre;
    private final String genero;
    /** {@code null} (no se guardó), {@code insertado}, {@code actualizado} o {@code sin_precio}. */
    private final String guardado;
    private final boolean solicitoGuardar;
    private final List<TiendaResultado> tiendas;

    public ResultadoBusqueda(String consulta, boolean encontrado, String nombre, String genero,
                             String guardado, boolean solicitoGuardar, List<TiendaResultado> tiendas) {
        this.consulta = consulta;
        this.encontrado = encontrado;
        this.nombre = nombre;
        this.genero = genero;
        this.guardado = guardado;
        this.solicitoGuardar = solicitoGuardar;
        this.tiendas = tiendas;
    }

    public String getConsulta() { return consulta; }
    public boolean isEncontrado() { return encontrado; }
    public String getNombre() { return nombre; }
    public String getGenero() { return genero; }
    public String getGuardado() { return guardado; }
    public boolean isSolicitoGuardar() { return solicitoGuardar; }
    public List<TiendaResultado> getTiendas() { return tiendas; }
}
