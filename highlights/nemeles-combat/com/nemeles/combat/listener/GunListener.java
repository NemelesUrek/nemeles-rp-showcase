package com.nemeles.combat.listener;

import com.nemeles.combat.AmmoManager;
import com.nemeles.combat.CombatTagManager;
import com.nemeles.combat.DownedManager;
import com.nemeles.combat.GunDef;
import com.nemeles.combat.GunRegistry;
import com.nemeles.core.api.NemelesApi;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Disparo de armas custom: hitscan (raytrace) + FX vanilla = paridad Java/Bedrock. */
public final class GunListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final GunRegistry guns;
    private final AmmoManager ammo;
    private final DownedManager downed;
    private final CombatTagManager tags;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private com.nemeles.combat.body.BodyManager body;   // sistema medico por partes (opcional)
    public void setBody(com.nemeles.combat.body.BodyManager body) { this.body = body; }

    private com.nemeles.combat.render.GunModelService gunModel;   // modelo 3D del arma (opcional, soft-dep)
    public void setGunModel(com.nemeles.combat.render.GunModelService gunModel) { this.gunModel = gunModel; }

    // Caida de dano por distancia (config). Del 100% hasta falloffStart, baja a falloffMinMult en falloffEnd.
    private boolean falloffEnabled;
    private double falloffStart, falloffEnd = 1.0, falloffMinMult = 1.0;
    public void setFalloff(boolean enabled, double start, double end, double minPct) {
        this.falloffEnabled = enabled;
        this.falloffStart = Math.max(0.0, start);
        this.falloffEnd = Math.max(this.falloffStart + 0.01, end);
        this.falloffMinMult = Math.max(0.0, Math.min(1.0, minPct / 100.0));
    }

    // === Gating de armas por nivel de alerta / evento (estilo GTA RP) ===
    private boolean gatingEnabled;
    private java.util.Map<String,Integer> tierByAlert = java.util.Collections.emptyMap();
    private String gatingPermPrefix = "nemeles.weapon.tier";
    private String gatingDenyMsg = "§cEsta arma solo se puede usar en alerta alta o eventos.";
    private boolean papiPresent;
    private java.lang.reflect.Method papiMethod;

    public void setGating(com.nemeles.combat.CombatConfig cfg) {
        this.gatingEnabled = cfg.weaponGatingEnabled;
        this.tierByAlert = cfg.tierByAlert;
        this.gatingPermPrefix = cfg.gatingPermPrefix;
        this.gatingDenyMsg = org.bukkit.ChatColor.translateAlternateColorCodes('&', cfg.gatingDenyMsg);
        this.papiPresent = org.bukkit.Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (this.papiPresent) {
            try { this.papiMethod = Class.forName("me.clip.placeholderapi.PlaceholderAPI").getMethod("setPlaceholders", Player.class, String.class); }
            catch (Throwable ignored) { this.papiPresent = false; }
        }
    }

    private String alertLevel(Player p) {
        if (!papiPresent || papiMethod == null) return "VERDE";
        try {
            Object r = papiMethod.invoke(null, p, "%cityalert_level%");
            if (r == null) return "VERDE";
            String v = r.toString().replaceAll("[&§][0-9A-Fa-fK-Ok-or]", "").trim().toUpperCase(java.util.Locale.ROOT);
            return v.isEmpty() ? "VERDE" : v;
        } catch (Throwable t) { return "VERDE"; }
    }

    /** Tier maximo de arma permitido al jugador AHORA (por alerta de ciudad + permiso/evento). */
    private int allowedTier(Player p) {
        if (p.isOp() || p.hasPermission("nemeles.weapon.all")) return 99;
        int allowed = tierByAlert.getOrDefault(alertLevel(p), 0);
        for (int t = 3; t >= 1; t--) { if (p.hasPermission(gatingPermPrefix + t)) { allowed = Math.max(allowed, t); break; } }
        return allowed;
    }

    public GunListener(Plugin plugin, GunRegistry guns, AmmoManager ammo, DownedManager downed, CombatTagManager tags) {
        this.plugin = plugin;
        this.guns = guns;
        this.ammo = ammo;
        this.downed = downed;
        this.tags = tags;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onShoot(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();
        GunDef gun = guns.fromItem(it);
        if (gun == null) return;
        e.setCancelled(true);
        if (downed.isDowned(p.getUniqueId())) return;
        if (ammo.isReloading(p.getUniqueId())) return;

        try {
            if (NemelesApi.regions().isSafezone(p.getLocation())) {
                p.sendActionBar(LEGACY.deserialize("§7Zona segura: armas bloqueadas"));
                return;
            }
        } catch (Throwable ignored) { }

        // GATING por nivel de alerta / evento: armas de tier alto solo en alerta alta o evento (o con permiso)
        if (gatingEnabled && gun.tier > 0 && gun.tier > allowedTier(p)) {
            p.sendActionBar(LEGACY.deserialize(gatingDenyMsg));
            return;
        }

        // sneak = recarga manual
        if (p.isSneaking() && gun.ammoId != null) { ammo.reload(p, it, gun); return; }

        long now = System.currentTimeMillis();
        if (now - cooldowns.getOrDefault(p.getUniqueId(), 0L) < gun.fireDelayMs) return;

        if (gun.ammoId != null) {
            int mag = guns.mag(it);
            if (mag <= 0) { ammo.reload(p, it, gun); return; }
            guns.setMag(it, mag - 1);
            p.getInventory().setItemInMainHand(it);
        }
        cooldowns.put(p.getUniqueId(), now);
        int cdTicks = (int) (gun.fireDelayMs / 50);
        if (cdTicks > 0) p.setCooldown(gun.baseItem, cdTicks);

        // ANIMACION de disparo 3a persona (propaga sola a Bedrock); una sola vez por disparo, no por pellet.
        if (gunModel != null) gunModel.onShoot(p, gun);
        // RETROCESO 1a persona DESACTIVADO: el flipbook de custom_model_data pisaba la skin equipada (volvia a base).
        // Para reactivarlo sin romper skins habria que hacerlo por kick de camara (no toca el modelo). Pendiente.
        // if (gunModel != null) gunModel.playRecoilFp(p, gun);

        Location eye = p.getEyeLocation();
        // Sonido de disparo: del pack (realista, ej deagle/ak/tommy/pump) si la config lo define; si no, vanilla.
        if (gun.fireSound != null && !gun.fireSound.isBlank()) {
            try { p.getWorld().playSound(eye, gun.fireSound, org.bukkit.SoundCategory.PLAYERS, 1.0f, 1.0f); }
            catch (Throwable t) { p.getWorld().playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.6f); }
        } else {
            p.getWorld().playSound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.6f);
        }

        final com.nemeles.core.api.vehicle.VehicleService veh = vehicleService();
        // PUNTERIA REAL: brazos/manos heridos = el arma tiembla (spread extra del sistema medico)
        double spread = gun.spreadDegrees;
        if (body != null) { try { spread += body.aimSpreadExtra(p.getUniqueId()); } catch (Throwable ignored) { } }
        if (p.isInsideVehicle()) spread += 3.0;   // DRIVE-BY: disparar desde un vehículo es menos preciso
        for (int i = 0; i < Math.max(1, gun.pellets); i++) {
            Vector dir = applySpread(eye.getDirection(), spread);
            if (i == 0) muzzleFlash(eye, dir);   // 1 fogonazo por disparo (no por pellet)
            // 1) RAYTRACE A BLOQUES: las balas paran en paredes y nos dan el punto de impacto real.
            RayTraceResult blockR = p.getWorld().rayTraceBlocks(eye, dir, gun.range,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            double wallDist = (blockR != null && blockR.getHitPosition() != null)
                    ? eye.toVector().distance(blockR.getHitPosition()) : gun.range;
            // 2) ENTIDADES, limitadas a la distancia de la pared (un muro tapa el disparo).
            RayTraceResult r = p.getWorld().rayTraceEntities(eye, dir, wallDist, guns.hitBoxGrow(),
                    ent -> !ent.equals(p) && (ent instanceof LivingEntity || isVehicle(veh, ent)));
            double endDist = wallDist;
            if (r != null && r.getHitPosition() != null) endDist = eye.toVector().distance(r.getHitPosition());
            if (i == 0) drawTracer(eye, dir, endDist);   // tracer fino que PARA en el impacto (1 por disparo)
            if (r != null) {
                Entity hit = r.getHitEntity();
                if (hit instanceof LivingEntity v) {
                    Vector hpv = r.getHitPosition();
                    // ZONA DE CUERPO para TODA entidad viva (cabeza x2.0, brazo x0.7, pie x0.55...)
                    com.nemeles.combat.body.BodyPart part = (hpv != null)
                            ? com.nemeles.combat.body.BodyPart.locate(v, hpv)
                            : com.nemeles.combat.body.BodyPart.TORSO_SUP;
                    bloodFx(v, hpv, part);   // sangre escalada por la zona (headshot = mas)
                    // jugadores: ademas registra la herida localizada (dolor/sangrado/fractura...)
                    if (body != null && v instanceof Player victim) {
                        try { body.wound(victim, part, com.nemeles.combat.body.BodyManager.WoundType.BALA,
                                gun.damage / 5.0); } catch (Throwable ignored) { }
                    }
                    handleHit(p, v, gun, part.gunMult, part);   // dano por zona + feedback al tirador
                } else if (veh != null) {
                    Vector hp = r.getHitPosition();
                    Location impact = hp != null ? hp.toLocation(p.getWorld()) : hit.getLocation();
                    try { veh.damageFromShot(hit, impact, p); } catch (Throwable ignored) { }
                }
            } else if (blockR != null) {
                blockImpactFx(blockR, dir);   // SALPICADURA + sonido segun el MATERIAL del bloque
            }
            if (r == null && i == 0) whizzBy(p, eye, dir, endDist);   // bala que pasa cerca = silbido
        }
    }

    private com.nemeles.core.api.vehicle.VehicleService vehicleService() {
        try { return NemelesApi.vehicles(); } catch (Throwable t) { return null; }
    }

    private static boolean isVehicle(com.nemeles.core.api.vehicle.VehicleService veh, Entity ent) {
        if (veh == null) return false;
        try { return veh.isVehicleEntity(ent); } catch (Throwable t) { return false; }
    }

    private void handleHit(Player shooter, LivingEntity victim, GunDef gun, double bodyMult,
                           com.nemeles.combat.body.BodyPart part) {
        if (victim instanceof Player vp) {
            try { if (NemelesApi.regions().isSafezone(vp.getLocation())) return; } catch (Throwable ignored) { }
            tags.tag(shooter.getUniqueId(), vp.getUniqueId());
        }
        double dmg = gun.damage * bodyMult;
        // Caida de dano por distancia: a bocajarro 100%, lejos hasta falloffMinMult del dano base.
        if (falloffEnabled) {
            double dist = shooter.getEyeLocation().distance(victim.getLocation());
            if (dist > falloffStart) {
                double ratio = Math.min(1.0, (dist - falloffStart) / (falloffEnd - falloffStart));
                dmg *= 1.0 - ratio * (1.0 - falloffMinMult);
            }
        }
        hitFeedback(shooter, part, dmg);   // marcador de impacto (sonido + actionbar segun zona)
        victim.damage(dmg, shooter);   // pasa por FactionFFListener + CombatDamageListener
    }

    /** Marcador de impacto al TIRADOR: sonido segun la zona + actionbar con la parte y el dano aplicado. */
    private void hitFeedback(Player shooter, com.nemeles.combat.body.BodyPart part, double dmg) {
        int d = (int) Math.round(dmg);
        if (part == com.nemeles.combat.body.BodyPart.CABEZA) {
            shooter.playSound(shooter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 2.0f);
            shooter.sendActionBar(LEGACY.deserialize("§c§l✪ HEADSHOT §r§7» §f-" + d + " §c❤"));
        } else if (part.isVital()) {
            shooter.playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, 1.4f);
            shooter.sendActionBar(LEGACY.deserialize("§e● §7Impacto: §f" + part.display + " §7(-" + d + ")"));
        } else {
            shooter.playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.7f, 0.9f);
            shooter.sendActionBar(LEGACY.deserialize("§a○ §7" + part.display + " §7(-" + d + ")"));
        }
    }

    private Vector applySpread(Vector dir, double degrees) {
        if (degrees <= 0) return dir.clone();
        double spread = Math.tan(Math.toRadians(degrees));
        Vector rand = new Vector(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5).multiply(spread);
        return dir.clone().add(rand).normalize();
    }

    /** Jitter de pitch para que el fuego automatico no suene como un loop uniforme. */
    private static float rp(float base, float spread) {
        return base + (float) ((Math.random() - 0.5) * 2.0 * spread);
    }

    private enum MatClass { HARD, DEEPSLATE, METAL, WOOD, GLASS, SOFT, SAND, PLANT, SNOW, GENERIC }

    /** Clasifica el bloque golpeado en una clase de material (para elegir sonido/particulas de impacto). */
    private static MatClass classify(org.bukkit.block.Block b) {
        org.bukkit.Material mat = b.getType();
        String n = mat.name();
        if (n.contains("IRON") || n.contains("GOLD_BLOCK") || n.contains("GILDED") || n.contains("COPPER")
                || n.contains("NETHERITE") || n.contains("ANVIL") || n.contains("CHAIN") || n.contains("RAIL")
                || n.contains("HOPPER") || n.contains("CAULDRON") || n.contains("BELL") || n.contains("LANTERN")
                || n.endsWith("_BARS") || n.contains("GRATE") || n.equals("BLAST_FURNACE") || n.equals("FURNACE")
                || n.equals("SMITHING_TABLE") || org.bukkit.Tag.ANVIL.isTagged(mat)) return MatClass.METAL;
        if (n.contains("GLASS") || n.contains("AMETHYST") || n.equals("SEA_LANTERN") || n.equals("BEACON")
                || org.bukkit.Tag.ICE.isTagged(mat)) return MatClass.GLASS;
        if (org.bukkit.Tag.LOGS.isTagged(mat) || org.bukkit.Tag.PLANKS.isTagged(mat)
                || org.bukkit.Tag.WOODEN_DOORS.isTagged(mat) || org.bukkit.Tag.WOODEN_FENCES.isTagged(mat)
                || org.bukkit.Tag.WOODEN_SLABS.isTagged(mat) || org.bukkit.Tag.WOODEN_STAIRS.isTagged(mat)
                || org.bukkit.Tag.WOODEN_TRAPDOORS.isTagged(mat) || org.bukkit.Tag.STANDING_SIGNS.isTagged(mat)
                || n.contains("BAMBOO") || n.equals("CRAFTING_TABLE") || n.equals("BARREL") || n.equals("CHEST")
                || n.equals("BOOKSHELF") || n.equals("LADDER") || n.equals("NOTE_BLOCK") || n.equals("JUKEBOX")
                || n.equals("LECTERN") || n.equals("CARTOGRAPHY_TABLE") || n.equals("FLETCHING_TABLE")) return MatClass.WOOD;
        if (n.contains("SNOW")) return MatClass.SNOW;
        if (org.bukkit.Tag.WOOL.isTagged(mat) || org.bukkit.Tag.CARPETS.isTagged(mat) || n.contains("CARPET")
                || org.bukkit.Tag.BEDS.isTagged(mat) || n.contains("SLIME") || n.contains("HONEY")
                || n.contains("SPONGE") || n.contains("HAY") || n.contains("WART_BLOCK")
                || n.contains("MOSS_BLOCK") || n.contains("MOSS_CARPET")) return MatClass.SOFT;
        if (org.bukkit.Tag.SAND.isTagged(mat) || n.equals("GRAVEL") || org.bukkit.Tag.DIRT.isTagged(mat)
                || n.equals("GRASS_BLOCK") || n.contains("CLAY") || n.contains("MUD") || n.contains("MYCELIUM")
                || n.contains("PODZOL") || n.contains("CONCRETE_POWDER") || n.equals("FARMLAND")
                || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL")) return MatClass.SAND;
        if (org.bukkit.Tag.LEAVES.isTagged(mat) || org.bukkit.Tag.CROPS.isTagged(mat) || org.bukkit.Tag.FLOWERS.isTagged(mat)
                || org.bukkit.Tag.SAPLINGS.isTagged(mat) || n.equals("SHORT_GRASS") || n.equals("TALL_GRASS")
                || n.equals("FERN") || n.equals("LARGE_FERN") || n.contains("VINE") || n.contains("MUSHROOM")
                || n.contains("SUGAR_CANE") || n.contains("KELP")) return MatClass.PLANT;
        if (n.contains("DEEPSLATE")) return MatClass.DEEPSLATE;
        if (org.bukkit.Tag.BASE_STONE_OVERWORLD.isTagged(mat) || org.bukkit.Tag.BASE_STONE_NETHER.isTagged(mat)
                || org.bukkit.Tag.TERRACOTTA.isTagged(mat) || n.contains("STONE") || n.contains("BRICK")
                || n.contains("CONCRETE") || n.contains("BLACKSTONE") || n.contains("QUARTZ") || n.contains("OBSIDIAN")
                || n.contains("PRISMARINE") || n.contains("BASALT") || n.contains("TUFF") || n.contains("CALCITE")
                || n.contains("ORE") || n.contains("NETHERRACK") || n.contains("END_STONE") || n.contains("PURPUR")) return MatClass.HARD;
        try {
            org.bukkit.Sound hs = b.getBlockData().getSoundGroup().getHitSound();
            if (hs == Sound.BLOCK_DEEPSLATE_HIT) return MatClass.DEEPSLATE;
            if (hs == Sound.BLOCK_STONE_HIT) return MatClass.HARD;
            if (hs == Sound.BLOCK_WOOD_HIT) return MatClass.WOOD;
            if (hs == Sound.BLOCK_METAL_HIT) return MatClass.METAL;
            if (hs == Sound.BLOCK_GLASS_HIT) return MatClass.GLASS;
            if (hs == Sound.BLOCK_WOOL_HIT) return MatClass.SOFT;
            if (hs == Sound.BLOCK_GRAVEL_HIT || hs == Sound.BLOCK_SAND_HIT) return MatClass.SAND;
            if (hs == Sound.BLOCK_GRASS_HIT) return MatClass.PLANT;
        } catch (Throwable ignored) { }
        return MatClass.GENERIC;
    }

    /** Fogonazo seco en la boca del canon: destello + brasas + humo (sin confeti). */
    private void muzzleFlash(Location eye, Vector dir) {
        World w = eye.getWorld();
        if (w == null) return;
        Location m = eye.clone().add(dir.clone().multiply(1.1)).add(0, -0.2, 0);
        w.spawnParticle(Particle.FLASH, m, 1, 0, 0, 0, 0);                        // 1 destello (sprite grande)
        w.spawnParticle(Particle.SMALL_FLAME, m, 3, 0.015, 0.015, 0.015, 0.004);  // brasas finas
        Location mf = m.clone().add(dir.clone().multiply(0.18));
        w.spawnParticle(Particle.SMOKE, mf, 1, 0.02, 0.02, 0.02, 0.008);          // 1 voluta de humo
        w.spawnParticle(Particle.CRIT, m, 2, 0.01, 0.01, 0.01, 0.05);             // chispa del canon
    }

    /** Estela fina blanco-calido que PARA en el impacto (con jitter + punta caliente). */
    private void drawTracer(Location eye, Vector dir, double dist) {
        World w = eye.getWorld();
        if (w == null) return;
        Particle.DustOptions core = new Particle.DustOptions(Color.fromRGB(255, 240, 200), 0.5f);
        double start = 1.2;
        double step = (dist > 40) ? 1.6 : 1.2;   // menos denso en tiros largos
        for (double d = start; d < dist; d += step) {
            Location pt = eye.clone().add(dir.clone().multiply(d));
            w.spawnParticle(Particle.DUST, pt, 1, 0.02, 0.02, 0.02, 0, core);
        }
        Location tip = eye.clone().add(dir.clone().multiply(Math.max(start, dist - 0.4)));
        w.spawnParticle(Particle.CRIT, tip, 1, 0, 0, 0, 0);
    }

    /** Impacto en bloque: esquirlas del material + sonido en capas por material + ricochet en superficies duras. */
    private void blockImpactFx(RayTraceResult blockR, Vector dir) {
        org.bukkit.block.Block b = blockR.getHitBlock();
        if (b == null) return;
        World w = b.getWorld();
        if (w == null) return;
        Vector hp = blockR.getHitPosition();
        Location at = (hp != null) ? hp.toLocation(w) : b.getLocation().add(0.5, 0.5, 0.5);
        org.bukkit.block.data.BlockData bd = b.getBlockData();
        MatClass mc = classify(b);
        Vector nrm = (blockR.getHitBlockFace() != null)
                ? blockR.getHitBlockFace().getDirection() : dir.clone().multiply(-1);
        Location off = at.clone().add(nrm.clone().multiply(0.05));   // sacar las particulas de la pared
        w.spawnParticle(Particle.BLOCK, off, 8, 0.05, 0.05, 0.05, 0.02, bd);
        w.spawnParticle(Particle.SMOKE, off, 2, 0.03, 0.03, 0.03, 0.01);
        if (mc == MatClass.HARD || mc == MatClass.DEEPSLATE || mc == MatClass.METAL) {
            w.spawnParticle(Particle.ELECTRIC_SPARK, off, 3, 0.04, 0.04, 0.04, 0.06);
            w.spawnParticle(Particle.CRIT, off, 2, 0.02, 0.02, 0.02, 0.05);
        } else if (mc == MatClass.GLASS) {
            w.spawnParticle(Particle.BLOCK, off, 6, 0.10, 0.10, 0.10, 0.06, bd);
        } else if (mc == MatClass.SAND || mc == MatClass.SOFT) {
            w.spawnParticle(Particle.DUST_PLUME, off, 4, 0.06, 0.06, 0.06, 0.02);
        }
        playImpactSound(w, at, bd, mc);
        boolean hard = (mc == MatClass.HARD || mc == MatClass.DEEPSLATE || mc == MatClass.METAL);
        double ricChance = (mc == MatClass.METAL) ? 0.45 : 0.28;
        if (hard && Math.random() < ricChance) {
            w.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, org.bukkit.SoundCategory.PLAYERS, 0.35f, 2.0f);
            w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, org.bukkit.SoundCategory.PLAYERS, 0.22f, rp(1.85f, 0.1f));
            Vector deflect = nrm.clone().add(new Vector(Math.random() - 0.5, Math.random() * 0.5, Math.random() - 0.5)).normalize();
            for (double k = 0.25; k < 1.1; k += 0.3) {
                w.spawnParticle(Particle.CRIT, at.clone().add(deflect.clone().multiply(k)), 1, 0, 0, 0, 0.0);
            }
        }
    }

    /** Sonido de impacto de bala en capas (transitorio fuerte + cuerpo) segun el material golpeado. */
    private void playImpactSound(World w, Location at, org.bukkit.block.data.BlockData bd, MatClass mc) {
        final org.bukkit.SoundCategory CAT = org.bukkit.SoundCategory.PLAYERS;
        switch (mc) {
            case HARD -> {
                w.playSound(at, Sound.BLOCK_STONE_BREAK, CAT, 0.85f, rp(1.5f, 0.12f));
                w.playSound(at, Sound.BLOCK_STONE_HIT, CAT, 0.55f, rp(0.9f, 0.10f));
            }
            case DEEPSLATE -> {
                w.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, CAT, 0.85f, rp(1.45f, 0.12f));
                w.playSound(at, Sound.BLOCK_DEEPSLATE_HIT, CAT, 0.55f, rp(0.85f, 0.10f));
            }
            case METAL -> {
                w.playSound(at, Sound.BLOCK_ANVIL_LAND, CAT, 0.45f, rp(1.85f, 0.10f));
                w.playSound(at, Sound.BLOCK_NETHERITE_BLOCK_HIT, CAT, 0.7f, rp(1.3f, 0.12f));
            }
            case WOOD -> {
                w.playSound(at, Sound.BLOCK_WOOD_BREAK, CAT, 0.8f, rp(0.85f, 0.10f));
                w.playSound(at, Sound.BLOCK_WOOD_HIT, CAT, 0.55f, rp(1.25f, 0.12f));
            }
            case GLASS -> {
                w.playSound(at, Sound.BLOCK_GLASS_BREAK, CAT, 0.9f, rp(1.2f, 0.10f));
                w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, CAT, 0.4f, rp(1.6f, 0.10f));
            }
            case SAND -> {
                w.playSound(at, Sound.BLOCK_GRAVEL_BREAK, CAT, 0.75f, rp(0.8f, 0.08f));
                w.playSound(at, Sound.BLOCK_SAND_HIT, CAT, 0.5f, rp(0.9f, 0.08f));
            }
            case SOFT -> {
                w.playSound(at, Sound.BLOCK_WOOL_BREAK, CAT, 0.7f, rp(0.85f, 0.08f));
                w.playSound(at, Sound.BLOCK_WOOL_HIT, CAT, 0.5f, rp(0.95f, 0.08f));
            }
            case SNOW -> {
                w.playSound(at, Sound.BLOCK_SNOW_BREAK, CAT, 0.7f, rp(0.95f, 0.08f));
                w.playSound(at, Sound.BLOCK_SNOW_HIT, CAT, 0.5f, rp(1.0f, 0.08f));
            }
            case PLANT -> w.playSound(at, Sound.BLOCK_GRASS_BREAK, CAT, 0.6f, rp(1.1f, 0.10f));
            default -> {
                try {
                    org.bukkit.SoundGroup sg = bd.getSoundGroup();
                    w.playSound(at, sg.getBreakSound(), CAT, 0.7f, rp(1.1f, 0.10f));
                    w.playSound(at, sg.getHitSound(), CAT, 0.45f, rp(1.2f, 0.10f));
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Sangre al impactar a un ser vivo: trozos texturizados + niebla rojo->oscuro + thwack humedo. */
    private void bloodFx(LivingEntity v, Vector hp, com.nemeles.combat.body.BodyPart part) {
        World w = v.getWorld();
        if (w == null) return;
        Location at = (hp != null) ? hp.toLocation(w) : v.getEyeLocation().subtract(0, 0.3, 0);
        boolean head = part == com.nemeles.combat.body.BodyPart.CABEZA
                || part == com.nemeles.combat.body.BodyPart.CUELLO;
        int n = head ? 18 : (part.isVital() ? 12 : 7);
        org.bukkit.block.data.BlockData red = org.bukkit.Material.REDSTONE_BLOCK.createBlockData();
        w.spawnParticle(Particle.BLOCK, at, n, 0.10, 0.10, 0.10, 0.05, red);
        Particle.DustTransition mist = new Particle.DustTransition(
                Color.fromRGB(150, 0, 0), Color.fromRGB(60, 0, 0), 0.9f);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, Math.max(4, n / 2), 0.12, 0.12, 0.12, 0.0, mist);
        if (head) w.spawnParticle(Particle.DAMAGE_INDICATOR, at, 2, 0.06, 0.06, 0.06, 0.0);
        w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, org.bukkit.SoundCategory.PLAYERS, 0.9f, rp(0.85f, 0.10f));
        if (head) {
            w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, org.bukkit.SoundCategory.PLAYERS, 0.7f, 0.7f);
        } else if (!part.isVital()) {
            w.playSound(at, Sound.ENTITY_SLIME_HURT, org.bukkit.SoundCategory.PLAYERS, 0.35f, 1.2f);
        }
    }

    /** Silbido de bala que pasa cerca de OTRO jugador (solo a ese jugador, sin spam). */
    private void whizzBy(Player shooter, Location eye, Vector dir, double endDist) {
        World w = eye.getWorld();
        if (w == null) return;
        Vector origin = eye.toVector();
        Vector ndir = dir.clone().normalize();
        for (Player o : w.getNearbyPlayers(eye, endDist + 2)) {
            if (o.equals(shooter)) continue;
            Vector head = o.getEyeLocation().toVector();
            double t = head.clone().subtract(origin).dot(ndir);
            if (t < 1.5 || t > endDist) continue;
            Vector closest = origin.clone().add(ndir.clone().multiply(t));
            double miss = closest.distance(head);
            if (miss > 1.6) continue;
            Location whizzAt = closest.toLocation(w);
            float vol = (float) (0.85 - 0.45 * (miss / 1.6));
            float pit = 1.6f + (float) (Math.random() * 0.5f);
            o.playSound(whizzAt, Sound.ENTITY_BEE_LOOP, org.bukkit.SoundCategory.PLAYERS, vol, pit);
            o.playSound(whizzAt, Sound.ENTITY_ARROW_SHOOT, org.bukkit.SoundCategory.PLAYERS, 0.3f, 2.0f);
        }
    }
}
