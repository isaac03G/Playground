/**
 * Traduce los textos que vienen de la base de datos al idioma elegido por el usuario. El idioma de origen
 * se detecta automáticamente porque los scrapers pueden guardar descripciones en español o en inglés.
 * Los textos fijos de la interfaz usan {@code messages.properties} / {@code messages_en.properties}.
 * <p>
 * Usa la API gratuita <b>MyMemory</b> (https://mymemory.translated.net): no pide registro ni clave.
 * Límites del plan gratis: 500 bytes por petición y 5.000 caracteres al día por IP; si se envía un correo
 * en {@code app.traduccion.email} el límite sube a 50.000 caracteres al día.
 * </p>
 * Para gastar poco de esa cuota:
 * <ul>
 *   <li>cada texto se traduce UNA sola vez: el resultado queda en memoria y en la colección Mongo
 *       {@code traducciones}, y sirve para todos los visitantes</li>
 *   <li>si la API falla, se acaba la cuota o se demora, se muestra el texto original en español
 *       y no se guarda nada; tras un aviso de cuota se deja de llamar 1 hora</li>
 * </ul>
 * Se expone a Thymeleaf como {@code @traductor}:
 * <pre>th:text="${@traductor.traducir(juego.descripcion)}"</pre>
 */
package com.proyecto.aula.playgroundv2.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service("traductor")
public class TraduccionService {

    private static final Logger log = LoggerFactory.getLogger(TraduccionService.class);

    /** MyMemory acepta máximo 500 bytes por petición; se deja margen. */
    private static final int MAX_BYTES = 450;
    private static final int MAX_EN_MEMORIA = 2000;
    private static final Duration PAUSA_POR_CUOTA = Duration.ofHours(1);
    private static final Pattern FIN_DE_FRASE = Pattern.compile("(?<=[.!?…])\\s+");

    private final MongoTemplate mongo;
    private final boolean activo;
    private final String url;
    private final String email;
    private final String coleccion;
    private final Duration espera;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private final Map<String, String> memoria = new ConcurrentHashMap<>();
    private volatile Instant pausadoHasta = Instant.EPOCH;

