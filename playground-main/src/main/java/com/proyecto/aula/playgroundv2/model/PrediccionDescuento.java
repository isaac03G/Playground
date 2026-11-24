package com.proyecto.aula.playgroundv2.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Prediccion del modelo: probabilidad de que un juego baje de precio en una tienda en los proximos
 * horizonteDias. La escribe playground-scraper/predictor.py; la web solo la lee.
 */
@Document(collection = "predicciones_descuento")
@Getter @Setter @NoArgsConstructor
public class PrediccionDescuento {
    @Id
    private String id;
    /** Es Juego.getId() guardado como texto. */
    private String juegoId;
    private String nombre;
    private String plataforma;
    private double probabilidad;
    private boolean probableDescuento;
    private int horizonteDias;
    private Instant calculadoEn;
}
