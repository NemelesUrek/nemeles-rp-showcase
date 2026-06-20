package com.nemeles.combat;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/** Snapshot de la config (se lee al arrancar). Dinero en CENTIMOS. */
public final class CombatConfig {

    public final Map<String, GunDef> guns = new HashMap<>();
    public final Map<String, Material> ammoItems = new HashMap<>();
    public final Map<String, Integer> ammoModelData = new HashMap<>();   // CMD por munición (0 = ninguno)

    // Gating de armas por nivel de alerta de ciudad / evento
    public boolean weaponGatingEnabled = true;
    public final Map<String,Integer> tierByAlert = new HashMap<>();   // VERDE->0 ... BLANCA->2
    public String gatingPermPrefix = "nemeles.weapon.tier";
    public String gatingDenyMsg = "&cEsta arma solo se puede usar en alerta alta o eventos.";

    public final Map<String, GunSkin> skins = new HashMap<>();   // skins cosmeticas: id -> arma base + model-data

    public double headshotMult;
    public double hitBoxGrow;   // cuanto "engorda" el hitbox del raytrace de balas (0 = exacto; 0.4 = muy perdonon)
    // Caida de dano por distancia: del 100% hasta 'startDist', baja linealmente hasta 'minPct' en 'endDist'.
    public boolean damageFalloff;
    public double falloffStartDist, falloffEndDist, falloffMinPct;

    public int bleedoutSeconds, bleedoutSecondsTurf, reboundWindowSeconds;
    public double reboundMultiplier, freezeRadius;
    public String pose;
    public int crawlWindowSeconds, surrenderMinSeconds;
    public float crawlSpeed;
    public int stabilizeBonusSeconds, stabilizeMaxBonusSeconds, transfuseChannelSeconds, transfuseXp;

    public String hospitalNpcName;
    public long hospitalFeeCents;
    public boolean hospitalOnlyWithoutMedics;
    public int hospitalMaxMedics;

    public boolean noNaturalRegen;
    public double rescueFeePercent;
    public int rescueItemsLost;
    public org.bukkit.Location rescueLocation;   // null = spawn del mundo

    public int channelSeconds, channelFloorSeconds, reviveXp, postReviveImmunitySeconds, cooldownPairSeconds;
    public double reviveRadius, reviveHealth;
    public boolean requireItem, pauseBleedWhileChannel, sameFactionOrAllyOnly;
    public Material reviveItem;
    public long reviveFeeCents;

    public int deathDirtyPct, deathCashPct, blackzoneDirtyPct;
    public String contrabandPolicy, blackzoneContrabandPolicy, permadeathAction;
    public boolean blackzoneKillIsCrime;

    public int tagSeconds, respawnImmunitySeconds;

    public int crimeAssaultWound, crimeHomicideCivilian, crimeHomicidePolice, crimeHomicideVigilante, policeLegalKillStars;
    public long bountyCents;            // recompensa fija al POLICIA por kill legal de buscado
    public long bountyPerStarCents;     // cazarrecompensas: recompensa por estrella al matar a un buscado (cualquiera)

