package com.proyecto.aula.playgroundv2.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proyecto.aula.playgroundv2.model.Juego;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class SteamGridDbService {

    private static final Logger log = LoggerFactory.getLogger(SteamGridDbService.class);
    private static final String API_BASE = "https://www.steamgriddb.com/api/v2";
    private static final String STEAM_STORE_API = "https://store.steampowered.com/api";

    private final MongoTemplate mongo;
    private final ObjectMapper json;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public SteamGridDbService(MongoTemplate mongo, ObjectMapper json,
                              @Value("${app.steamgriddb.api-key:}") String apiKey,
                              @Value("${app.steamgriddb.timeout-ms:2500}") long timeoutMs) {
        this.mongo = mongo;
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** Finds and caches a matching hero, using the existing cover as the view fallback. */
    public String obtenerBanner(Juego juego) {
        if (juego == null || juego.getJuegoId() == null || juego.getNombre() == null
                || juego.getNombre().isBlank()) {
            return null;
        }
        if (Boolean.TRUE.equals(juego.getSteamGridDbBannerBuscado())
                && (apiKey.isBlank() || Boolean.TRUE.equals(juego.getSteamGridDbBannerSgdbConsultado()))) {
            return juego.getSteamGridDbBannerUrl();
        }

        String gameId = juego.getJuegoId();
        try {
            String banner = buscarBanner(juego.getNombre());
            juego.setSteamGridDbBannerUrl(banner);
            juego.setSteamGridDbBannerBuscado(true);
            juego.setSteamGridDbBannerSgdbConsultado(!apiKey.isBlank());
            guardarCache(gameId, banner);
            return banner;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Se interrumpió la búsqueda de banner para el juego {}", gameId);
            return null;
        } catch (Exception e) {
            log.warn("No se pudo buscar un banner para el juego {}: {}", gameId, e.toString());
            return null;
        }
    }

    private String buscarBanner(String nombre) throws IOException, InterruptedException {
        if (!apiKey.isBlank()) {
            try {
                String bannerSteamGridDb = buscarEnSteamGridDb(nombre);
                if (bannerSteamGridDb != null) return bannerSteamGridDb;
            } catch (IOException e) {
                log.warn("SteamGridDB no encontró un banner utilizable para '{}': {}", nombre, e.toString());
            }
        }
        return buscarEnSteamStore(nombre);
    }

    private String buscarEnSteamGridDb(String nombre) throws IOException, InterruptedException {
        JsonNode games = solicitarSteamGridDb(API_BASE + "/search/autocomplete/" + encodePath(nombre));
        JsonNode juegoCoincidente = null;
        String nombreNormalizado = normalizar(nombre);
        for (JsonNode resultado : games.path("data")) {
            if (nombreNormalizado.equals(normalizar(resultado.path("name").asText()))) {
                juegoCoincidente = resultado;
                break;
            }
        }
        if (juegoCoincidente == null || !juegoCoincidente.hasNonNull("id")) return null;

        String gameId = juegoCoincidente.path("id").asText();
        JsonNode heroes = solicitarSteamGridDb(API_BASE + "/heroes/game/" + gameId
                + "?dimensions=1920x620,3840x1240,1600x650"
                + "&nsfw=false&humor=false&epilepsy=false&limit=20");
        for (JsonNode hero : heroes.path("data")) {
            String url = hero.path("url").asText("");
            if (esUrlSteamGridDb(url)) return url;
        }
        return null;
    }

    private String buscarEnSteamStore(String nombre) throws IOException, InterruptedException {
        JsonNode games = solicitar(STEAM_STORE_API + "/storesearch/?term=" + encodeQuery(nombre)
                + "&l=en&cc=US", false);
        JsonNode game = null;
        String nombreNormalizado = normalizar(nombre);
        for (JsonNode resultado : games.path("items")) {
            if ("app".equals(resultado.path("type").asText())
                    && nombreNormalizado.equals(normalizar(resultado.path("name").asText()))) {
                game = resultado;
                break;
            }
        }
        if (game == null || !game.hasNonNull("id")) return null;

        String appId = game.path("id").asText();
        JsonNode details = solicitar(STEAM_STORE_API + "/appdetails?appids=" + encodeQuery(appId)
                + "&l=en", false);
        JsonNode app = details.path(appId);
        if (!app.path("success").asBoolean(false)
                || !nombreNormalizado.equals(normalizar(app.path("data").path("name").asText()))) {
            return null;
        }
        String image = app.path("data").path("header_image").asText("");
        return esUrlSteamStore(image) ? image : null;
    }

    private JsonNode solicitar(String url, boolean conAutorizacion) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "PlaygroundGameCatalog/1.0")
                .GET();
        if (conAutorizacion) requestBuilder.header("Authorization", "Bearer " + apiKey);
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("SteamGridDB respondió HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    private JsonNode solicitarSteamGridDb(String url) throws IOException, InterruptedException {
        JsonNode root = solicitar(url, true);
        if (!root.path("success").asBoolean(false)) {
            throw new IOException("SteamGridDB devolvió una respuesta sin éxito");
        }
        return root;
    }

    private void guardarCache(String gameId, String banner) {
        try {
            Update update = new Update()
                    .set("steamGridDbBannerBuscado", true)
                    .set("steamGridDbBannerSgdbConsultado", !apiKey.isBlank())
                    .set("steamGridDbBannerUrl", banner);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(gameId)), update, Juego.class);
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar en MongoDB el banner del juego {}: {}", gameId, e.toString());
        }
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizar(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private static boolean esUrlSteamGridDb(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("steamgriddb.com")
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".steamgriddb.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean esUrlSteamStore(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) return false;
            String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
            return normalizedHost.equals("store.steampowered.com")
                    || normalizedHost.endsWith(".steamstatic.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
