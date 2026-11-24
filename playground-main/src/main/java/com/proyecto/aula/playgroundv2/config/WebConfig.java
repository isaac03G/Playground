/**
 * Configuración web MVC: recursos estáticos y archivos subidos por usuarios.
 * <p>
 * Expone el directorio configurado en {@code app.upload.dir} bajo la URL pública {@code /uploads/**},
 * de modo que imágenes de juegos u otros ficheros subidos se sirvan desde disco (desarrollo o producción).
 * </p>
 *
 * @author proyecto-aula
 */
package com.proyecto.aula.playgroundv2.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Implementa {@link WebMvcConfigurer} para registrar manejadores de recursos personalizados.
 * <p>
 * Sirve los archivos subidos desde disco bajo la URL /uploads/**
 * Las nuevas subidas se guardan fuera del JAR; los recursos historicos empaquetados
 * en static/uploads siguen disponibles como ubicacion alternativa.
 * </p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Ruta persistente donde se guardan las subidas; por defecto ./data/uploads.
     * Los recursos historicos de classpath:/static/uploads/ se sirven como alternativa.
     */
    @Value("${app.upload.dir:src/main/resources/static/uploads}")
    private String uploadDir;

    /**
     * Mapea {@code /uploads/**} al sistema de ficheros local (prefijo {@code file:}).
     * <p>
     * Cómo funciona: resuelve {@link #uploadDir} a ruta absoluta y la registra como
     * {@code ResourceLocation} para que Spring MVC sirva los bytes sin pasar por un controlador.
     * </p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath();
        registry.addResourceHandler("/uploads/**")
                // Primero las subidas persistentes; despues los recursos historicos empaquetados.
                .addResourceLocations("file:" + uploadPath + "/", "classpath:/static/uploads/")
                .setCachePeriod(604800);
    }
}