    public static CombatConfig from(FileConfiguration c) {
        CombatConfig cfg = new CombatConfig();

        ConfigurationSection ws = c.getConfigurationSection("weapons");
        if (ws != null) {
            for (String id : ws.getKeys(false)) {
                ConfigurationSection g = ws.getConfigurationSection(id);
                if (g == null) continue;
                GunDef d = new GunDef();
                d.id = id;
                d.baseItem = material(g.getString("base-item", "IRON_HOE"), Material.IRON_HOE);
                d.damage = g.getDouble("damage", 5.0);
                double rate = g.getDouble("rate", 2.0);
                d.fireDelayMs = (long) (1000.0 / Math.max(0.1, rate));
                d.range = g.getDouble("range", 25);
                d.magSize = Math.max(1, g.getInt("mag", 12));
                String ammo = g.getString("ammo", "none");
                d.ammoId = (ammo == null || ammo.equalsIgnoreCase("none")) ? null : ammo;
                d.reloadTicks = g.getInt("reload-ticks", 30);
                d.pellets = Math.max(1, g.getInt("pellets", 1));
                d.legal = g.getBoolean("legal", true);
                d.modelData = Math.max(0, g.getInt("model-data", 0));
                d.fireSound = g.getString("fire-sound", "");
                d.tier = Math.max(0, g.getInt("tier", 0));
                cfg.guns.put(id, d);
            }
        }
        double spDef = c.getDouble("weapon-global.spread-degrees-default", 1.5);
        double spSmg = c.getDouble("weapon-global.spread-degrees-smg", 3.0);
        double spShot = c.getDouble("weapon-global.spread-degrees-shotgun", 6.0);
        for (GunDef d : cfg.guns.values()) {
            d.spreadDegrees = d.id.equalsIgnoreCase("smg") ? spSmg
                    : d.id.equalsIgnoreCase("shotgun") ? spShot : spDef;
        }
        cfg.headshotMult = c.getDouble("weapon-global.headshot-mult", 1.0);
        cfg.hitBoxGrow = Math.max(0.0, c.getDouble("weapon-global.hit-box-grow", 0.1));   // antes 0.4 (perdonaba mucho)
        cfg.damageFalloff = c.getBoolean("weapon-global.damage-falloff", true);
        cfg.falloffStartDist = Math.max(0.0, c.getDouble("weapon-global.falloff-start-distance", 12.0));
        cfg.falloffEndDist = Math.max(cfg.falloffStartDist + 0.01, c.getDouble("weapon-global.falloff-end-distance", 40.0));
        cfg.falloffMinPct = Math.max(0.0, Math.min(100.0, c.getDouble("weapon-global.falloff-min-damage-pct", 35.0)));

        cfg.weaponGatingEnabled = c.getBoolean("weapon-gating.enabled", true);
        cfg.gatingPermPrefix = c.getString("weapon-gating.bypass-permission-prefix", "nemeles.weapon.tier");
        cfg.gatingDenyMsg = c.getString("weapon-gating.deny-message", cfg.gatingDenyMsg);
        ConfigurationSection ga = c.getConfigurationSection("weapon-gating.by-alert");
        if (ga != null) for (String lvl : ga.getKeys(false)) cfg.tierByAlert.put(lvl.toUpperCase(java.util.Locale.ROOT), Math.max(0, ga.getInt(lvl, 0)));
        if (cfg.tierByAlert.isEmpty()) { cfg.tierByAlert.put("VERDE",0); cfg.tierByAlert.put("AMARILLA",0); cfg.tierByAlert.put("NARANJA",1); cfg.tierByAlert.put("ROJA",2); cfg.tierByAlert.put("BLANCA",2); }

        ConfigurationSection as = c.getConfigurationSection("ammo");
        if (as != null) {
            for (String id : as.getKeys(false)) {
                ConfigurationSection a = as.getConfigurationSection(id);
                String mat = a != null ? a.getString("item", "IRON_NUGGET") : "IRON_NUGGET";
                cfg.ammoItems.put(id, material(mat, Material.IRON_NUGGET));
                cfg.ammoModelData.put(id, a != null ? Math.max(0, a.getInt("model-data", 0)) : 0);
            }
        }

        ConfigurationSection sk = c.getConfigurationSection("skins");
        if (sk != null) {
            for (String id : sk.getKeys(false)) {
                ConfigurationSection k = sk.getConfigurationSection(id);
                if (k == null) continue;
                GunSkin gs = new GunSkin();
                gs.id = id;
                gs.weapon = k.getString("weapon", "");
                gs.modelData = Math.max(0, k.getInt("model-data", 0));
                gs.name = k.getString("name", id);
                cfg.skins.put(id, gs);
            }
        }

        cfg.bleedoutSeconds = c.getInt("downed.bleedout-seconds", 300);
        cfg.bleedoutSecondsTurf = c.getInt("downed.bleedout-seconds-turf", 210);
        cfg.reboundMultiplier = c.getDouble("downed.rebound-multiplier", 0.5);
        cfg.reboundWindowSeconds = c.getInt("downed.rebound-window-seconds", 180);
        cfg.freezeRadius = c.getDouble("downed.freeze-radius", 1.5);
        cfg.pose = c.getString("downed.pose", "SWIMMING");
        cfg.crawlWindowSeconds = c.getInt("downed.crawl-window-seconds", 0);   // 0 = tumbado y QUIETO (no caminas)
        cfg.crawlSpeed = (float) c.getDouble("downed.crawl-speed", 0.08);
        cfg.surrenderMinSeconds = c.getInt("downed.surrender-min-seconds", 60);
        cfg.stabilizeBonusSeconds = c.getInt("downed.stabilize.bonus-seconds", 120);
        cfg.stabilizeMaxBonusSeconds = c.getInt("downed.stabilize.max-bonus-seconds", 360);
        cfg.transfuseChannelSeconds = c.getInt("downed.stabilize.channel-seconds", 4);
        cfg.transfuseXp = c.getInt("downed.stabilize.xp", 12);

        cfg.hospitalNpcName = c.getString("hospital.npc-name", "hospital").toLowerCase(java.util.Locale.ROOT).trim();
        cfg.hospitalFeeCents = Math.round(c.getDouble("hospital.fee", 2000) * 100.0);
        cfg.hospitalOnlyWithoutMedics = c.getBoolean("hospital.only-without-medics", true);
        cfg.hospitalMaxMedics = c.getInt("hospital.max-medics-online", 2);

        cfg.noNaturalRegen = c.getBoolean("body.no-natural-regen", true);
        cfg.rescueFeePercent = c.getDouble("downed.rescue.fee-percent", 25.0);
        cfg.rescueItemsLost = c.getInt("downed.rescue.items-lost", 3);
        if (c.isConfigurationSection("downed.rescue.location")) {
            ConfigurationSection rl = c.getConfigurationSection("downed.rescue.location");
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(rl.getString("world", "world"));
            if (w != null) cfg.rescueLocation = new org.bukkit.Location(w,
                    rl.getDouble("x"), rl.getDouble("y"), rl.getDouble("z"));
        }

        cfg.channelSeconds = Math.max(1, c.getInt("revive.channel-seconds", 8));
        cfg.channelFloorSeconds = Math.max(1, c.getInt("revive.channel-floor-seconds", 3));
        cfg.reviveXp = c.getInt("revive.xp", 15);
        cfg.reviveRadius = c.getDouble("revive.radius", 2.5);
        cfg.reviveHealth = c.getDouble("revive.health", 6.0);
        cfg.requireItem = c.getBoolean("revive.require-item", true);
        cfg.reviveItem = material(c.getString("revive.item", "GLISTERING_MELON_SLICE"), Material.GLISTERING_MELON_SLICE);
        cfg.pauseBleedWhileChannel = c.getBoolean("revive.pause-bleed-while-channel", true);
        cfg.sameFactionOrAllyOnly = c.getBoolean("revive.same-faction-or-ally-only", true);
        cfg.reviveFeeCents = Math.round(c.getDouble("revive.fee", 150) * 100.0);
        cfg.cooldownPairSeconds = c.getInt("revive.cooldown-per-pair-seconds", 600);
        cfg.postReviveImmunitySeconds = c.getInt("revive.post-revive-immunity-seconds", 5);

        cfg.deathDirtyPct = c.getInt("death.lose-dirty-percent", 30);
        cfg.deathCashPct = c.getInt("death.lose-cash-percent", 0);
        cfg.contrabandPolicy = c.getString("death.contraband-policy", "SINK");

        cfg.blackzoneDirtyPct = c.getInt("blackzone.lose-dirty-percent", 100);
        cfg.blackzoneContrabandPolicy = c.getString("blackzone.contraband-policy", "DROP");
        cfg.permadeathAction = c.getString("blackzone.permadeath-action", "WIPE_INVENTORY");
        cfg.blackzoneKillIsCrime = c.getBoolean("blackzone.kill-is-crime", false);

        cfg.tagSeconds = c.getInt("combat.tag-seconds", 15);
        cfg.respawnImmunitySeconds = c.getInt("combat.respawn-immunity-seconds", 8);

        cfg.crimeAssaultWound = c.getInt("crime.assault-wound", 25);
        cfg.crimeHomicideCivilian = c.getInt("crime.homicide-civilian", 40);
        cfg.crimeHomicidePolice = c.getInt("crime.homicide-police", 60);
        cfg.crimeHomicideVigilante = c.getInt("crime.homicide-vigilante", 20);
        cfg.policeLegalKillStars = c.getInt("crime.police-legal-kill-stars", 3);
        cfg.bountyCents = Math.round(c.getDouble("crime.bounty", 0) * 100.0);
        cfg.bountyPerStarCents = Math.round(Math.max(0.0, c.getDouble("crime.bounty-per-star", 150)) * 100.0);
        return cfg;
    }

    private static Material material(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name.toUpperCase());
        return m != null ? m : def;
    }
}
