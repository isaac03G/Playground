/**
 * Activa el cambio de idioma en toda la aplicación.
 * <ul>
 *   <li>{@code localeResolver}: lee el idioma de la cookie {@code idioma} (ver {@link IdiomaLocaleResolver})</li>
 *   <li>{@code messageSource}: carga los textos de {@code messages.properties} (español, por defecto)
 *       y {@code messages_en.properties} (inglés)</li>
 * </ul>
 * {@code setFallbackToSystemLocale(false)} evita que un servidor con el sistema en inglés muestre
 * {@code messages_en} a quien pidió español.
 */
package com.proyecto.aula.playgroundv2.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;

@Configuration
public class IdiomaConfig {

    /** El nombre del método tiene que ser exactamente "localeResolver": así lo busca Spring MVC. */
    @Bean
    public LocaleResolver localeResolver() {
        return new IdiomaLocaleResolver();
    }

    /** El nombre del método tiene que ser exactamente "messageSource": así lo buscan Spring y Thymeleaf. */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource fuente = new ResourceBundleMessageSource();
        fuente.setBasename("messages");
        fuente.setDefaultEncoding("UTF-8");
        fuente.setFallbackToSystemLocale(false);
        return fuente;
    }
}
