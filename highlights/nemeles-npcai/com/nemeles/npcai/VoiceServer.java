package com.nemeles.npcai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * VOZ: habla con los NPCs por MICROFONO. El plugin sirve una pagina web minima ( /voz te da el
 * enlace); el NAVEGADOR transcribe lo que dices (Web Speech API: gratis, sin claves, en espanol)
 * y envia el texto aqui. El texto entra en la conversacion como si lo hubieras escrito en el chat:
 * si no estas hablando con nadie, se inicia sola la charla con el NPC mas cercano. El NPC contesta
 * por texto (chat + holograma), como siempre. Sin mods: vale para Java y (via navegador) Bedrock.
 *
 * OJO navegador: el microfono exige "contexto seguro" -> funciona directo en http://localhost
 * (jugando en el mismo PC) o detras de https (config public-url). Para una IP de LAN por http,
 * Chrome necesita un flag; la propia pagina lo explica si detecta el bloqueo.
 */
public final class VoiceServer implements ConversationManager.ReplyListener {

    private record Tok(UUID player, long expires) { }

    private final Plugin plugin;
    private final ConversationManager mgr;
    private final Supplier<AiConfig> cfg;
    private final Map<String, Tok> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Deque<JsonObject>> pending = new ConcurrentHashMap<>();   // respuestas de NPC por leer
    private final Map<String, CompletableFuture<byte[]>> audioJobs = new ConcurrentHashMap<>(); // audio clonado por id

    private final TtsClient tts;          // voz clonada (Chatterbox); puede estar desactivada
    private final int ttsTimeoutSeconds;

    private final int port;
    private final String bind, publicHost, publicUrl, lang;
    private final long ttlMs;

    private HttpServer http;
    private ExecutorService pool;
    private volatile String pageCache;

    public VoiceServer(Plugin plugin, ConversationManager mgr, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.cfg = cfg;
        FileConfiguration c = plugin.getConfig();
        port       = c.getInt("voice-chat.port", 8978);
        bind       = c.getString("voice-chat.bind", "0.0.0.0");
        publicHost = c.getString("voice-chat.public-host", "");
        publicUrl  = c.getString("voice-chat.public-url", "");
        lang       = c.getString("voice-chat.lang", "es-ES");
        ttlMs      = Math.max(1, c.getInt("voice-chat.token-minutes", 120)) * 60_000L;

        // Voz clonada (microservicio Chatterbox en tts-server/). Solo unos personajes.
        boolean clEnabled = c.getBoolean("voice-clone.enabled", true);
        String clUrl      = c.getString("voice-clone.url", "http://127.0.0.1:8979");
        ttsTimeoutSeconds = Math.max(3, c.getInt("voice-clone.timeout-seconds", 25));
        java.util.Set<String> chars = new java.util.HashSet<>();
        java.util.List<String> list = c.getStringList("voice-clone.characters");
        if (list == null || list.isEmpty()) list = java.util.List.of("luna", "simon", "maid", "chibi", "miss");
        for (String s : list) if (s != null) chars.add(s.toLowerCase());
        tts = new TtsClient(clEnabled, clUrl, chars, ttsTimeoutSeconds);
    }

    public int port() { return port; }

    public void start() throws Exception {
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        // 6 hilos: /audio puede quedarse ESPERANDO al microservicio de voz clonada hasta 25s;
        // con solo 3, dos audios largos a la vez dejarian sin hilo a /poll y /say.
        pool = Executors.newFixedThreadPool(6, r -> { Thread t = new Thread(r, "nemeles-voz"); t.setDaemon(true); return t; });
        http.setExecutor(pool);
        http.createContext("/", ex -> {
            try { route(ex); } catch (Throwable t) { try { ex.close(); } catch (Throwable ignored) { } }
        });
        http.start();
    }

    public void stop() {
        try { if (http != null) http.stop(0); } catch (Throwable ignored) { }
        try { if (pool != null) pool.shutdownNow(); } catch (Throwable ignored) { }
        tokens.clear();
    }

