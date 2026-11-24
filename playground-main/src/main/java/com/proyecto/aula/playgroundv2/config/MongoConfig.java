/**
 * Configuración de conversiones personalizadas para Spring Data MongoDB.
 * <p>
 * Permite leer documentos de juegos cuyo campo {@code precios} esté almacenado en formatos
 * legados (mapa plataforma → número) o como documentos BSON, normalizándolos siempre a
 * {@link com.proyecto.aula.playgroundv2.model.PrecioPlataforma} en listas.
 * </p>
 *
 * @author proyecto-aula
 */
package com.proyecto.aula.playgroundv2.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import com.proyecto.aula.playgroundv2.model.PrecioPlataforma;

/**
 * Registra convertidores de lectura para compatibilidad con esquemas antiguos de precios en Mongo.
 */
@Configuration
public class MongoConfig {

  /**
   * Bean que Spring Data Mongo usa al materializar entidades desde BSON.
   */
  @Bean
  public MongoCustomConversions mongoCustomConversions() {
    return new MongoCustomConversions(List.of(
        new PreciosDocumentReadConverter(),
        new PreciosMapReadConverter(),
        new PrecioPlataformaDocumentReadConverter()));
  }

  /**
   * Convierte un mapa Java (p. ej. deserializado desde JSON antiguo) a lista de precios.
   * <p>
   * Formatos soportados:
   * <ul>
   *   <li>Mapa antiguo: {@code { "Steam": 100000, "GOG": 90000 }}</li>
   *   <li>Documento único: {@code { "plataforma": "...", "precio": n }}</li>
   * </ul>
   * </p>
   */
  @ReadingConverter
  static class PreciosMapReadConverter implements Converter<Map<String, Object>, List<PrecioPlataforma>> {
    @Override
    public List<PrecioPlataforma> convert(Map<String, Object> source) {
      if (source == null || source.isEmpty()) {
        return null;
      }
      if (source.containsKey("plataforma") && source.containsKey("precio")) {
        return List.of(toPrecioPlataforma(source));
      }
      List<PrecioPlataforma> resultado = new ArrayList<>();
      for (Map.Entry<String, Object> entry : source.entrySet()) {
        double precio = entry.getValue() instanceof Number n ? n.doubleValue() : 0.0;
        resultado.add(new PrecioPlataforma(entry.getKey(), precio));
      }
      return resultado;
    }
  }

  /**
   * Igual que {@link PreciosMapReadConverter} pero cuando el driver entrega un {@link Document} BSON.
   */
  @ReadingConverter
  static class PreciosDocumentReadConverter implements Converter<Document, List<PrecioPlataforma>> {
    @Override
    public List<PrecioPlataforma> convert(Document source) {
      if (source == null || source.isEmpty()) {
        return null;
      }
      if (source.containsKey("plataforma")) {
        return List.of(toPrecioPlataforma(source));
      }
      List<PrecioPlataforma> resultado = new ArrayList<>();
      for (String plataforma : source.keySet()) {
        Object valor = source.get(plataforma);
        double precio = valor instanceof Number n ? n.doubleValue() : 0.0;
        resultado.add(new PrecioPlataforma(plataforma, precio));
      }
      return resultado;
    }
  }

  /**
   * Convierte un único subdocumento BSON a un {@link PrecioPlataforma}.
   */
  @ReadingConverter
  static class PrecioPlataformaDocumentReadConverter implements Converter<Document, PrecioPlataforma> {
    @Override
    public PrecioPlataforma convert(Document source) {
      return toPrecioPlataforma(source);
    }
  }

  /**
   * Utilidad compartida: extrae {@code plataforma} y {@code precio} de un mapa genérico, más los datos
   * de moneda que escriben los scrapers ({@code moneda}, {@code esGratis}, {@code precioOriginal},
   * {@code monedaOriginal}). Sin estos campos adicionales se perderían al leer el documento.
   */
  private static PrecioPlataforma toPrecioPlataforma(Map<String, ?> source) {
    Object plataforma = source.get("plataforma");
    Object precioVal = source.get("precio");
    if (plataforma == null) {
      return null;
    }
    double precio = precioVal instanceof Number n ? n.doubleValue() : 0.0;
    PrecioPlataforma resultado = new PrecioPlataforma(String.valueOf(plataforma), precio);
    if (source.get("moneda") != null) {
      resultado.setMoneda(String.valueOf(source.get("moneda")).trim().toUpperCase());
    }
    if (source.get("esGratis") instanceof Boolean gratis) {
      resultado.setEsGratis(gratis);
    }
    if (source.get("precioOriginal") instanceof Number original) {
      resultado.setPrecioOriginal(original.doubleValue());
    }
    if (source.get("monedaOriginal") != null) {
      resultado.setMonedaOriginal(String.valueOf(source.get("monedaOriginal")).trim().toUpperCase());
    }
    // Seguimiento de cambios que escribe actualizador.py. Sin leerlos aquí, las etiquetas "↓ %" de oferta
    // nunca aparecerían: este conversor reemplaza al mapeo automático de Spring Data.
    if (source.get("precioAnterior") instanceof Number anterior) {
      resultado.setPrecioAnterior(anterior.doubleValue());
    }
    if (source.get("descuento") instanceof Number descuento) {
      resultado.setDescuento(descuento.intValue());
    }
    resultado.setFechaCambio(aInstant(source.get("fechaCambio")));
    resultado.setUltimaRevision(aInstant(source.get("ultimaRevision")));
    return resultado;
  }

  /** Mongo entrega las fechas como {@link Date}; el modelo las guarda como {@link Instant}. */
  private static Instant aInstant(Object valor) {
    if (valor instanceof Date fecha) {
      return fecha.toInstant();
    }
    if (valor instanceof Instant instante) {
      return instante;
    }
    return null;
  }
}