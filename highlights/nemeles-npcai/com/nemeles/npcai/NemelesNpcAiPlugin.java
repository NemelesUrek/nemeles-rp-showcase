package com.nemeles.npcai;

import com.nemeles.npcai.command.NpcAiCommands;
import com.nemeles.npcai.listener.NpcChatListener;
import com.nemeles.npcai.listener.NpcInteractListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class NemelesNpcAiPlugin extends JavaPlugin {

    private AiConfig cfg;
    private OllamaClient ollama;
    private ConversationManager manager;
    private HologramManager holo;
    private com.nemeles.npcai.listener.CitizensListener citizens; // null si Citizens no está
    private NpcLifeManager life; // null si Citizens no está
    private BondManager bond;    // null si la BD no está o el easter egg está off
    private VoiceServer voice;   // null si voice-chat.enabled=false o el puerto no se pudo abrir
    private InteractionManager interactions;   // null si Citizens no está
    private BanterManager banter;              // charlas espontaneas entre NPCs cercanos (null si Citizens no está)
    private NpcCombatManager combat;           // P1: NPCs danables + muerte/respawn (no-op sin Citizens)
    private NpcArmorManager armor;             // P2: armadura defensiva (null sin Citizens/off)
    private AffinityManager affinity;          // P5: expuesto a /relacion y al holograma (null si BD off)
    private LunaBondPerks lunaPerks;           // poderes del Lazo de Luna (alma gemela)

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = new AiConfig(getConfig());
        ollama = new OllamaClient(cfg);
        holo = new HologramManager(this, cfg.holoEnabled, cfg.holoHeight);
        holo.sweepOrphans(); // limpia hologramas huerfanos de un arranque anterior
        manager = new ConversationManager(this, cfg, ollama, holo);

        // Afinidad: que la ciudad te RECUERDE. Aditivo y a prueba de fallos (si la BD no está, se omite).
        AffinityManager aff = null;
        try {
            if (com.nemeles.core.api.NemelesApi.isReady()) {
                com.nemeles.core.api.db.DatabaseProvider db = com.nemeles.core.api.NemelesApi.database();
                db.registerMigrations("npcai", com.nemeles.npcai.db.AffinityMigrations.all());
                aff = new AffinityManager(this,
                        new com.nemeles.npcai.db.AffinityDao(db.dataSource()), db.dbExecutor(),
                        getConfig().getBoolean("affinity.enabled", true));
                manager.setAffinity(aff);
                this.affinity = aff;   // P5: expuesto a /relacion y al holograma de relacion
                getLogger().info("Afinidad de NPCs activada (la ciudad te recuerda).");
                // Memoria de largo plazo: el NPC recuerda DE QUE hablo contigo entre sesiones (misma BD npcai).
                MemoryManager mem = new MemoryManager(this,
                        new com.nemeles.npcai.db.MemoryDao(db.dataSource()), db.dbExecutor(),
                        getConfig().getBoolean("memory.enabled", true));
                manager.setMemory(mem);
                getLogger().info("Memoria de largo plazo de NPCs activada (te recuerdan entre sesiones).");
                // Easter egg: alma gemela. UNA sola companera fiel en todo el servidor para un NPC (por defecto Luna).
                if (cfg.bond.enabled) {
                    bond = new BondManager(this, cfg.bond,
                            new com.nemeles.npcai.db.BondDao(db.dataSource()), db.dbExecutor());
                    manager.setBond(bond);
                    getLogger().info("Easter egg de alma gemela activado para el NPC: " + cfg.bond.persona
                            + (bond.hasOwner() ? " (ya tiene companero: " + bond.ownerName() + ")" : " (libre)"));
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Afinidad de NPCs no disponible: " + t.getMessage());
        }

        PluginManager pm = getServer().getPluginManager();
        // P5: el listener de interaccion recibe ahora la afinidad y el holograma (para "ver tu relacion").
        pm.registerEvents(new NpcInteractListener(manager, () -> cfg, () -> interactions,
                () -> affinity, holo), this);
        pm.registerEvents(new NpcChatListener(this, manager), this);

        // P1: NPCs danables + muerte/respawn. Se construye SIEMPRE (no-op si Citizens no esta).
        combat = new NpcCombatManager(this, manager, aff, () -> cfg);
        // Regen: si lo hieren y el jugador se aleja sin volver a pegarle, se cura toda la vida y se calma (cada 2 s).
        getServer().getScheduler().runTaskTimer(this, combat::regenTick, 100L, 40L);

        // P2: armadura defensiva al primer golpe. Requiere Citizens.
        if (cfg.armorEnabled && pm.getPlugin("Citizens") != null) {
            armor = new NpcArmorManager(this, () -> cfg);
            combat.setArmor(armor);   // al respawnear vuelve sin la armadura de batalla
            getLogger().info("Armadura defensiva de NPCs activada (se blindan al primer golpe).");
        }

        // P7: contexto del mundo (alerta/territorio/wanted/heat/zona) en el prompt. Cada dato es opcional.
        try {
            manager.setWorldContext(new WorldContext(cfg));
            getLogger().info("Contexto del mundo activado: los NPCs reaccionan a la ciudad (territorio, wanted, alerta).");
        } catch (Throwable t) {
            getLogger().warning("Contexto del mundo no disponible: " + t.getMessage());
        }

        // Retaliacion (P3): requiere afinidad. El listener de dano se registra si HAY afinidad
        // (independiente de hostilidad) para que muerte/respawn (P1) y armadura (P2) funcionen siempre.
        RetaliationManager retaliation = null;
        if (aff != null) {
            if (cfg.hostilityEnabled) {
                retaliation = new RetaliationManager(this, () -> cfg, aff, manager);
                manager.setRetaliation(retaliation);   // P3: insultar suma a la racha
                combat.setRetaliation(retaliation);    // para CALMARLO al regenerar vida
            }
            com.nemeles.npcai.listener.NpcDamageListener dmgL = new com.nemeles.npcai.listener.NpcDamageListener(
                    () -> cfg, aff, combat, armor, retaliation);
            dmgL.setBond(bond);   // el dueño del Lazo no puede dañar a su NPC
            pm.registerEvents(dmgL, this);
        } else {
            // Sin afinidad pero CON Citizens: aun queremos muerte/respawn + armadura al pegar a un NPC.
            if (combat != null || armor != null) {
                com.nemeles.npcai.listener.NpcDamageListener dmgL = new com.nemeles.npcai.listener.NpcDamageListener(
                        () -> cfg, null, combat, armor, null);
                dmgL.setBond(bond);   // el dueño del Lazo no puede dañar a su NPC
                pm.registerEvents(dmgL, this);
            }
        }

        // Poderes del LAZO DE LUNA (item del alma gemela): llamar/escolta, defender, aura, pase libre.
        if (bond != null && bond.enabled()) {
            try {
                lunaPerks = new LunaBondPerks(this, bond, aff, () -> cfg);
                lunaPerks.setArmor(armor);   // que Luna se blinde al defender (no pelee en camiseta)
                pm.registerEvents(lunaPerks, this);
                getServer().getScheduler().runTaskTimer(this, lunaPerks::auraTick, 60L, 60L);     // aura cada 3 s
                getServer().getScheduler().runTaskTimer(this, lunaPerks::guardTick, 20L, 10L);    // escolta/defensa cada 0.5 s
                getServer().getScheduler().runTaskTimer(this, lunaPerks::jealousyTick, 40L, 20L); // celos/traicion cada 1 s
                getLogger().info("Poderes del Lazo de Luna activados (escolta, defensa, aura, pase libre, celos y pacto roto).");
            } catch (Throwable t) {
                getLogger().warning("Poderes del Lazo de Luna no disponibles: " + t.getMessage());
            }
        }

        // Hostilidad: tick de proximidad (avisa, golpea, escala, dispara a distancia y no se deja hostigar).
        if (aff != null && cfg.hostilityEnabled) {
            HostilityManager hostility = new HostilityManager(this, () -> cfg, aff, manager, retaliation);
            hostility.setBond(bond);   // pase libre del alma gemela (Luna y los suyos no la atacan)
            // P4: ataque a distancia (Luna dispara). Vanilla + a prueba de fallos.
            try {
                NpcRangedManager rangedMgr = new NpcRangedManager(this, () -> cfg);
                hostility.setRanged(rangedMgr);
                getLogger().info("Ataque a distancia de NPCs activado (p.ej. Luna dispara con alta precision).");
            } catch (Throwable t) {
                getLogger().warning("Ataque a distancia no disponible: " + t.getMessage());
            }
            getServer().getScheduler().runTaskTimer(this, hostility::tick, 40L, 20L);            // cada 1 s
            final RetaliationManager rf = retaliation;
            getServer().getScheduler().runTaskTimer(this, () -> { if (rf != null) rf.sweep(); }, 40L, 40L); // limpia rachas + corta persecucion al expirar (2 s)
            getLogger().info("Hostilidad de NPCs activada (se defienden, escalan y no se dejan hostigar).");
        }
        if (pm.getPlugin("Citizens") != null) {
            try {
                citizens = new com.nemeles.npcai.listener.CitizensListener(this, manager, () -> cfg);
                citizens.setCombat(combat);   // re-hace danables a los NPCs que respawnean en chunk-load (no quedan intocables)
                // Venta callejera de maria a NPCs (regateo + riesgo de denuncia/encubierto)
                try { citizens.setDeals(new StreetDealManager(this, aff)); } catch (Throwable t) {
                    getLogger().warning("Venta callejera no disponible: " + t.getMessage());
                }
                // Cadena de la COCAINA: vendedor de hoja, procesador y comprador NOMADA (rota a diario)
                try { citizens.setCoca(new CocaChainManager(this)); } catch (Throwable t) {
                    getLogger().warning("Cadena de coca no disponible: " + t.getMessage());
                }
                pm.registerEvents(citizens, this);
                // Vida: los NPCs pasean por el mundo como vecinos (se paran si hablas con ellos).
                life = new NpcLifeManager(this, manager, () -> cfg);
                life.setCombat(combat);   // P1: los NPCs nuevos nacen danables con vida fija
                getServer().getScheduler().runTaskTimer(this, life::tick, 160L, cfg.lifeTickTicks);
                // P1: barrido inicial — vida + vulnerabilidad a los NPCs YA spawneados (tras cargar el mundo).
                getServer().getScheduler().runTaskLater(this, () -> {
                    try {
                        int n = combat.sweepExisting();
                        if (n > 0) getLogger().info("NPCs danables: aplicada vida (" + cfg.npcHealth + ") a " + n + " NPC(s).");
                    } catch (Throwable ignored) { }
                }, 100L);
                // Interacciones fisicas: sigueme/quedate/vete por amistad + regalos de comida.
                interactions = new InteractionManager(this, manager, aff, () -> cfg);
                manager.setInteractions(interactions);
                life.setFollowingCheck(id -> interactions.isFollowing(id));
                getServer().getScheduler().runTaskTimer(this, interactions::tick, 120L, 40L);   // cada 2 s
                // Banter: cuando dos NPCs configurados estan cerca, sueltan solos bromas entre ellos.
                banter = new BanterManager(this, manager, holo, () -> cfg);
                if (banter.enabled()) {
                    getServer().getScheduler().runTaskTimer(this, banter::tick, 200L, 100L);   // cada 5 s
                    getLogger().info("Banter NPC-a-NPC activado (charlas espontaneas entre NPCs cercanos).");
                }
                getLogger().info("Integracion con Citizens activada (clic = charla; skin auto; vida/paseo; sigueme/regalos).");
            } catch (Throwable t) {
                getLogger().warning("No se pudo enganchar con Citizens: " + t.getMessage());
            }
        }

        // VOZ: pagina web servida por el plugin para hablar con los NPCs por microfono (/voz da el enlace).
        if (getConfig().getBoolean("voice-chat.enabled", true)) {
            try {
                voice = new VoiceServer(this, manager, () -> cfg);
                voice.start();
                manager.setReplyListener(voice);   // las respuestas del NPC llegan a la pagina y se LEEN EN ALTO
                getLogger().info("Pagina de VOZ activa en el puerto " + voice.port() + " — comando /voz para el enlace.");
            } catch (Throwable t) {
                voice = null;
                getLogger().warning("Pagina de voz no disponible (¿puerto ocupado?): " + t.getMessage());
            }
        }

        NpcAiCommands cmds = new NpcAiCommands(this, manager, () -> affinity);
        setExec("npcai", cmds);
        setExec("hablar", cmds);
        setExec("voz", cmds);
        setExec("relacion", cmds);

        getServer().getScheduler().runTaskTimer(this, manager::tickExpire, 100L, 100L); // cada 5 s
        getServer().getScheduler().runTaskTimer(this, this::ejectRiders, 20L, 5L);      // anti-montar (cada 0.25 s)

        getLogger().info("NemelesNpcAI habilitado. NPCs: " + cfg.personas.size()
                + " | IA: " + cfg.ollamaUrl + " (" + cfg.model + ")");
        getLogger().info("Requiere Ollama corriendo + modelo descargado:  ollama pull " + cfg.model);

        // precalentar: carga el modelo en VRAM ya, para que la primera charla sea rápida
        ollama.warmup(cfg.model);
    }

    @Override
    public void onDisable() {
        if (voice != null) { try { voice.stop(); } catch (Throwable ignored) { } }
        if (interactions != null) { try { interactions.stopAll(); } catch (Throwable ignored) { } }
        if (holo != null) holo.removeAll();
    }

    /** Baja al jugador si acaba montado en cualquier NPC de Citizens o persona de IA. Montar NPCs queda off. */
    private void ejectRiders() {
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
            if (!p.isInsideVehicle()) continue;
            org.bukkit.entity.Entity v = p.getVehicle();
            if (v == null) continue;
            if (v.hasMetadata("NPC") || (cfg != null && cfg.match(v.getName()) != null)) p.leaveVehicle();
        }
    }

    public void reloadAll() {
        reloadConfig();
        cfg = new AiConfig(getConfig());
        ollama = new OllamaClient(cfg);
        manager.update(cfg, ollama);
        try { manager.setWorldContext(new WorldContext(cfg)); } catch (Throwable ignored) { }   // P7
        if (armor != null) armor.clearMemory();   // P2: re-evalua quien se blinda con la nueva config
    }

    public String personaKeys() { return String.join(", ", cfg.personas.keySet()); }

    public int personaCount() { return cfg.personas.size(); }

    public NpcLifeManager life() { return life; }

    public NpcCombatManager combat() { return combat; }   // P1: /npcai vida

    public AffinityManager affinity() { return affinity; } // P5

    public AiConfig cfg() { return cfg; }                  // P5: /relacion

    public BondManager bond() { return bond; }

    public VoiceServer voice() { return voice; }

    /** Aplica las skins configuradas (skin-url) a los NPCs existentes que casen con un persona. */
    public void applyConfiguredSkins(org.bukkit.command.CommandSender s) {
        if (citizens == null) { s.sendMessage("§cCitizens no está instalado: no hay skins automáticas que aplicar."); return; }
        int n = citizens.applyAllSkins();
        s.sendMessage("§aAplicando skins configuradas a §f" + n + "§a NPC(s)...");
    }

    private void setExec(String n, NpcAiCommands c) {
        PluginCommand pc = getCommand(n);
        if (pc != null) pc.setExecutor(c);
    }
}
