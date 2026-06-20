package com.nemeles.npcai;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Orquesta las conversaciones: estado, proximidad, anti-spam, tono por wanted, IA, holograma y respuesta. */
public final class ConversationManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private AiConfig cfg;
    private OllamaClient ollama;
    private final HologramManager holo;
    private final Map<UUID, Conversation> active = new ConcurrentHashMap<>();
    private AffinityManager affinity;   // opcional; null si no esta disponible
    private MemoryManager memory;       // opcional; null si no esta disponible
    private BondManager bond;           // opcional; easter egg de alma gemela (null si no disponible)
    private InteractionManager interactions;   // opcional; acciones fisicas sigueme/quedate/vete (requiere Citizens)
    private ReplyListener replyListener;        // opcional; recibe las respuestas del NPC (pagina de voz / TTS)
    private WorldContext worldCtx;              // P7: contexto del mundo (alerta/territorio/wanted/heat) en el prompt
    private RetaliationManager retaliation;     // P3: para que INSULTAR sume a la racha (golpe=2, insulto=1)

    /** Recibe cada respuesta de NPC entregada a un jugador (la usa la pagina de voz para leerla en alto). */
    public interface ReplyListener { void onNpcReply(UUID player, NpcPersona persona, String text); }

    public ConversationManager(Plugin plugin, AiConfig cfg, OllamaClient ollama, HologramManager holo) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.ollama = ollama;
        this.holo = holo;
    }

    public void update(AiConfig cfg, OllamaClient ollama) { this.cfg = cfg; this.ollama = ollama; }

    public void setAffinity(AffinityManager affinity) { this.affinity = affinity; }

    public void setMemory(MemoryManager memory) { this.memory = memory; }

    public void setBond(BondManager bond) { this.bond = bond; }

    public void setInteractions(InteractionManager im) { this.interactions = im; }

    public void setReplyListener(ReplyListener l) { this.replyListener = l; }

    /** P7: inyecta el constructor de contexto del mundo (alerta de ciudad, territorio, wanted, heat). */
    public void setWorldContext(WorldContext wc) { this.worldCtx = wc; }

    /** P3: inyecta la retaliacion para que insultar al NPC sume a su racha de hostigamiento. */
    public void setRetaliation(RetaliationManager r) { this.retaliation = r; }

    /** P1: cierra la charla cuya entidad NPC sea esta (p.ej. al "morir" el NPC). No-op si nadie habla con el. */
    public void forceEndEntity(UUID entityId) {
        if (entityId == null) return;
        for (Map.Entry<UUID, Conversation> e : active.entrySet()) {
            if (entityId.equals(e.getValue().entityId)) { endConv(e.getKey()); return; }
        }
    }

    /** Deja constancia de una accion fisica REAL (p.ej. un regalo) para que la IA responda acorde. */
    public void noteAction(UUID player, UUID entityId, String note) {
        Conversation c = active.get(player);
        if (c != null && c.entityId.equals(entityId)) c.actionNote = note;
    }

    public boolean isActive(UUID u) { return active.containsKey(u); }

    /** Nombre visible del NPC con el que este jugador esta hablando ahora, o null. (Lo usa la pagina de voz.) */
    public String activeNpcName(UUID u) {
        Conversation c = active.get(u);
        return c == null ? null : c.persona.name;
    }

    /** ¿Hay alguna conversación activa apuntando a ESTA entidad NPC? (lo usa el paseo para pararlo al hablar). */
    public boolean isEntityBusy(UUID entityId) {
        if (entityId == null) return false;
        for (Conversation c : active.values()) if (entityId.equals(c.entityId)) return true;
        return false;
    }

    /** ¿Este jugador está charlando ahora mismo con ESTA entidad NPC? (lo usa la hostilidad para no golpear durante la charla). */
    public boolean isTalkingTo(UUID uid, UUID entityId) {
        Conversation c = active.get(uid);
        return c != null && c.entityId.equals(entityId);
    }

    public void start(Player p, NpcPersona persona, UUID entityId) {
        Conversation c = new Conversation(persona, entityId, System.currentTimeMillis());
        UUID uid = p.getUniqueId();
        active.put(uid, c);
        if (affinity != null) { try { affinity.load(persona.key, uid); } catch (Throwable ignored) { } }
        if (cfg.startHint != null && !cfg.startHint.isBlank()) send(p, cfg.startHint);
        Entity ent = Bukkit.getEntity(entityId);
        ejectSoon(p, entityId);

        // Saludo CASUAL generado por la IA (no un mensaje fijo): si os conocéis, lo nota; si no, se presenta solo.
        if (cfg.greetEnabled) {
            c.busy = true;
            holo.showThinking(ent, c.persona.color + c.persona.name);
            // Cargamos primero la memoria (BD local, rápida) y EN EL HILO PRINCIPAL pedimos el saludo.
            if (memory != null && memory.isEnabled()) {
                memory.loadThen(persona.key, uid, mem -> { c.memory = mem == null ? "" : mem; openGreeting(uid, c); });
            } else {
                openGreeting(uid, c);
            }
        } else if (memory != null && memory.isEnabled()) {
            memory.loadThen(persona.key, uid, mem -> c.memory = mem == null ? "" : mem);
        }
    }

    /** Pide a la IA un saludo breve y natural al iniciar la charla (usa la memoria si la hay). */
    private void openGreeting(UUID uid, Conversation c) {
        Player p = Bukkit.getPlayer(uid);
        if (p == null) { c.busy = false; return; }
        String system = buildSystem(c.persona, p.getName(), uid);
        String cue = c.memory != null && !c.memory.isBlank()
                ? "Acaba de acercarse " + p.getName() + ", a quien YA conoces (mira lo que recuerdas de el/ella)."
                  + " Recibele en 1 frase breve, en personaje y ACORDE A TU RELACION ACTUAL con el (calido si le aprecias,"
                  + " frio/hostil y cortante si le odias); si encaja, referencia algo que recuerdes."
                : "Acaba de acercarse " + p.getName() + ", a quien no conoces de nada."
                  + " Respondele en 1 frase breve, en personaje y ACORDE A TU RELACION ACTUAL con el (si no le conoces y no"
                  + " hay mal rollo, normal; si ya le tienes ojeriza, con frialdad o desprecio), sin presentarte con discurso.";
        List<ChatMsg> messages = new ArrayList<>();
        messages.add(new ChatMsg("system", system));
        messages.add(new ChatMsg("user", cue));
        String model = (c.persona.model != null && !c.persona.model.isBlank()) ? c.persona.model : cfg.model;
        ollama.chat(model, messages).whenComplete((reply, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> deliverGreeting(uid, reply)));
    }

    private void deliverGreeting(UUID uid, String reply) {
        Conversation c = active.get(uid);
        if (c == null) return;
        Player p = Bukkit.getPlayer(uid);
        if (p == null) { c.busy = false; return; }
        c.busy = false;
        c.opened = true;
        String clean = sanitize(reply, c.persona.name);
        if (clean == null || clean.isBlank()) {
            // Fallback si Ollama no responde: usa el saludo fijo del persona (o algo genérico).
            clean = (c.persona.greeting != null && !c.persona.greeting.isBlank())
                    ? c.persona.greeting : "Eh... ¿qué quieres?";
        }
        c.history.addLast(new ChatMsg("assistant", clean));
        trim(c);
        send(p, cfg.replyFormat.replace("{color}", c.persona.color).replace("{npc}", c.persona.name).replace("{text}", clean));
        Entity ent = Bukkit.getEntity(c.entityId);
        holo.show(ent, holoLine(c.persona, clean), cfg.holoSeconds);
        playVoice(ent, c.persona, clean.length());
        animateTalk(ent, p, clean.length());
        if (replyListener != null) { try { replyListener.onNpcReply(uid, c.persona, clean); } catch (Throwable ignored) { } }
    }

    /** Si pese a todo el jugador acaba montado en el NPC (Citizens controllable, etc.), lo baja. */
    private void ejectSoon(Player p, UUID entityId) {
        for (int delay : new int[]{1, 2, 4, 8}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isInsideVehicle()) {
                    Entity v = p.getVehicle();
                    if (v != null && entityId.equals(v.getUniqueId())) p.leaveVehicle();
                }
            }, delay);
        }
    }

    public void end(Player p) { endConv(p.getUniqueId()); }
    public void end(UUID u) { endConv(u); }

    private void endConv(UUID uid) {
        Conversation c = active.remove(uid);
        if (c != null) {
            holo.hide(c.entityId);
            rememberConversation(uid, c);
        }
    }

    /**
     * Al terminar la charla, pide a la IA un resumen breve (notas) que el NPC GUARDA de este jugador,
     * fusionando lo que ya recordaba con lo nuevo. Async y a prueba de fallos. Solo si hubo charla real.
     */
    private void rememberConversation(UUID uid, Conversation c) {
        if (memory == null || !memory.isEnabled() || c.userTurns < cfg.memMinTurns) return;
        Player p = Bukkit.getPlayer(uid);
        String playerName = p != null ? p.getName() : "ese ciudadano";
        // Snapshot del transcript EN EL HILO PRINCIPAL (history se muta solo aquí).
        StringBuilder tr = new StringBuilder();
        for (ChatMsg m : c.history) {
            if (m.role().equals("user")) tr.append(playerName).append(": ").append(m.content()).append("\n");
            else if (m.role().equals("assistant")) tr.append(c.persona.name).append(": ").append(m.content()).append("\n");
        }
        String prev = c.memory == null ? "" : c.memory;
        String npcName = c.persona.name;
        int maxChars = cfg.memMaxChars;
        String key = c.persona.key;
        String model = (c.persona.model != null && !c.persona.model.isBlank()) ? c.persona.model : cfg.model;

        List<ChatMsg> messages = new ArrayList<>();
        messages.add(new ChatMsg("system",
                "Eres un sistema que mantiene NOTAS breves que el personaje " + npcName + " recuerda sobre " + playerName + "."
              + " Devuelve SOLO 2-4 notas muy cortas (hechos concretos: como se llama o se hace llamar, a que se dedica,"
              + " que pidio o conto, si quedo a deber algo, el tono del trato). En espanol, sin markdown ni listas con guiones,"
              + " separadas por '; ', maximo " + maxChars + " caracteres en total. Nada de inventar."));
        messages.add(new ChatMsg("user",
                "NOTAS PREVIAS: " + (prev.isBlank() ? "(ninguna)" : prev)
              + "\n\nNUEVA CONVERSACION:\n" + tr + "\nActualiza y devuelve SOLO las notas combinadas."));
        ollama.chat(model, messages).whenComplete((reply, err) -> {
            String notes = sanitize(reply, npcName);
            if (notes == null || notes.isBlank()) return;
            if (notes.length() > maxChars) notes = notes.substring(0, maxChars).trim();
            final String fn = notes;
            try { memory.save(key, uid, fn); } catch (Throwable ignored) { }
        });
    }

    /** Procesa un mensaje del jugador. DEBE llamarse en el hilo principal. */
    public void handle(Player p, String text) {
        Conversation c = active.get(p.getUniqueId());
        if (c == null) return;
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;

        if (cfg.endWords.contains(t.toLowerCase(Locale.ROOT))) {
            send(p, "&7(" + c.persona.name + " asiente y se despide)");
            endConv(p.getUniqueId());
            return;
        }
        // Alma gemela (Luna): las FRASES SECRETAS cuentan AUNQUE este "ocupada" o en cooldown (si no, al
        // escribirlas rapido se perdian). Determinista, hilo principal; el bond manda sus propios mensajes.
        if (bond != null) {
            try {
                int affNow = (affinity != null) ? affinity.get(c.persona.key, p.getUniqueId()) : 0;
                bond.onMessage(p, c.persona.key, c.persona.name, AiConfig.normalize(t), affNow, isNight(p));
            } catch (Throwable ignored) { }
        }
        long now = System.currentTimeMillis();
        if (c.busy) { send(p, "&7(" + c.persona.name + " todavía está respondiendo...)"); return; }
        if (now < c.cooldownUntil) return;

        Entity ent = Bukkit.getEntity(c.entityId);
        if (ent == null || !ent.getWorld().equals(p.getWorld())
                || ent.getLocation().distanceSquared(p.getLocation()) > cfg.proximityRadius * cfg.proximityRadius) {
            send(p, "&7(te has alejado de " + c.persona.name + ")");
            endConv(p.getUniqueId());
            return;
        }

        if (t.length() > cfg.maxInputChars) t = t.substring(0, cfg.maxInputChars);
        // Puente de MISIONES: si es un NPC dador de trabajos y el jugador acepta, dispara el sistema de contratos.
        if (cfg.missionsEnabled && c.persona.missionGiver && isAcceptWord(t)) tryGiveMission(p, c.persona);
        c.busy = true;
        c.lastActivity = now;
        c.cooldownUntil = now + cfg.cooldownMs;
        c.history.addLast(new ChatMsg("user", t));
        c.userTurns++;
        // Afinidad: hablar suma; insultar resta fuerte (puede volver hostil al NPC); ser amable suma algo mas.
        if (affinity != null) {
            try {
                int delta = 1;
                String norm = AiConfig.normalize(t);
                if (containsAny(norm, cfg.insultWords)) {
                    delta -= cfg.hostInsultDrop;
                    // P3: insultar tambien suma a la racha (suave, +1). Asi "no se deja hostigar" tampoco de palabra.
                    if (retaliation != null && ent != null) {
                        try { retaliation.onHarassed(p, ent, c.persona, false); } catch (Throwable ignored) { }
                    }
                } else if (containsAny(norm, cfg.kindWords)) delta += 1;
                affinity.bump(c.persona.key, p.getUniqueId(), delta);
            } catch (Throwable ignored) { }
        }
        // Acciones fisicas: sigueme / quedate / vete (deterministas; dejan una nota de escena para la IA).
        if (interactions != null) {
            try { interactions.onChat(p, c, AiConfig.normalize(t)); } catch (Throwable ignored) { }
        }
        trim(c);

        c.retriedLang = false;
        send(p, cfg.thinkingMsg.replace("{npc}", c.persona.name));
        holo.showThinking(ent, c.persona.color + c.persona.name);
        ask(p.getUniqueId(), c, false);
    }

    /** Construye los mensajes y pide la respuesta a la IA. retry=true refuerza el espanol tras una respuesta en otro idioma. */
    private void ask(UUID uid, Conversation c, boolean retry) {
        Player p = Bukkit.getPlayer(uid);
        if (p == null) { c.busy = false; return; }
        String system = buildSystem(c.persona, p.getName(), uid);
        // Nota de escena: una accion fisica REAL acaba de ocurrir (seguir/regalo/echar); se inyecta UNA vez.
        if (c.actionNote != null && !c.actionNote.isBlank()) {
            system += "\nNOTA DE ESCENA (esto acaba de pasar DE VERDAD en el juego): " + c.actionNote
                    + " Responde de forma coherente con esto.";
            c.actionNote = null;
        }
        if (retry) {
            system += " AVISO IMPORTANTE: tu respuesta anterior uso un idioma o alfabeto equivocado."
                    + " Responde de NUEVO, EXCLUSIVAMENTE en espanol y usando solo el alfabeto latino,"
                    + " sin un solo caracter chino, japones ni coreano.";
        }
        List<ChatMsg> messages = new ArrayList<>();
        messages.add(new ChatMsg("system", system));
        messages.addAll(c.history);
        String model = (c.persona.model != null && !c.persona.model.isBlank()) ? c.persona.model : cfg.model;
        ollama.chat(model, messages).whenComplete((reply, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> deliver(uid, reply)));
    }

    private void deliver(UUID uid, String reply) {
        Conversation c = active.get(uid);
        if (c == null) return;
        Player p = Bukkit.getPlayer(uid);
        if (p == null) { c.busy = false; return; }
        // Salvaguarda de idioma: si la IA respondio en otro alfabeto (chino/japones/coreano, deriva tipica de Qwen),
        // reintenta UNA vez reforzando el espanol antes de rendirse.
        if (reply != null && isWrongLanguage(reply) && !c.retriedLang) {
            c.retriedLang = true;
            ask(uid, c, true);
            return;   // seguimos "busy"; la respuesta del reintento volvera a entrar aqui
        }
        c.busy = false;
        String clean = sanitize(reply, c.persona.name);
        if (clean == null || clean.isBlank()) {
            send(p, cfg.unavailableMsg.replace("{npc}", c.persona.name));
            holo.show(Bukkit.getEntity(c.entityId), c.persona.color + c.persona.name + "\n&7...", 5);
            return;
        }
        c.history.addLast(new ChatMsg("assistant", clean));
        trim(c);
        send(p, cfg.replyFormat.replace("{color}", c.persona.color).replace("{npc}", c.persona.name).replace("{text}", clean));
        Entity ent = Bukkit.getEntity(c.entityId);
        holo.show(ent, holoLine(c.persona, clean), cfg.holoSeconds);
        playVoice(ent, c.persona, clean.length());
        animateTalk(ent, p, clean.length());
        if (replyListener != null) { try { replyListener.onNpcReply(uid, c.persona, clean); } catch (Throwable ignored) { } }
    }

    /** Da vida al NPC al hablar: mira al jugador, gesticula (mueve el brazo) y suelta una partícula. */
    private void animateTalk(Entity npc, Player target, int textLen) {
        if (!cfg.fxEnabled || npc == null) return;
        if (cfg.fxLook && target != null) {
            try {
                org.bukkit.Location l = target.getEyeLocation();
                npc.lookAt(l.getX(), l.getY(), l.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
            } catch (Throwable ignored) { }
        }
        if (cfg.fxGestures && npc instanceof Player pl) {
            int swings = Math.max(1, Math.min(4, textLen / 14 + 1));
            for (int i = 0; i < swings; i++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> { try { pl.swingMainHand(); } catch (Throwable ignored) { } }, i * 6L);
            }
        }
        if (cfg.fxParticle != null && !cfg.fxParticle.equalsIgnoreCase("NONE")) {
            try {
                org.bukkit.Particle part = org.bukkit.Particle.valueOf(cfg.fxParticle.toUpperCase(Locale.ROOT));
                org.bukkit.World w = npc.getWorld();
                if (w != null) w.spawnParticle(part, npc.getLocation().add(0, npc.getHeight() + 0.35, 0), 4, 0.25, 0.15, 0.25, 0.8);
            } catch (Throwable ignored) { }
        }
    }

    /** "Voz" del NPC: una ráfaga de blips vanilla (crossplay) con el tono del personaje. */
    private void playVoice(Entity npc, NpcPersona persona, int textLen) {
        if (!cfg.voiceEnabled || npc == null) return;
        final org.bukkit.World w = npc.getWorld();
        if (w == null) return;
        final org.bukkit.Location loc = npc.getLocation().add(0, npc.getHeight() * 0.8, 0);
        int blips = Math.max(3, Math.min(cfg.voiceMaxBlips, textLen / 12 + 2));
        final double basePitch = persona.voicePitch > 0 ? persona.voicePitch : cfg.voiceBasePitch;
        final String sound = (persona.voiceSound != null && !persona.voiceSound.isBlank()) ? persona.voiceSound : cfg.voiceSound;
        final float vol = (float) cfg.voiceVolume;
        for (int i = 0; i < blips; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    float pitch = (float) Math.max(0.5, Math.min(2.0, basePitch + (Math.random() - 0.5) * 0.2));
                    w.playSound(loc, sound, vol, pitch);
                } catch (Throwable ignored) { }
            }, i * 2L);
        }
    }

    public void tickExpire() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Conversation>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Conversation> e = it.next();
            if (now - e.getValue().lastActivity > cfg.convTimeoutMs) {
                holo.hide(e.getValue().entityId);
                Conversation conv = e.getValue();
                UUID who = e.getKey();
                it.remove();
                rememberConversation(who, conv);
                Player p = Bukkit.getPlayer(who);
                if (p != null) send(p, "&7(" + conv.persona.name + " se cansa de esperar y se da la vuelta)");
            }
        }
    }

    private void trim(Conversation c) { while (c.history.size() > cfg.historySize) c.history.removeFirst(); }

    private String holoLine(NpcPersona persona, String text) {
        // Solo el texto: el nombre ya lo muestra el cartel del NPC (evita verlo duplicado).
        return persona.color + text;
    }

    private String buildSystem(NpcPersona persona, String playerName, UUID uid) {
        StringBuilder sb = new StringBuilder();
        if (cfg.rules != null && !cfg.rules.isBlank()) sb.append(cfg.rules).append("\n\n");
        sb.append(persona.persona);
        if (cfg.world != null && !cfg.world.isBlank()) sb.append(" ").append(cfg.world);
        String heat = heatLine(uid);
        if (!heat.isBlank()) sb.append(" ").append(heat);
        // P7: contexto del mundo AHORA (alerta de ciudad, territorio+dueno, wanted, calor, zona). A prueba de fallos.
        if (worldCtx != null) {
            try {
                Player wp = Bukkit.getPlayer(uid);
                String wctx = worldCtx.build(wp, uid, persona.missionGiver);
                if (wctx != null && !wctx.isBlank()) sb.append(wctx);
            } catch (Throwable ignored) { }
        }
        if (affinity != null) {
            try {
                String aff = affinity.descriptor(persona.key, uid);
                if (aff != null && !aff.isBlank()) {
                    sb.append("\nTU RELACION CON ").append(playerName)
                      .append(" AHORA MISMO (esto MANDA sobre tu tono y tu actitud, por encima de tu caracter general): ")
                      .append(aff);
                }
            } catch (Throwable ignored) { }
        }
        // Alma gemela: si este jugador es su companero (o si ya tiene dueno), cambia como le trata.
        if (bond != null) {
            try {
                String b = bond.systemContext(persona.key, uid);
                if (b != null && !b.isBlank()) sb.append(b);
            } catch (Throwable ignored) { }
        }
        // Memoria de largo plazo: lo que ESTE NPC recuerda de ESTE jugador de antes.
        if (memory != null && memory.isEnabled()) {
            try {
                String mem = memory.get(persona.key, uid);
                if (mem != null && !mem.isBlank()) {
                    sb.append("\nLo que ya recuerdas de ").append(playerName).append(" de antes: ").append(mem)
                      .append(" (No lo recites de golpe; usalo con naturalidad solo si encaja.)");
                }
            } catch (Throwable ignored) { }
        }
        sb.append("\nEstas hablando directamente con un ciudadano llamado ").append(playerName)
                .append(". Llamale por su nombre cuando encaje.");
        return sb.toString();
    }

    /** Ajusta el tono según el nivel de búsqueda (estrellas) del jugador, vía WantedService. */
    private String heatLine(UUID uid) {
        if (!cfg.heatEnabled) return "";
        int stars;
        try { stars = com.nemeles.core.api.NemelesApi.wanted().getStars(uid); }
        catch (Throwable ignored) { return ""; }   // modulo policia no cargado
        if (stars >= 5) return cfg.heatTier5;
        if (stars >= 3) return cfg.heatTier34;
        if (stars >= 1) return cfg.heatTier12;
        return "";
    }

    private String sanitize(String reply, String npcName) {
        if (reply == null) return null;
        String s = reply.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("«") && s.endsWith("»")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        String pn = npcName.toLowerCase(Locale.ROOT) + ":";
        if (s.toLowerCase(Locale.ROOT).startsWith(pn)) s = s.substring(pn.length()).trim();
        s = s.replace("\r", " ").replace("\n", " ").replaceAll(" +", " ").trim();
        s = s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
        // quita cualquier caracter chino/japones/coreano residual que se haya colado
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (!isCjkChar(ch)) b.append(ch); }
        s = b.toString().replaceAll(" +", " ").trim();
        if (s.length() > 500) s = s.substring(0, 500).trim() + "...";
        return s;
    }

    /** true si una proporcion apreciable del texto son caracteres CJK (chino/japones/coreano). */
    private boolean isWrongLanguage(String s) {
        if (s == null) return false;
        int cjk = 0, visible = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            visible++;
            if (isCjkChar(ch)) cjk++;
        }
        return visible > 0 && cjk >= 2 && cjk * 5 >= visible;   // >=2 chars y >=20% del texto visible
    }

    /** Rango Unicode de caracteres chinos, japoneses (kana) y coreanos (hangul) + puntuacion/anchos CJK. */
    private static boolean isCjkChar(char ch) {
        return (ch >= 0x1100 && ch <= 0x11FF)   // Hangul Jamo
            || (ch >= 0x3000 && ch <= 0x30FF)   // Puntuacion CJK + Hiragana + Katakana
            || (ch >= 0x3130 && ch <= 0x318F)   // Hangul Compatibility Jamo
            || (ch >= 0x3400 && ch <= 0x9FFF)   // CJK Ext A + Ideogramas unificados
            || (ch >= 0xAC00 && ch <= 0xD7AF)   // Silabas Hangul
            || (ch >= 0xF900 && ch <= 0xFAFF)   // Ideogramas de compatibilidad CJK
            || (ch >= 0xFF00 && ch <= 0xFFEF);  // Formas de ancho medio/completo
    }

    /** ¿El mensaje del jugador es una aceptación del trabajo? (token o frase completa, sin acentos). */
    private boolean isAcceptWord(String t) {
        if (t == null || cfg.missionAcceptWords.isEmpty()) return false;
        String norm = AiConfig.normalize(t);
        if (cfg.missionAcceptWords.contains(norm)) return true;
        for (String tok : norm.split("[^a-z0-9]+")) {
            if (!tok.isEmpty() && cfg.missionAcceptWords.contains(tok)) return true;
        }
        return false;
    }

    /** ¿El texto (normalizado) contiene alguna de estas palabras/frases? */
    private boolean containsAny(String norm, java.util.Set<String> words) {
        if (norm == null || words == null || words.isEmpty()) return false;
        for (String w : words) { if (!w.isEmpty() && norm.contains(w)) return true; }
        return false;
    }

    /**
     * Dispara el comando del módulo de contratos para asignar un trabajo, MODULADO por la afinidad:
     * si el NPC te odia (hostil) se niega; si confía poco, paga menos; si te aprecia, paga más.
     * El multiplicador viaja por metadata (los jugadores no pueden falsearla) y NemelesContracts lo lee.
     * No-op si el módulo de contratos no está.
     */
    private void tryGiveMission(Player p, NpcPersona persona) {
        String cmd = cfg.missionCommand;
        if (cmd == null || cmd.isBlank()) return;
        String base = cmd.split(" ")[0];
        if (Bukkit.getPluginCommand(base) == null) return;   // contratos no instalado: el NPC solo habla
        int aff = 0;
        if (affinity != null) { try { aff = affinity.get(persona.key, p.getUniqueId()); } catch (Throwable ignored) { } }
        // NUNCA bloquea su historia: aunque le caigas fatal, te abre OTRO camino (mas turbio/arriesgado), nunca
        // un "no" seco. La afinidad cambia el TONO, el PAGO y el TIPO de encargo (path), no el acceso.
        boolean hostilePath = affinity != null && aff <= cfg.hostHostileThreshold;
        double mult = rewardMult(aff);
        try {
            p.setMetadata("nemeles_contract_mult", new org.bukkit.metadata.FixedMetadataValue(plugin, mult));
            p.setMetadata("nemeles_contract_path", new org.bukkit.metadata.FixedMetadataValue(plugin, hostilePath ? "turbio" : "normal"));
        } catch (Throwable ignored) { }
        if (hostilePath) send(p, cfg.missionHostilePath.replace("{npc}", persona.name));   // trato sucio, a regañadientes
        try { Bukkit.dispatchCommand(p, cmd); } catch (Throwable ignored) { }   // síncrono: contratos lee la metadata aquí
        try { p.removeMetadata("nemeles_contract_mult", plugin); p.removeMetadata("nemeles_contract_path", plugin); } catch (Throwable ignored) { }
    }

    /** Multiplicador de recompensa de misión según afinidad. Cada tramo es un "camino" distinto. */
    private double rewardMult(int aff) {
        if (aff >= 40) return 1.25;                       // amigo de confianza: los mejores trabajos
        if (aff >= 15) return 1.10;                       // te aprecia: buen trabajo
        if (aff <= cfg.hostHostileThreshold) return 1.15; // te ODIA: camino TURBIO, sucio y arriesgado, pero paga
        if (aff <= cfg.hostWaryThreshold) return 0.80;    // poca confianza: trabajo de prueba, paga menos
        return 1.0;
    }

    private void send(Player p, String legacyAmp) {
        p.sendMessage(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', legacyAmp)));
    }

    /** ¿Es de noche en el mundo del jugador? (ticks 13000..23000 = noche en Minecraft). */
    private boolean isNight(Player p) {
        try { long t = p.getWorld().getTime(); return t >= 13000 && t <= 23000; }
        catch (Throwable ignored) { return false; }
    }
}
