package com.nemeles.npcai;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Carga inmutable de la config de NemelesNpcAI. */
public final class AiConfig {

    public final String ollamaUrl, model, keepAlive, thinkingMsg, unavailableMsg, replyFormat, startHint, rules, world;
    public final double temperature, proximityRadius, topP, repeatPenalty;
    public final int repeatLastN;
    public final int maxTokens, connectTimeout, requestTimeout, historySize, maxInputChars;
    public final long convTimeoutMs, cooldownMs;
    public final Set<String> endWords;
    public final Map<String, NpcPersona> personas;
    public final Map<String, java.util.Set<org.bukkit.Material>> likedFoods;   // gustos de comida por NPC (vacio = le gusta todo)

    public final boolean missionsEnabled;
    public final String missionCommand;
    public final Set<String> missionAcceptWords;
    public final String missionHostileRefuse;
    public final String missionHostilePath;   // camino TURBIO: el NPC que te odia te abre un trato sucio, no te niega

    // Hostilidad por afinidad
    public final boolean hostilityEnabled;
    public final int hostHostileThreshold, hostWaryThreshold, hostAttackDrop, hostInsultDrop;
    public final double hostScanRadius, hostWarnRadius, hostAttackRadius, hostDamage;
    public final long hostWarnCooldownMs, hostAttackCooldownMs;
    public final String hostWarnMessage, hostWarnSound, hostAttackSound, hostParticle;
    public final Set<String> insultWords, kindWords;

    // P3 — escalada / racha de hostigamiento / persecucion
    public final boolean hostEscalate;
    public final double hostDamageMax, hostEscalateDamageStep, hostEscalateStreakDamage;
    public final long hostAttackCooldownMinMs, hostEscalateCooldownStepMs, hostEscalateStreakCooldownMs;
    public final int hostStreakHostile;
    public final long hostStreakDecayMs;
    public final boolean hostChaseEnabled;
    public final long hostChaseMs;
    public final double hostChaseSpeed, hostChaseReach;
    public final long hostEngageMs;   // ventana "en combate" tras un golpe fuerte: solo dentro de ella el NPC ataca por su cuenta

    // P2 — armadura defensiva al primer golpe
    public final boolean armorEnabled;
    public final int armorProtection, armorUnbreaking, armorWornPercent;
    public final String armorHelmet, armorChestplate, armorLeggings, armorBoots;
    public final Set<String> armorKeys;   // vacio = todos los protagonistas

    // P4 — ataque a distancia (defaults globales: FX + valores por persona)
    public final String rangedShotSound, rangedImpactSound, rangedTracerParticle, rangedMuzzleParticle;
    public final double rangedDefAccuracy, rangedDefDamage, rangedDefRange;
    public final long rangedDefCooldownMs;

    // P7 — contexto del mundo en el prompt
    public final boolean worldCtxEnabled, worldCtxAlert, worldCtxTerritory, worldCtxWanted, worldCtxHeat, worldCtxZone;
    public final String worldCtxMissionHint;

    public final boolean heatEnabled, holoEnabled, voiceEnabled;
    public final boolean fxEnabled, fxLook, fxGestures;
    public final String fxParticle;
    public final int holoSeconds, voiceMaxBlips;
    public final double holoHeight, voiceVolume, voiceBasePitch;
    public final String heatTier12, heatTier34, heatTier5, voiceSound;

    // Vida: los NPCs pasean por el mundo como vecinos (requiere Citizens).
    public final boolean lifeEnabled, pauseOnTalk;
    public final double wanderRadius, maxStray, wanderChance, lifeSpeed;
    public final long lifeTickTicks;

    // P1 — NPCs danables: vida fija, muerte limpia y respawn en su "casa".
    public final boolean npcDamageEnabled, npcResetAffinityOnDeath;
    public final double npcHealth;
    public final long npcRespawnSeconds;
    public final double npcAttackHealthPercent;   // atacan solo cuando su vida baja a este % (perdonan el 1er golpe)
    public final long npcRegenDelayMs;            // tras este tiempo sin recibir golpes...
    public final double npcRegenRadius;           // ...y sin jugador a esta distancia, se curan TODA la vida y se calman

