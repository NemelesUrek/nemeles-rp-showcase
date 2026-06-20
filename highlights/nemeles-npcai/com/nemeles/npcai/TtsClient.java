package com.nemeles.npcai;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Cliente del microservicio de VOZ CLONADA (Chatterbox, ver tts-server/). Solo unos pocos
 * personajes (config voice-clone.characters) tienen voz clonada; al resto ni se le llama.
 * Si el servicio no responde, devolvemos null y la pagina de voz usa la voz del navegador.
 */
public final class TtsClient {

    private final boolean enabled;
    private final String url;             // p.ej. http://127.0.0.1:8979
    private final Set<String> characters; // claves de NPC con voz clonada (en minusculas)
    private final int timeoutSeconds;
    private final HttpClient http;

    public TtsClient(boolean enabled, String url, Set<String> characters, int timeoutSeconds) {
        this.enabled = enabled;
        String u = url == null ? "" : url.trim();
        this.url = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.characters = characters;
        this.timeoutSeconds = Math.max(3, timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** ¿Este NPC debe hablar con voz clonada? */
    public boolean isCloned(String key) {
        return enabled && key != null && characters.contains(key.toLowerCase());
    }

    /** Pide el audio WAV de ese personaje hablando ese texto. null si falla (=> fallback navegador). */
    public CompletableFuture<byte[]> synth(String voiceKey, String text) {
        if (!enabled || url.isEmpty()) return CompletableFuture.completedFuture(null);
        JsonObject body = new JsonObject();
        body.addProperty("voice", voiceKey);
        body.addProperty("text", text);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url + "/tts"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    byte[] b = resp.body();
                    String ct = resp.headers().firstValue("Content-Type").orElse("");
                    if (resp.statusCode() == 200 && ct.startsWith("audio") && b != null && b.length > 44) return b;
                    return (byte[]) null;
                })
                .exceptionally(ex -> null);
    }
}
