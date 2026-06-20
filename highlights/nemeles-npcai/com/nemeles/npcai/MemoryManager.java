package com.nemeles.npcai;

import com.nemeles.npcai.db.MemoryDao;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Memoria de largo plazo por (persona, jugador): notas breves que el NPC recuerda de ti entre sesiones.
 * Se precarga al empezar a charlar y se inyecta en el prompt; se actualiza (resumen de la IA) al terminar.
 * Persiste en BD. Todo es opcional y a prueba de fallos (quien lo usa lo envuelve en try/catch).
 */
public final class MemoryManager {

    private final Plugin plugin;
    private final MemoryDao dao;
    private final Executor io;
    private final boolean enabled;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public MemoryManager(Plugin plugin, MemoryDao dao, Executor io, boolean enabled) {
        this.plugin = plugin;
        this.dao = dao;
        this.io = io;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    private static String key(String persona, UUID id) { return persona + "|" + id; }

    /** Precarga las notas de (persona, jugador) en caché (async). */
    public void load(String persona, UUID id) {
        if (!enabled) return;
        String k = key(persona, id);
        if (cache.containsKey(k)) return;
        io.execute(() -> {
            try { String v = dao.load(persona, id); cache.putIfAbsent(k, v == null ? "" : v); }
            catch (Exception e) { plugin.getLogger().warning("memory load: " + e.getMessage()); }
        });
    }

    /** Notas cacheadas (o "" si no hay nada cargado). Lectura sync para inyectar en el prompt. */
    public String get(String persona, UUID id) { return cache.getOrDefault(key(persona, id), ""); }

    /** Carga las notas (de caché o BD) y entrega el resultado en el HILO PRINCIPAL al callback. */
    public void loadThen(String persona, UUID id, Consumer<String> mainThreadCallback) {
        if (!enabled) { mainThreadCallback.accept(""); return; }
        String k = key(persona, id);
        String cached = cache.get(k);
        if (cached != null) { mainThreadCallback.accept(cached); return; }
        io.execute(() -> {
            String v = "";
            try { v = dao.load(persona, id); } catch (Exception ignored) { }
            cache.putIfAbsent(k, v == null ? "" : v);
            final String fv = cache.getOrDefault(k, "");
            Bukkit.getScheduler().runTask(plugin, () -> mainThreadCallback.accept(fv));
        });
    }

    /** Guarda/actualiza las notas (cache + escritura async). */
    public void save(String persona, UUID id, String memory) {
        if (!enabled || memory == null) return;
        String k = key(persona, id);
        cache.put(k, memory);
        io.execute(() -> { try { dao.upsert(persona, id, memory); } catch (Exception ignored) { } });
    }
}