    // Memoria de largo plazo: el NPC recuerda de qué habló contigo entre sesiones.
    public final boolean memEnabled;
    public final int memMaxChars, memMinTurns;
    public final boolean greetEnabled;       // saludo casual generado por IA al empezar (en vez del canned)

    // Easter egg: alma gemela (una sola companera fiel en todo el servidor para un NPC concreto).
    public final BondConfig bond;

    // Interacciones FISICAS con los NPCs (sigueme/quedate/vete, regalos de comida). Requiere Citizens.
    public final boolean itxEnabled;
    public final Set<String> followWords, stayWords, leaveWords;
    public final int followMinAffinity, leaveAnnoy, giftAffinity;
    public final double followSpeed;
    public final long followMaxMs;
    // P6 — feedback al pedir "sigueme". {npc}=nombre, {have}=afinidad actual, {need}=umbral.
    public final String followOkMsg, followLowMsg, followWaryMsg;

    private final Map<String, NpcPersona> lookup = new HashMap<>();   // normalizado (sin acentos): clave y nombre

    public AiConfig(FileConfiguration c) {
        ollamaUrl       = c.getString("ollama.url", "http://localhost:11434");
        model           = c.getString("ollama.model", "qwen2.5:7b");
        keepAlive       = c.getString("ollama.keep-alive", "30m");
        temperature     = c.getDouble("ollama.temperature", 0.9);
        topP            = c.getDouble("ollama.top-p", 0.92);
        repeatPenalty   = c.getDouble("ollama.repeat-penalty", 1.3);
        repeatLastN     = c.getInt("ollama.repeat-last-n", 256);
        maxTokens       = c.getInt("ollama.max-tokens", 160);
        connectTimeout  = c.getInt("ollama.connect-timeout-seconds", 5);
        requestTimeout  = c.getInt("ollama.request-timeout-seconds", 45);

        historySize     = Math.max(2, c.getInt("chat.history-size", 6));
        convTimeoutMs   = c.getInt("chat.conversation-timeout-seconds", 90) * 1000L;
        proximityRadius = c.getDouble("chat.proximity-radius", 7);
        cooldownMs      = c.getInt("chat.cooldown-seconds", 2) * 1000L;
        maxInputChars   = Math.max(20, c.getInt("chat.max-input-chars", 300));
        thinkingMsg     = c.getString("chat.thinking-message", "&7* {npc} esta pensando... *");
        unavailableMsg  = c.getString("chat.unavailable", "&7({npc} te mira perdido)");
        replyFormat     = c.getString("chat.reply-format", "{color}{npc}&r&f: {text}");
        startHint       = c.getString("chat.start-hint", "");
        rules           = c.getString("rules", "");
        world           = c.getString("world", "");

        heatEnabled = c.getBoolean("heat.enabled", true);
        heatTier12  = c.getString("heat.tier1-2", "");
        heatTier34  = c.getString("heat.tier3-4", "");
        heatTier5   = c.getString("heat.tier5", "");
        holoEnabled = c.getBoolean("hologram.enabled", true);
        holoSeconds = c.getInt("hologram.seconds", 12);
        holoHeight  = c.getDouble("hologram.height-offset", 0.4);

        voiceEnabled   = c.getBoolean("voice.enabled", true);
        voiceSound     = c.getString("voice.sound", "entity.villager.ambient");
        voiceVolume    = c.getDouble("voice.volume", 1.0);
        voiceBasePitch = c.getDouble("voice.base-pitch", 1.0);
        voiceMaxBlips  = c.getInt("voice.max-blips", 9);

        fxEnabled   = c.getBoolean("effects.enabled", true);
        fxLook      = c.getBoolean("effects.look-at-player", true);
        fxGestures  = c.getBoolean("effects.gestures", true);
        fxParticle  = c.getString("effects.particle", "NOTE");

        lifeEnabled   = c.getBoolean("life.enabled", true);
        wanderRadius  = Math.max(2, c.getDouble("life.wander-radius", 14));
        maxStray      = Math.max(wanderRadius, c.getDouble("life.max-stray", 28));
        wanderChance  = Math.max(0.0, Math.min(1.0, c.getDouble("life.wander-chance", 0.35)));
        lifeSpeed     = Math.max(0.2, Math.min(1.5, c.getDouble("life.speed", 0.8)));
        lifeTickTicks = Math.max(20L, c.getInt("life.tick-seconds", 4) * 20L);
        pauseOnTalk   = c.getBoolean("life.pause-on-talk", true);

        npcDamageEnabled        = c.getBoolean("life.npc-damage", true);
        npcHealth               = Math.max(1.0, c.getDouble("life.npc-health", 50.0));
        npcRespawnSeconds       = Math.max(1, c.getInt("life.respawn-seconds", 20));
        npcResetAffinityOnDeath = c.getBoolean("life.reset-affinity-on-death", false);
        npcAttackHealthPercent  = clamp01(c.getDouble("life.attack-at-health-percent", 0.80));
        npcRegenDelayMs         = Math.max(1000L, c.getInt("life.regen-delay-seconds", 120) * 1000L);  // 2 min sin dano
        npcRegenRadius          = Math.max(4.0, c.getDouble("life.regen-player-radius", 18.0));  // (en desuso: el regen ya no mira distancia)

        worldCtxEnabled   = c.getBoolean("world-context.enabled", true);
        worldCtxAlert     = c.getBoolean("world-context.alert", true);
        worldCtxTerritory = c.getBoolean("world-context.territory", true);
        worldCtxWanted    = c.getBoolean("world-context.wanted", false);  // off: heatLine ya cubre el wanted
        worldCtxHeat      = c.getBoolean("world-context.heat", true);
        worldCtxZone      = c.getBoolean("world-context.zone", true);
        worldCtxMissionHint = c.getString("world-context.mission-hint",
                "Como das trabajo, TEN EN CUENTA este estado del mundo al ofrecerlo: si la ciudad esta en alerta alta, "
              + "el territorio en disputa o este tipo muy buscado, comentalo con naturalidad (que hoy es mal dia para mover "
              + "mercancia, que cobrara mas por el riesgo, o que mejor espere a que se enfrie la cosa). No inventes datos que no tengas.");

        memEnabled    = c.getBoolean("memory.enabled", true);
        memMaxChars   = Math.max(80, c.getInt("memory.max-chars", 300));
        memMinTurns   = Math.max(1, c.getInt("memory.min-turns", 1));
        greetEnabled  = c.getBoolean("chat.casual-greeting", true);

        Set<String> ew = new HashSet<>();
        for (String s : c.getStringList("chat.end-words")) ew.add(s.toLowerCase(Locale.ROOT).trim());
        endWords = ew;

        missionsEnabled = c.getBoolean("missions.enabled", true);
        missionCommand  = c.getString("missions.command", "contrato");
        Set<String> mw = new HashSet<>();
        for (String s : c.getStringList("missions.accept-words")) mw.add(normalize(s));
        missionAcceptWords = mw;
        missionHostileRefuse = c.getString("missions.hostile-refuse",
                "&c{npc} te escupe al suelo: no pienso darte trabajo. Ganate mi respeto primero.");
        missionHostilePath = c.getString("missions.hostile-path",
                "&8{npc} te mira con asco, pero el dinero es el dinero: &7\"No me caes bien, pero tengo algo SUCIO que nadie mas quiere tocar. Mas riesgo, mas paga. Tu sabras.\"");

        hostilityEnabled      = c.getBoolean("hostility.enabled", true);
        hostHostileThreshold  = c.getInt("hostility.hostile-threshold", -50);
        hostWaryThreshold     = c.getInt("hostility.wary-threshold", -20);
        hostScanRadius        = c.getDouble("hostility.scan-radius", 12);
        hostWarnRadius        = c.getDouble("hostility.warn-radius", 8);
        hostAttackRadius      = c.getDouble("hostility.attack-radius", 3.0);
        hostDamage            = c.getDouble("hostility.damage", 4.0);
        hostAttackCooldownMs  = c.getInt("hostility.attack-cooldown-seconds", 2) * 1000L;
        hostWarnCooldownMs    = c.getInt("hostility.warn-cooldown-seconds", 12) * 1000L;
        hostAttackDrop        = c.getInt("hostility.attack-affinity-drop", 30);
        hostInsultDrop        = c.getInt("hostility.insult-drop", 6);
        hostWarnMessage       = c.getString("hostility.warn-message",
                "&c{npc} te lanza una mirada de odio y te grita que te largues.");
        hostWarnSound         = c.getString("hostility.warn-sound", "entity.villager.no");
        hostAttackSound       = c.getString("hostility.attack-sound", "entity.player.attack.sweep");
        hostParticle          = c.getString("hostility.particle", "ANGRY_VILLAGER");
        Set<String> iw = new HashSet<>();
        for (String s : c.getStringList("hostility.insult-words")) iw.add(normalize(s));
        insultWords = iw;
        Set<String> kw = new HashSet<>();
        for (String s : c.getStringList("hostility.kind-words")) kw.add(normalize(s));
        kindWords = kw;

        // P3 — escalada / racha / persecucion
        hostEscalate                 = c.getBoolean("hostility.escalation.enabled", true);
        hostDamageMax                = c.getDouble("hostility.escalation.damage-max", 9.0);
        hostEscalateDamageStep       = c.getDouble("hostility.escalation.damage-per-25-affinity", 1.0);
        hostEscalateStreakDamage     = c.getDouble("hostility.escalation.damage-per-streak", 0.5);
        hostAttackCooldownMinMs      = Math.max(150, c.getInt("hostility.escalation.cooldown-min-ms", 600));
        hostEscalateCooldownStepMs   = Math.max(0, c.getInt("hostility.escalation.cooldown-cut-per-25-affinity-ms", 250));
        hostEscalateStreakCooldownMs = Math.max(0, c.getInt("hostility.escalation.cooldown-cut-per-streak-ms", 120));
        hostStreakHostile            = Math.max(1, c.getInt("hostility.harassment.streak-to-hostile", 3));
        hostStreakDecayMs            = Math.max(1000, c.getInt("hostility.harassment.streak-decay-seconds", 8) * 1000L);
        hostChaseEnabled             = c.getBoolean("hostility.chase.enabled", true);
        hostChaseMs                  = Math.max(1000, c.getInt("hostility.chase.seconds", 6) * 1000L);
        hostChaseSpeed               = Math.max(0.5, Math.min(1.8, c.getDouble("hostility.chase.speed", 1.25)));
        hostChaseReach               = Math.max(0.0, c.getDouble("hostility.chase.extra-reach", 1.0));
        // Combate por INCIDENTE: el NPC solo te ataca por su cuenta mientras este "en combate" (le pegaste hace poco).
        // Pasada esta ventana SIN que le vuelvas a pegar, se desengancha; al morir/respawnear/regenerar tambien se limpia.
        hostEngageMs                 = Math.max(2000, c.getInt("hostility.engage-seconds", 25) * 1000L);

        // P2 — armadura defensiva
        armorEnabled    = c.getBoolean("armor.enabled", true);
        armorProtection = Math.max(0, Math.min(10, c.getInt("armor.protection", 4)));
        armorUnbreaking = Math.max(0, Math.min(10, c.getInt("armor.unbreaking", 3)));
        armorWornPercent = Math.max(0, Math.min(95, c.getInt("armor.worn-percent", 80)));
        armorHelmet     = c.getString("armor.helmet", "NETHERITE_HELMET");
        armorChestplate = c.getString("armor.chestplate", "NETHERITE_CHESTPLATE");
        armorLeggings   = c.getString("armor.leggings", "NETHERITE_LEGGINGS");
        armorBoots      = c.getString("armor.boots", "NETHERITE_BOOTS");
        Set<String> ak = new HashSet<>();
        for (String s : c.getStringList("armor.npcs")) {
            String n = s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
            if (!n.isEmpty()) ak.add(n);
        }
        armorKeys = ak;

        // P4 — ataque a distancia (defaults globales)
        rangedShotSound      = c.getString("ranged.shot-sound", "entity.firework_rocket.blast");
        rangedImpactSound    = c.getString("ranged.impact-sound", "entity.player.hurt");
        rangedTracerParticle = c.getString("ranged.tracer-particle", "CRIT");
        rangedMuzzleParticle = c.getString("ranged.muzzle-particle", "SMOKE");
        rangedDefAccuracy    = clamp01(c.getDouble("ranged.default-accuracy", 0.97));
        rangedDefDamage      = Math.max(0.0, c.getDouble("ranged.default-damage", 5.0));
        rangedDefRange       = Math.max(1.0, c.getDouble("ranged.default-range", 22.0));
        rangedDefCooldownMs  = Math.max(200L, c.getInt("ranged.default-cooldown-ms", 1500));

        Map<String, NpcPersona> pp = new HashMap<>();
        Map<String, java.util.Set<org.bukkit.Material>> lf = new HashMap<>();   // gustos de comida por NPC
        ConfigurationSection ps = c.getConfigurationSection("personas");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(key);
                if (s == null) continue;
                String k = key.toLowerCase(Locale.ROOT).trim();
                pp.put(k, new NpcPersona(
                        k,
                        s.getString("name", key),
                        s.getString("color", "&f"),
                        s.getString("greeting", ""),
                        s.getString("persona", ""),
                        s.getString("model", null),
                        s.getDouble("voice-pitch", voiceBasePitch),
                        s.getString("voice-sound", voiceSound),
                        s.getString("skin-url", ""),
                        s.getBoolean("mission-giver", false),
                        s.getString("weapon", ""),
                        genderFor(k, s.getString("voice-gender", null), s.getDouble("voice-pitch", voiceBasePitch)),
                        s.getBoolean("ranged", false),
                        clamp01(s.getDouble("ranged-accuracy", rangedDefAccuracy)),
                        Math.max(0.0, s.getDouble("ranged-damage", rangedDefDamage)),
                        Math.max(1.0, s.getDouble("ranged-range", rangedDefRange)),
                        Math.max(200L, (long) s.getInt("ranged-cooldown-ms", (int) rangedDefCooldownMs))));
                // comidas que le gustan a ESTE NPC (si hay lista, SOLO esas suben afinidad; sin lista = le gusta todo)
                java.util.Set<org.bukkit.Material> foods = new HashSet<>();
                for (String fn : s.getStringList("liked-foods")) {
                    org.bukkit.Material m = org.bukkit.Material.matchMaterial(fn == null ? "" : fn.toUpperCase(Locale.ROOT));
                    if (m != null) foods.add(m);
                }
                if (!foods.isEmpty()) lf.put(k, foods);
            }
        }
        personas = pp;
        likedFoods = lf;
        for (NpcPersona p : pp.values()) {
            lookup.put(normalize(p.key), p);
            lookup.put(normalize(p.name), p);   // permite nombrar el NPC por su nombre visible
        }

        bond = new BondConfig(c);

        // Interacciones fisicas (con valores por defecto si la seccion no existe)
        itxEnabled        = c.getBoolean("interactions.enabled", true);
        followWords       = wordSet(c, "interactions.follow-words",
                "sigueme", "ven conmigo", "acompaname", "vente", "ven aqui", "vamos juntos", "sigue me");
        stayWords         = wordSet(c, "interactions.stay-words",
                "quedate", "espera aqui", "esperame", "no te muevas", "quieto aqui", "quedate aqui");
        leaveWords        = wordSet(c, "interactions.leave-words",
                "vete", "largate", "dejame en paz", "pirate", "fuera de aqui", "esfumate", "vete de aqui");
        followMinAffinity = c.getInt("interactions.follow-min-affinity", 20);
        followSpeed       = Math.max(0.5, Math.min(1.6, c.getDouble("interactions.follow-speed", 1.15)));
        followMaxMs       = Math.max(30, c.getInt("interactions.follow-max-seconds", 600)) * 1000L;
        leaveAnnoy        = Math.max(0, c.getInt("interactions.leave-annoy", 5));
        giftAffinity      = Math.max(0, c.getInt("interactions.gift-affinity", 6));
        followOkMsg       = c.getString("interactions.follow-ok-message",
                "&a{npc} confia en ti y empieza a seguirte. &7(dile \"quedate\" para que te espere)");
        followLowMsg      = c.getString("interactions.follow-low-message",
                "&e{npc} no te tiene suficiente confianza todavia: vas &f{have}&e/&f{need}&e. Hablale o regalale comida.");
        followWaryMsg     = c.getString("interactions.follow-wary-message",
                "&c{npc} desconfia de ti y no piensa acompanarte. Tendras que ganarte su respeto primero.");
    }

    /** Lista de frases de la config, normalizadas; si esta vacia, usa los valores por defecto. */
    private static Set<String> wordSet(FileConfiguration c, String path, String... defaults) {
        Set<String> out = new HashSet<>();
        for (String s : c.getStringList(path)) { String n = normalize(s); if (!n.isEmpty()) out.add(n); }
        if (out.isEmpty()) for (String s : defaults) out.add(normalize(s));
        return out;
    }

    /** Busca el persona que corresponde a un nombre de entidad (sin distinguir acentos/mayúsculas). */
    public NpcPersona match(String entityName) {
        return entityName == null ? null : lookup.get(normalize(entityName));
    }

    /** Mujeres conocidas del roster (lo demas se asume hombre, salvo override o pitch alto). */
    private static final Set<String> FEMALE_KEYS = Set.of(
            "lola", "maid", "miss", "luna", "chibi", "remedios_saavedra", "valeria_heredera",
            "vera_navaja", "yaiza_navaja", "tomasa_recibo", "agente_quiroga", "dolores_quemada",
            "merche_gasas", "amparo_bazar", "fina_pescadera", "remedios_la_chatarra",
            "yenni_radio_macuto", "vera_telegrama", "remedios_visillo", "marga_la_banca",
            "yoli_coctel", "encarna_tablao", "aurora_videncia");

    /** Genero de la voz: override de config > tabla del roster > deduccion por tono (pitch alto = mujer). */
    private static String genderFor(String key, String override, double pitch) {
        if (override != null && !override.isBlank()) {
            String o = override.trim().toLowerCase(Locale.ROOT);
            if (o.startsWith("f") || o.startsWith("m")) return o.substring(0, 1);
        }
        if (FEMALE_KEYS.contains(key)) return "f";
        // Si no esta en la tabla (NPC nuevo sin clasificar), deduce por el tono configurado.
        return pitch >= 1.2 ? "f" : "m";
    }

    public static String normalize(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT);
        // Quita puntuacion (comas, puntos, signos ¿¡) y colapsa espacios: el matching de frases secretas,
        // insultos y ordenes no debe romperse por una coma o un punto de mas. Conserva letras, numeros y espacios.
        n = n.replaceAll("[^\\p{L}\\p{Nd} ]", " ").replaceAll("\\s+", " ");
        return n.trim();
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