    /** Crea un token para este jugador y devuelve el ENLACE completo de su pagina de voz. */
    public String issueToken(Player p) {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue().expires() < now);
        String tok = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        tokens.put(tok, new Tok(p.getUniqueId(), now + ttlMs));
        String base = (publicUrl != null && !publicUrl.isBlank())
                ? publicUrl.replaceAll("/+$", "")
                : "http://" + hostFor(p) + ":" + port;
        return base + "/voz?t=" + tok + "&lang=" + lang;
    }

    /** Host que vera el jugador en el enlace: config > localhost (si juega en este PC) > IP de LAN. */
    private String hostFor(Player p) {
        if (publicHost != null && !publicHost.isBlank()) return publicHost;
        try {
            if (p.getAddress() != null && p.getAddress().getAddress() != null
                    && p.getAddress().getAddress().isLoopbackAddress()) return "localhost";
        } catch (Throwable ignored) { }
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80);   // no envia nada: solo resuelve la IP local de salida
            return s.getLocalAddress().getHostAddress();
        } catch (Throwable t) { return "localhost"; }
    }

    /** Recibe cada respuesta de NPC (saludos incluidos) y la encola para que la pagina la LEA EN ALTO. */
    @Override
    public void onNpcReply(UUID player, NpcPersona persona, String text) {
        java.util.Deque<JsonObject> q = pending.computeIfAbsent(player, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        JsonObject o = new JsonObject();
        o.addProperty("npc", persona.name);
        o.addProperty("pitch", persona.voicePitch);   // el tono del personaje tambien colorea su voz TTS
        o.addProperty("gender", persona.voiceGender);  // "f"/"m": la pagina elige una voz de ese genero
        o.addProperty("key", persona.key);             // id estable: cada NPC mantiene SIEMPRE la misma voz
        o.addProperty("text", text);
        // Voz CLONADA: si este personaje es de los elegidos, pedimos el audio al microservicio (async)
        // y mandamos un id; la pagina lo reproducira en vez de la voz del navegador. Si falla, fallback.
        if (tts.isCloned(persona.key)) {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            CompletableFuture<byte[]> job = tts.synth(persona.key, text);
            audioJobs.put(id, job);
            o.addProperty("audio", id);
            // limpieza: pasado un rato, suelta el audio (lo haya consumido la pagina o no).
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> audioJobs.remove(id), 20L * 40);
        }
        q.addLast(o);
        while (q.size() > 12) q.pollFirst();
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    private void route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        if ("/say".equals(path) && "POST".equalsIgnoreCase(ex.getRequestMethod())) { say(ex); return; }
        if ("/poll".equals(path) && "GET".equalsIgnoreCase(ex.getRequestMethod())) { poll(ex); return; }
        if ("/audio".equals(path) && "GET".equalsIgnoreCase(ex.getRequestMethod())) { audio(ex); return; }
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) { page(ex); return; }
        respond(ex, 404, "text/plain; charset=utf-8", "no");
    }

    /** La pagina pregunta cada poco si hay respuestas nuevas del NPC que leer en alto. */
    private void poll(HttpExchange ex) throws Exception {
        String tok = queryParam(ex, "t");
        Tok info = tok == null ? null : tokens.get(tok);
        if (info == null || info.expires() < System.currentTimeMillis()) {
            respondJson(ex, 401, err("enlace caducado"));
            return;
        }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        java.util.Deque<JsonObject> q = pending.get(info.player());
        if (q != null) { JsonObject m; while ((m = q.pollFirst()) != null) arr.add(m); }
        JsonObject out = new JsonObject();
        out.add("msgs", arr);
        respondJson(ex, 200, out);
    }

    /** Devuelve el WAV de la voz CLONADA de un NPC (espera a que el microservicio lo genere). */
    private void audio(HttpExchange ex) throws Exception {
        String tok = queryParam(ex, "t");
        Tok info = tok == null ? null : tokens.get(tok);
        if (info == null || info.expires() < System.currentTimeMillis()) { respond(ex, 401, "text/plain", "no"); return; }
        String id = queryParam(ex, "id");
        CompletableFuture<byte[]> job = id == null ? null : audioJobs.get(id);
        if (job == null) { respond(ex, 404, "text/plain", "no"); return; }
        byte[] wav;
        try { wav = job.get(ttsTimeoutSeconds, TimeUnit.SECONDS); }
        catch (Throwable t) { wav = null; }
        audioJobs.remove(id);
        if (wav == null || wav.length < 45) {
            // el microservicio esta apagado o fallo: 204 -> la pagina usa la voz del navegador
            ex.sendResponseHeaders(204, -1); ex.close(); return;
        }
        ex.getResponseHeaders().set("Content-Type", "audio/wav");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, wav.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(wav); }
    }

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void page(HttpExchange ex) throws Exception {
        String html = pageCache;
        if (html == null) {
            try (InputStream in = plugin.getResource("voice.html")) {
                html = in == null ? "<h1>voice.html no encontrado en el jar</h1>"
                                  : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            pageCache = html;
        }
        respond(ex, 200, "text/html; charset=utf-8", html);
    }

    private void say(HttpExchange ex) throws Exception {
        byte[] raw = ex.getRequestBody().readNBytes(8192);
        JsonObject req;
        try { req = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Throwable t) { respondJson(ex, 400, err("peticion invalida")); return; }
        String tok  = req.has("t")    ? req.get("t").getAsString()    : "";
        String text = req.has("text") ? req.get("text").getAsString() : "";
        Tok info = tokens.get(tok);
        if (info == null || info.expires() < System.currentTimeMillis()) {
            respondJson(ex, 401, err("enlace caducado: usa /voz otra vez en el juego"));
            return;
        }
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) { respondJson(ex, 400, err("texto vacio")); return; }
        final String msg = clean.length() > 300 ? clean.substring(0, 300) : clean;
        final UUID who = info.player();
        respondJson(ex, 200, onMain(() -> deliver(who, msg)));
    }

    /** En el HILO PRINCIPAL: mete el texto en la conversacion (o la inicia con el NPC mas cercano). */
    private JsonObject deliver(UUID who, String msg) {
        Player p = Bukkit.getPlayer(who);
        if (p == null || !p.isOnline()) return err("no estas conectado al juego");
        if (!p.hasPermission("nemeles.npcai.use")) return err("sin permiso para hablar con NPCs");
        AiConfig c = cfg.get();
        if (mgr.isActive(who)) {
            String npc = mgr.activeNpcName(who);
            p.sendMessage(ChatColor.DARK_GRAY + "[voz] " + ChatColor.GRAY + "Tú: " + ChatColor.WHITE + msg);
            mgr.handle(p, msg);
            return ok("sent", npc == null ? "..." : npc);
        }
        double r = Math.max(2, c.proximityRadius);
        Entity best = null; NpcPersona bp = null; double bd = Double.MAX_VALUE;
        for (Entity ent : p.getNearbyEntities(r, r, r)) {
            String n = ent.getName();
            NpcPersona per = c.match(n == null ? null : ChatColor.stripColor(n));
            if (per == null) continue;
            double d = ent.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = ent; bp = per; }
        }
        if (best == null) return err("no estas hablando con nadie y no hay NPCs cerca: acercate a uno");
        mgr.start(p, bp, best.getUniqueId());
        return ok("started", bp.name);
    }

    private JsonObject onMain(Supplier<JsonObject> task) {
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { f.complete(task.get()); } catch (Throwable t) { f.complete(err("error interno")); }
            });
        } catch (Throwable t) { return err("el servidor esta arrancando o apagandose"); }
        try { return f.get(4, TimeUnit.SECONDS); } catch (Throwable t) { return err("el servidor no respondio a tiempo"); }
    }

    private static JsonObject ok(String status, String npc) {
        JsonObject o = new JsonObject();
        o.addProperty("status", status);
        o.addProperty("npc", npc);
        return o;
    }

    private static JsonObject err(String m) {
        JsonObject o = new JsonObject();
        o.addProperty("error", m);
        return o;
    }

    private void respond(HttpExchange ex, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void respondJson(HttpExchange ex, int code, JsonObject o) throws Exception {
        respond(ex, code, "application/json; charset=utf-8", o.toString());
    }
}
