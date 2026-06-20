package com.nemeles.npcai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Cliente HTTP asíncrono para Ollama (IA local). Devuelve null si Ollama no está disponible. */
public final class OllamaClient {

    private final HttpClient http;
    private final String url;
    private final String keepAlive;
    private final double temperature, topP, repeatPenalty;
    private final int maxTokens, requestTimeout, repeatLastN;
    private final Gson gson = new Gson();

    public OllamaClient(AiConfig cfg) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(cfg.connectTimeout)).build();
        String u = cfg.ollamaUrl == null ? "http://localhost:11434" : cfg.ollamaUrl.trim();
        this.url = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.keepAlive = cfg.keepAlive;
        this.temperature = cfg.temperature;
        this.maxTokens = cfg.maxTokens;
        this.requestTimeout = cfg.requestTimeout;
        this.topP = cfg.topP;
        this.repeatPenalty = cfg.repeatPenalty;
        this.repeatLastN = cfg.repeatLastN;
    }

    /** POST /api/chat (no-stream). Resuelve con el texto del NPC, o null si hay error/timeout/offline. */
    public CompletableFuture<String> chat(String model, List<ChatMsg> messages) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", false);
        root.addProperty("keep_alive", keepAlive); // mantener el modelo en VRAM (evita recargas lentas)
        JsonArray arr = new JsonArray();
        for (ChatMsg m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role());
            o.addProperty("content", m.content());
            arr.add(o);
        }
        root.add("messages", arr);
        JsonObject opts = new JsonObject();
        opts.addProperty("temperature", temperature);
        opts.addProperty("num_predict", maxTokens);
        // Anti-repeticion: penaliza repetir las mismas frases/tokens y abre algo de variedad (top_p).
        if (topP > 0) opts.addProperty("top_p", topP);
        if (repeatPenalty > 0) opts.addProperty("repeat_penalty", repeatPenalty);
        if (repeatLastN > 0) opts.addProperty("repeat_last_n", repeatLastN);
        root.add("options", opts);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url + "/api/chat"))
                    .timeout(Duration.ofSeconds(requestTimeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(root), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    try {
                        if (resp.statusCode() != 200) return (String) null;
                        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                        if (o.has("message")) {
                            JsonObject m = o.getAsJsonObject("message");
                            if (m.has("content")) return m.get("content").getAsString();
                        }
                        return (String) null;
                    } catch (Exception e) {
                        return (String) null;
                    }
                })
                .exceptionally(ex -> null);
    }

    /** Carga el modelo en VRAM al arrancar (fire-and-forget) para que la primera respuesta no tarde. */
    public void warmup(String model) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", false);
        root.addProperty("keep_alive", keepAlive);
        JsonArray arr = new JsonArray();
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", "hola");
        arr.add(u);
        root.add("messages", arr);
        JsonObject opts = new JsonObject();
        opts.addProperty("num_predict", 1);
        root.add("options", opts);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/chat"))
                    .timeout(Duration.ofSeconds(requestTimeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(root), StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); // fire and forget
        } catch (Exception ignored) {
        }
    }
}
