package com.proyecto.aula.playgroundv2.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Revisa los precios SOLA, sin que nadie pulse nada: mientras la aplicacion este encendida lanza
 * {@code actualizador.py} (modo "automatico": tasas de cambio + precios de TODOS los juegos guardados)
 * poco despues de arrancar y luego cada {@code app.actualizador.cada}.
 * <p>
 * Configuracion en application.properties:
 * <pre>
 * app.actualizador.auto=true              # true = activo; false o ausente = apagado
 * app.actualizador.cada=PT6H              # cada cuanto (ISO-8601: PT30M = 30 min, PT6H = 6 h, PT12H = 12 h)
 * app.actualizador.espera-inicial=PT2M    # espera tras arrancar la app antes de la primera pasada
 * </pre>
 * Reutiliza {@link ActualizadorService}: la salida se ve en el panel admin, y si ya hay una ejecucion en curso
 * (por ejemplo una que lanzaste a mano) esta pasada se omite sin error.
 */
@Component
@ConditionalOnProperty(name = "app.actualizador.auto", havingValue = "true")
public class ActualizadorProgramado {

    private static final Logger log = LoggerFactory.getLogger(ActualizadorProgramado.class);

    private final ActualizadorService actualizador;

    public ActualizadorProgramado(ActualizadorService actualizador) {
        this.actualizador = actualizador;
    }

    @Scheduled(initialDelayString = "${app.actualizador.espera-inicial:PT2M}",
               fixedRateString = "${app.actualizador.cada:PT6H}")
    public void revisarPrecios() {
        try {
            actualizador.iniciar("automatico");
            log.info("Actualizacion automatica de precios iniciada (se ve en el panel admin).");
        } catch (IllegalStateException e) {
            // ya hay una en curso, o no se encuentra el script/Python: se registra y se reintenta en el siguiente ciclo
            log.warn("Actualizacion automatica omitida: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Fallo inesperado al lanzar la actualizacion automatica", e);
        }
    }
}