    public TraduccionService(MongoTemplate mongo,
                             @Value("${app.traduccion.activo:true}") boolean activo,
                             @Value("${app.traduccion.url:https://api.mymemory.translated.net/get}") String url,
                             @Value("${app.traduccion.email:}") String email,
                             @Value("${app.traduccion.coleccion:traducciones}") String coleccion,
                             @Value("${app.traduccion.timeout-ms:4000}") long timeoutMs) {
        this.mongo = mongo;
        this.activo = activo;
        this.url = url;
        this.email = email == null ? "" : email.trim();
        this.coleccion = coleccion;
        this.espera = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.espera).build();
    }

    // ------------------------------------------------------------------ uso público

    /** Traduce al idioma de la petición actual (cookie {@code idioma}). */
    public String traducir(String texto) {
        return traducir(texto, LocaleContextHolder.getLocale());
    }

    /**
     * Devuelve {@code texto} en el idioma {@code destino}; MyMemory detecta el idioma original. Si la
     * traducción está desactivada o no se pudo traducir, devuelve el texto original.
     */
    public String traducir(String texto, Locale destino) {
        if (texto == null || texto.isBlank()) return texto;
        String idioma = destino == null ? "es" : destino.getLanguage();
        if (!"es".equals(idioma) && !"en".equals(idioma)) return texto;
        if (!activo) return texto;

        String clave = "autodetect:" + idioma + ":" + huella(texto);
        String enMemoria = memoria.get(clave);
        if (enMemoria != null) return enMemoria;

        String guardada = leerGuardada(clave);
        if (guardada != null) {
            recordar(clave, guardada);
            return guardada;
        }

        String nueva = consultarApi(texto, idioma);
        if (nueva == null) return texto;          // falló: se ve en español y se reintenta en otra visita

        recordar(clave, nueva);
        guardar(clave, idioma, texto, nueva);
        return nueva;
    }

    // ------------------------------------------------------------------ API MyMemory

    /** Traduce el texto completo (por trozos si es largo). Devuelve {@code null} si algo falla. */
    private String consultarApi(String texto, String idioma) {
        if (Instant.now().isBefore(pausadoHasta)) return null;

        StringBuilder resultado = new StringBuilder();
        for (String trozo : partir(texto)) {
            String traducido = traducirTrozo(trozo, idioma);
            if (traducido == null) return null;   // mejor todo en español que mitad y mitad
            if (resultado.length() > 0) resultado.append(' ');
            resultado.append(traducido);
        }
        return resultado.length() == 0 ? null : resultado.toString();
    }

    private String traducirTrozo(String trozo, String idioma) {
        try {
            StringBuilder direccion = new StringBuilder(url)
                    .append("?q=").append(codificar(trozo))
                    .append("&langpair=").append(codificar("autodetect|" + idioma));
            if (!email.isEmpty()) direccion.append("&de=").append(codificar(email));

            HttpRequest peticion = HttpRequest.newBuilder(URI.create(direccion.toString()))
                    .timeout(espera)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (respuesta.statusCode() == 429) {
                pausar("HTTP 429");
                return null;
            }
            if (respuesta.statusCode() != 200) {
                log.warn("MyMemory respondió HTTP {}", respuesta.statusCode());
                return null;
            }

            JsonNode raiz = json.readTree(respuesta.body());
            int estado = raiz.path("responseStatus").asInt(200);
            String texto = raiz.path("responseData").path("translatedText").asText("");

            // Cuando se acaba la cuota diaria MyMemory NO da error: devuelve un aviso como si fuera la traducción.
            if (estado == 429 || texto.toUpperCase(Locale.ROOT).contains("MYMEMORY WARNING")) {
                pausar("cuota diaria agotada");
                return null;
            }
            if (estado != 200 || texto.isBlank()) {
                log.warn("MyMemory no pudo traducir (estado {})", estado);
                return null;
            }
            // MyMemory devuelve entidades HTML (&#39;, &quot;...): se decodifican porque Thymeleaf ya escapa al mostrar
            return HtmlUtils.htmlUnescape(texto).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("No se pudo traducir con MyMemory: {}", e.toString());
            return null;
        }
    }

    private void pausar(String motivo) {
        pausadoHasta = Instant.now().plus(PAUSA_POR_CUOTA);
        log.warn("MyMemory: {}. Se deja de traducir durante 1 hora (se muestra el texto original).", motivo);
    }

    // ------------------------------------------------------------------ partir en trozos de <= 450 bytes

    private static List<String> partir(String texto) {
        List<String> trozos = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String frase : FIN_DE_FRASE.split(texto.trim())) {
            for (String parte : cortarFrase(frase)) {
                if (actual.length() > 0 && bytes(actual) + 1 + bytes(parte) > MAX_BYTES) {
                    trozos.add(actual.toString());
                    actual.setLength(0);
                }
                if (actual.length() > 0) actual.append(' ');
                actual.append(parte);
            }
        }
        if (actual.length() > 0) trozos.add(actual.toString());
        return trozos;
    }

    /** Una frase que por sí sola pasa el límite se corta por palabras (y una palabra enorme, por letras). */
    private static List<String> cortarFrase(String frase) {
        List<String> partes = new ArrayList<>();
        if (bytes(frase) <= MAX_BYTES) {
            partes.add(frase);
            return partes;
        }
        StringBuilder actual = new StringBuilder();
        for (String palabra : frase.split("\\s+")) {
            while (bytes(palabra) > MAX_BYTES) {            // p. ej. una URL larguísima
                if (actual.length() > 0) { partes.add(actual.toString()); actual.setLength(0); }
                partes.add(palabra.substring(0, 100));      // 100 letras ocupan 300 bytes como máximo (3 bytes por letra)
                palabra = palabra.substring(100);
            }
            if (actual.length() > 0 && bytes(actual) + 1 + bytes(palabra) > MAX_BYTES) {
                partes.add(actual.toString());
                actual.setLength(0);
            }
            if (actual.length() > 0) actual.append(' ');
            actual.append(palabra);
        }
        if (actual.length() > 0) partes.add(actual.toString());
        return partes;
    }

    private static int bytes(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String codificar(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ caché (memoria + Mongo)

    private void recordar(String clave, String traduccion) {
        if (memoria.size() >= MAX_EN_MEMORIA) memoria.clear();   // tope sencillo; Mongo conserva todo
        memoria.put(clave, traduccion);
    }

    private String leerGuardada(String clave) {
        try {
            Document doc = mongo.findById(clave, Document.class, coleccion);
            return doc == null ? null : doc.getString("traducido");
        } catch (Exception e) {
            log.debug("No se pudo leer la caché de traducciones: {}", e.toString());
            return null;
        }
    }

    private void guardar(String clave, String idioma, String original, String traducido) {
        try {
            Document doc = new Document("_id", clave)
                    .append("idioma", idioma)
                    .append("original", original)
                    .append("traducido", traducido)
                    .append("creadoEn", new Date());
            mongo.save(doc, coleccion);
        } catch (Exception e) {
            log.debug("No se pudo guardar la traducción: {}", e.toString());
        }
    }

    /** Huella del texto original: si alguien edita la descripción, cambia la clave y se vuelve a traducir. */
    private static String huella(String texto) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(texto.hashCode());
        }
    }
}
