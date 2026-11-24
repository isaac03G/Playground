package com.proyecto.aula.playgroundv2.repository;

import com.proyecto.aula.playgroundv2.model.PrediccionDescuento;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Acceso a la coleccion predicciones_descuento (la web solo la lee). */
public interface PrediccionDescuentoRepository extends MongoRepository<PrediccionDescuento, String> {
}
