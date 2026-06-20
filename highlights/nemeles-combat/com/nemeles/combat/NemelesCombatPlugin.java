package com.nemeles.combat;

import com.nemeles.combat.command.CombatCommands;
import com.nemeles.combat.db.CombatMigrations;
import com.nemeles.combat.db.DeathDao;
import com.nemeles.combat.listener.CombatConnectionListener;
import com.nemeles.combat.listener.CombatDamageListener;
import com.nemeles.combat.listener.CombatDeathListener;
import com.nemeles.combat.listener.DownedActionListener;
import com.nemeles.combat.listener.GunListener;
import com.nemeles.combat.papi.CombatPlaceholders;
import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.db.DatabaseProvider;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class NemelesCombatPlugin extends JavaPlugin {

    private DownedManager downed;
    private CombatKeys keys;
    private com.nemeles.combat.render.GunModelService gunModel;   // modelo 3D de armas (BetterModel, soft-dep)

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!NemelesApi.isReady()) {
            getLogger().severe("NemelesCore no esta listo; deshabilitando NemelesCombat.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        DatabaseProvider db = NemelesApi.database();
        db.registerMigrations("combat", CombatMigrations.all());

        CombatConfig cfg = CombatConfig.from(getConfig());
        keys = new CombatKeys(this);
        MedItems.init(this);
        registerMedRecipes();
        GunRegistry guns = new GunRegistry(this, keys, cfg);
        AmmoManager ammo = new AmmoManager(this, keys, guns);
        // Modelo 3D del arma en mano (3a persona, conserva la skin) via BetterModel. SOFT-DEP: si BetterModel
        // no esta, el servicio queda inerte y el combate funciona igual (todo null-safe).
        gunModel = new com.nemeles.combat.render.GunModelService(this, guns, cfg);
        ammo.setGunModel(gunModel);
        downed = new DownedManager(this, cfg);
        CombatTagManager tags = new CombatTagManager(cfg.tagSeconds);
        DeathDao deathDao = new DeathDao(db.dataSource());
        db.dbExecutor().execute(() -> {
            try { deathDao.init(); }
            catch (Exception e) { getLogger().warning("[COMBAT] init death_log: " + e.getMessage()); }
        });

        CombatServiceImpl svc = new CombatServiceImpl(downed, guns, ammo);
        NemelesApi.registerCombat(svc);

        PluginManager pm = getServer().getPluginManager();
        // Sistema medico por PARTES del cuerpo (estilo Project Zomboid): heridas localizadas,
        // sangrado, infecciones, tratamientos con cursos y XP de medicina.
        com.nemeles.combat.body.BodyManager bodyMgr = new com.nemeles.combat.body.BodyManager(this);
        try { saveResource("vision_medica.yml", false); } catch (Throwable ignored) { }   // fresh install
        com.nemeles.combat.body.VisionTexts.load(this);   // textos de vision gradual (equipo IA)
        pm.registerEvents(new com.nemeles.combat.body.BodyListener(bodyMgr, cfg.noNaturalRegen), this);
        // al rescatar/expirar el downed, el cuerpo se sana entero (lo usa DownedManager)
        downed.setBodyHealer(bodyMgr::healAll);
        getServer().getScheduler().runTaskTimer(this, bodyMgr::bleedTick, 200L, 200L);       // sangrado cada 10s
        getServer().getScheduler().runTaskTimer(this, bodyMgr::infectionTick, 1200L, 1200L); // infeccion/regen cada 60s
        getServer().getScheduler().runTaskTimer(this, bodyMgr::effectsTick, 60L, 60L);       // efectos cada 3s

        GunListener gunListener = new GunListener(this, guns, ammo, downed, tags);
        gunListener.setBody(bodyMgr);
        gunListener.setFalloff(cfg.damageFalloff, cfg.falloffStartDist, cfg.falloffEndDist, cfg.falloffMinPct);
        gunListener.setGating(cfg);
        gunListener.setGunModel(gunModel);
        pm.registerEvents(gunListener, this);
        // Deteccion de equipar/guardar arma (baja latencia) + tick de reconciliacion (red de seguridad).
        if (gunModel.isAvailable()) {
            pm.registerEvents(new com.nemeles.combat.listener.GunEquipListener(guns, gunModel), this);
            gunModel.start();
            getLogger().info("[GunModel] BetterModel enlazado: armas 3D en 3a persona activas.");
        }
        // SECUELA post-revive: levantarse SIN transfusion previa castiga el cuerpo (la sangre importa)
        downed.setReviveAftermath((revivido, estabilizado) -> {
            if (estabilizado) return;
            bodyMgr.addPain(revivido.getUniqueId(), 35);
            var torso = bodyMgr.body(revivido.getUniqueId()).get(com.nemeles.combat.body.BodyPart.TORSO_SUP);
            torso.hp = Math.max(15, torso.hp - 20);
            torso.vendada = false;
            revivido.sendMessage(org.bukkit.ChatColor.GRAY
                    + "Te levantas a pulso, sin sangre nueva: el pecho te pesa y el dolor viene en oleadas. "
                    + org.bukkit.ChatColor.DARK_GRAY + "(secuelas: revívete con transfusión previa para evitarlas)");
        });

        org.bukkit.command.PluginCommand cuerpoCmd = getCommand("cuerpo");
        if (cuerpoCmd != null) cuerpoCmd.setExecutor((sender, cmd, label, a) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) { sender.sendMessage("Solo jugadores."); return true; }
            String sub = a.length >= 1 ? a[0].toLowerCase(java.util.Locale.ROOT) : "";
            // ── subcomandos de la ficha por CHAT (Bedrock / derribado): ver|tratar <PARTE> ──
            if (sub.equals("ver") || sub.equals("tratar")) {
                if (a.length < 2) { p.sendMessage(org.bukkit.ChatColor.RED + "Falta la parte. Usa /cuerpo y pulsa un botón."); return true; }
                com.nemeles.combat.body.BodyPart part;
                try { part = com.nemeles.combat.body.BodyPart.valueOf(a[1].toUpperCase(java.util.Locale.ROOT)); }
                catch (IllegalArgumentException ex) { p.sendMessage(org.bukkit.ChatColor.RED + "Parte desconocida."); return true; }
                java.util.UUID pid = com.nemeles.combat.body.BodyGUI.VIEWING.get(p.getUniqueId());
                org.bukkit.entity.Player patient = pid != null ? getServer().getPlayer(pid) : p;
                if (patient == null) patient = p;
                if (!patient.getUniqueId().equals(p.getUniqueId())
                        && (!patient.getWorld().equals(p.getWorld()) || patient.getLocation().distanceSquared(p.getLocation()) > 25)) {
                    p.sendMessage(org.bukkit.ChatColor.RED + "El paciente se alejó demasiado."); return true;
                }
                if (sub.equals("ver")) {
                    com.nemeles.combat.body.BodyGUI.chatDetail(p, patient, part, bodyMgr);
                } else {
                    bodyMgr.treat(p, patient, part);
                    if (!bodyMgr.isChanneling(p.getUniqueId())) com.nemeles.combat.body.BodyGUI.openChat(p, patient, bodyMgr);
                }
                return true;
            }
            // ── abrir la ficha (de uno mismo o de otro a <=5 bloques) ──
            org.bukkit.entity.Player patient = p;
            if (a.length >= 1) {
                patient = getServer().getPlayerExact(a[0]);
                if (patient == null) { p.sendMessage(org.bukkit.ChatColor.RED + "Ese paciente no está conectado."); return true; }
                if (!patient.getWorld().equals(p.getWorld())
                        || patient.getLocation().distanceSquared(p.getLocation()) > 25) {
                    p.sendMessage(org.bukkit.ChatColor.RED + "Acércate al paciente para examinarlo (5 bloques).");
                    return true;
                }
            }
            com.nemeles.combat.body.BodyGUI.open(p, patient, bodyMgr);
            return true;
        });
        // Escuela de medicina: estudiar (pago + manual + 5 min) -> EXAMEN cronometrado anti-trampa
        com.nemeles.combat.body.MedExam medExam = new com.nemeles.combat.body.MedExam(this, bodyMgr);
        pm.registerEvents(medExam, this);
        // Nutricion estilo Zomboid: comida en mal estado + asco por repeticion + dieta variada
        pm.registerEvents(new com.nemeles.combat.body.FoodListener(bodyMgr), this);

        org.bukkit.command.PluginCommand medCmd = getCommand("medicina");
        if (medCmd != null) medCmd.setExecutor((sender, cmd, label, a) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) { sender.sendMessage("Solo jugadores."); return true; }
            String sub = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
            String which = a.length >= 2 ? a[1] : "";
            switch (sub) {
                case "estudiar" -> medExam.study(p, which);
                case "examen" -> medExam.exam(p, which);
                case "items" -> {
                    p.sendMessage(org.bukkit.ChatColor.YELLOW + "── Equipo médico (se vende en la FARMACIA) ──");
                    for (MedItems.Def d : MedItems.defs().values()) {
                        p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('§',
                                " " + d.display + org.bukkit.ChatColor.DARK_GRAY + " (" + d.id + ")"
                                        + (d.tool ? org.bukkit.ChatColor.GRAY + " · no se gasta" : "")));
                    }
                    p.sendMessage(org.bukkit.ChatColor.GRAY + "Craft: venda = 2 papel + 1 hilo · férula = 2 palos + 1 hilo");
                }
                case "recargar" -> {   // admin: recarga examen_medicina.yml + mensajes_heridas.yml sin reiniciar
                    if (!p.isOp() && !p.hasPermission("nemeles.combat.admin")) { p.sendMessage(org.bukkit.ChatColor.RED + "Solo administradores."); return true; }
                    medExam.loadBank();
                    bodyMgr.messages().reload(this);
                    com.nemeles.combat.body.VisionTexts.load(this);
                    p.sendMessage(org.bukkit.ChatColor.GREEN + "Banco de examen, mensajes de heridas y visión médica recargados.");
                }
                case "dar" -> {   // admin/testing: /medicina dar <item|kit> [n]
                    if (!p.isOp() && !p.hasPermission("nemeles.combat.admin")) { p.sendMessage(org.bukkit.ChatColor.RED + "Solo administradores."); return true; }
                    int n = a.length >= 3 ? Math.max(1, parseIntOr(a[2], 1)) : 1;
                    if (which.equalsIgnoreCase("kit")) {
                        for (String id : MedItems.defs().keySet()) {
                            var it = MedItems.create(id, id.equals(MedItems.BISTURI) || id.equals(MedItems.PINZAS) || id.equals(MedItems.MEDKIT) ? 1 : 4);
                            if (it != null) p.getInventory().addItem(it);
                        }
                        p.sendMessage(org.bukkit.ChatColor.GREEN + "Kit médico completo entregado.");
                    } else {
                        var it = MedItems.create(which, n);
                        if (it == null) { p.sendMessage(org.bukkit.ChatColor.RED + "Item desconocido. /medicina items"); return true; }
                        p.getInventory().addItem(it);
                        p.sendMessage(org.bukkit.ChatColor.GREEN + "Entregado: " + n + "x " + MedItems.plain(which));
                    }
                }
                case "sanar" -> {   // admin: cura TODO (heridas + derribado + vida) — sin morir mil veces
                    if (!p.isOp() && !p.hasPermission("nemeles.combat.admin")) { p.sendMessage(org.bukkit.ChatColor.RED + "Solo administradores."); return true; }
                    org.bukkit.entity.Player target = a.length >= 2 ? getServer().getPlayerExact(a[1]) : p;
                    if (target == null) { p.sendMessage(org.bukkit.ChatColor.RED + "Jugador no conectado."); return true; }
                    bodyMgr.healAll(target.getUniqueId());     // borra heridas + dolor
                    downed.clearDowned(target);                // saca de derribado + restaura movimiento/visión
                    try {
                        for (org.bukkit.potion.PotionEffect ef : target.getActivePotionEffects()) target.removePotionEffect(ef.getType());
                        var attr = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                        if (attr != null) attr.setBaseValue(20.0);
                        target.setHealth(attr != null ? attr.getValue() : 20.0);
                        target.setFoodLevel(20); target.setSaturation(20f); target.setFireTicks(0);
                    } catch (Throwable ignored) { }
                    target.sendMessage(org.bukkit.ChatColor.GREEN + "✚ Te han curado por completo: cuerpo nuevo.");
                    if (!target.getUniqueId().equals(p.getUniqueId()))
                        p.sendMessage(org.bukkit.ChatColor.GREEN + "Curaste por completo a " + target.getName() + ".");
                }
                case "curso" -> {   // admin/testing: fija el título médico sin examen
                    if (!p.isOp() && !p.hasPermission("nemeles.combat.admin")) { p.sendMessage(org.bukkit.ChatColor.RED + "Solo administradores."); return true; }
                    int level;
                    try { level = Math.max(0, Math.min(2, Integer.parseInt(which))); }
                    catch (Exception ex) { p.sendMessage(org.bukkit.ChatColor.YELLOW + "/medicina curso <0|1|2> [jugador]"); return true; }
                    org.bukkit.entity.Player target = a.length >= 3 ? getServer().getPlayerExact(a[2]) : p;
                    if (target == null) { p.sendMessage(org.bukkit.ChatColor.RED + "Jugador no conectado."); return true; }
                    bodyMgr.setCourse(target, level);
                    String nm = level == 0 ? "ninguno (lego)" : level == 1 ? "primeros auxilios" : "medicina avanzada";
                    p.sendMessage(org.bukkit.ChatColor.GREEN + "Curso de " + target.getName() + " = " + level + " (" + nm + ").");
                }
                default -> p.sendMessage(org.bukkit.ChatColor.YELLOW
                        + "/medicina estudiar <basico|avanzado> · examen · items · sanar [jug] · curso <0|1|2> [jug] (admin)");
            }
            return true;
        });
        GraveManager grave = new GraveManager(this);   // tumba (loot box) al morir del todo
        pm.registerEvents(grave, this);
        pm.registerEvents(new CombatDamageListener(downed, tags), this);
        CombatDeathListener deathListener = new CombatDeathListener(this, cfg, keys, downed, tags, deathDao, db.dbExecutor(), grave);
        deathListener.setGunModel(gunModel);   // limpiar modelo 3D al morir (arma -> tumba)
        pm.registerEvents(deathListener, this);
        pm.registerEvents(new DownedActionListener(downed), this);
        CombatConnectionListener connListener = new CombatConnectionListener(cfg, keys, downed, tags, deathDao, db.dbExecutor());
        connListener.setGunModel(gunModel);   // limpiar modelo 3D al salir (sin fantasmas)
        pm.registerEvents(connListener, this);
        // CARGAR a hombros al derribado (traslado) + HOSPITAL NPC (check-in si no hay medicos)
        pm.registerEvents(new com.nemeles.combat.listener.CarryListener(downed), this);
        pm.registerEvents(new com.nemeles.combat.listener.HospitalListener(this, bodyMgr, downed, cfg), this);

        CombatCommands cmds = new CombatCommands(this, downed, guns, ammo);
        setExecutor("rendirse", cmds);
        setExecutor("revivir", cmds);
        setExecutor("emt", cmds);
        setExecutor("arma", cmds);
        setExecutor("manosarriba", cmds);
        setExecutor("atracar", cmds);

        if (pm.getPlugin("PlaceholderAPI") != null) {
            try {
                new CombatPlaceholders(this, downed, guns).register();
                getLogger().info("Placeholders %combat_*% registrados en PlaceholderAPI.");
            } catch (Throwable t) {
                getLogger().warning("No se pudieron registrar placeholders de combate: " + t.getMessage());
            }
        }

        getServer().getScheduler().runTaskTimer(this, downed::tick, 20L, 20L); // sangrado/revive: 1s
        getServer().getScheduler().runTaskTimer(this, bodyMgr::treatScan, 5L, 5L); // minijuegos de curacion: 0.25s (ventanas/pulso)

        getLogger().info("NemelesCombat habilitado. Armas + downed/EMT activos.");
    }

    @Override
    public void onDisable() {
        // quitar TODOS los modelos 3D de armas para no dejar entidades/trackers huerfanos (leccion aprendida).
        if (gunModel != null) { try { gunModel.cleanupAll(); } catch (Throwable ignored) { } }
        if (downed != null) {
            // un reinicio NO debe ser un escape gratis para los derribados: aplica el sumidero de
            // contrabando (lo fiable de forma síncrona) antes de restaurar y limpiar las sesiones.
            if (keys != null) {
                for (org.bukkit.entity.Player p : downed.onlineDowned()) {
                    try { Contraband.strip(p, keys.contraband, false); } catch (Throwable ignored) { }
                }
            }
            downed.restoreAll();
        }
    }

    private void setExecutor(String name, CombatCommands cmds) {
        PluginCommand c = getCommand(name);
        if (c != null) c.setExecutor(cmds);
    }

    private static int parseIntOr(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    /** Recetas baratas SOLO para lo basico (venda/ferula); el resto se compra en la farmacia. */
    private void registerMedRecipes() {
        try {
            var venda = MedItems.create(MedItems.VENDA, 3);
            var r1 = new org.bukkit.inventory.ShapelessRecipe(new org.bukkit.NamespacedKey(this, "med_venda"), venda);
            r1.addIngredient(2, org.bukkit.Material.PAPER);
            r1.addIngredient(1, org.bukkit.Material.STRING);
            getServer().addRecipe(r1);
            var ferula = MedItems.create(MedItems.FERULA, 2);
            var r2 = new org.bukkit.inventory.ShapelessRecipe(new org.bukkit.NamespacedKey(this, "med_ferula"), ferula);
            r2.addIngredient(2, org.bukkit.Material.STICK);
            r2.addIngredient(1, org.bukkit.Material.STRING);
            getServer().addRecipe(r2);
        } catch (Throwable t) {
            getLogger().warning("No se pudieron registrar las recetas medicas: " + t.getMessage());
        }
    }
}
