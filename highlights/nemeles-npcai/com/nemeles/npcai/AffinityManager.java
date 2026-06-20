package com.nemeles.npcai;

import com.nemeles.npcai.db.AffinityDao;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Afinidad por (persona, jugador): la ciudad te RECUERDA. Sube al conversar; cambia el tono que se
 * inyecta al prompt del NPC. Persiste en BD. Todo es opcional y a prueba de fallos (el que lo usa
 * envuelve las llamadas en try/catch); si la BD falla, la afinidad se queda neutra.
 */
public final class AffinityManager {

    private final Plugin plugin;
    private final AffinityDao dao;
    private final Executor io;
    private final boolean enabled;
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    public AffinityManager(Plugin plugin, AffinityDao dao, Executor io, boolean enabled) {
        this.plugin = plugin;
        this.dao = dao;
        this.io = io;
        this.enabled = enabled;
    }

    private static String key(String persona, UUID id) { return persona + "|" + id; }
    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }

    /** Precarga la afinidad de (persona, jugador) en caché (async). */
    public void load(String persona, UUID id) {
        if (!enabled) return;
        String k = key(persona, id);
        if (cache.containsKey(k)) return;
        io.execute(() -> {
            try { int v = dao.load(persona, id); cache.putIfAbsent(k, v); }
            catch (Exception e) { plugin.getLogger().warning("affinity load: " + e.getMessage()); }
        });
    }

    public int get(String persona, UUID id) { return cache.getOrDefault(key(persona, id), 0); }

    /** Suma afinidad. Si no está en caché, lee-modifica-guarda en el hilo de BD (no pisa el valor real). */
    public void bump(String persona, UUID id, int delta) {
        if (!enabled) return;
        String k = key(persona, id);
        Integer cached = cache.get(k);
        if (cached != null) {
            int nv = clamp(cached + delta);
            cache.put(k, nv);
            io.execute(() -> { try { dao.upsert(persona, id, nv); } catch (Exception ignored) { } });
        } else {
            io.execute(() -> {
                try {
                    int nv = clamp(dao.load(persona, id) + delta);
                    cache.put(k, nv);
                    dao.upsert(persona, id, nv);
                } catch (Exception ignored) { }
            });
        }
    }

    /** Fija la afinidad (NPC, jugador) a un valor absoluto -100..100 (usado al "morir" el NPC). */
    public void set(String persona, UUID id, int value) {
        if (!enabled) return;
        int v = clamp(value);
        int cur = get(persona, id);
        bump(persona, id, v - cur);   // reutiliza el camino probado de bump (cache + BD)
    }

    /**
     * Frase de tono según la afinidad, para inyectar en el prompt del NPC ("" si neutra/desactivada).
     * Tramos finos: cuanto más extremo el vínculo, más TAJANTE la directiva, para que hasta un modelo pequeño
     * cambie de verdad su actitud (un -98 NO debe hablarte "normal"; un +90 te trata como de los suyos).
     */
    public String descriptor(String persona, UUID id) {
        if (!enabled) return "";
        int v = get(persona, id);
        // ── odio / desprecio ──
        if (v <= -85)
            return "ODIAS a este ciudadano y no lo disimulas: te resulta despreciable. Le hablas con hostilidad ABIERTA,"
                 + " cortante y agresiva; le dejas claro que no es bienvenido y que se largue. NO le ayudas, NO le sigues"
                 + " la conversacion con amabilidad y NO finges cordialidad: cada respuesta rezuma rechazo y desprecio.";
        if (v <= -50)
            return "Consideras a este ciudadano un ENEMIGO. Le hablas con desprecio y rabia contenida, a la defensiva,"
                 + " con respuestas secas y de mala gana; no colaboras de buen grado y le adviertes que se aleje.";
        if (v <= -20)
            return "Este ciudadano te genera DESCONFIANZA y antipatia: le hablas seco, cortante y con recelo, sin una"
                 + " pizca de calidez, con respuestas breves y a regañadientes.";
        if (v <= -8)
            return "Este ciudadano no te termina de caer bien; mantienes la distancia y un tono frio y reservado.";
        // ── aprecio / cariño ──
        if (v >= 85)
            return "Quieres a este ciudadano como a alguien de los TUYOS: le hablas con cariño sincero, confianza total"
                 + " y lealtad, te alegras de verle y te abres con el de verdad.";
        if (v >= 40)
            return "Aprecias a este ciudadano: le hablas con confianza, simpatia y complicidad, de buen humor.";
        if (v >= 15)
            return "Este ciudadano te cae bien; eres algo mas calido, cercano y abierto con el.";
        return "";
    }
}
