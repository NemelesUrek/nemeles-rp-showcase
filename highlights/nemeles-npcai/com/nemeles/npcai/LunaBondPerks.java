package com.nemeles.npcai;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Poderes del "LAZO DE LUNA" (item del alma gemela). Solo funcionan para el DUEÑO del vínculo y con el item REAL
 * (tag PDC, no un POPPY renombrado). Cuatro poderes:
 *   (1) Clic derecho: Luna se teletransporta a tu lado y te ESCOLTA.
 *   (2) Mientras escolta, ATACA a quien te este atacando (tu asesina personal).
 *   (3) AURA pasiva: regeneracion + resistencia mientras lleves el Lazo.
 *   (4) PASE LIBRE: Luna (y los suyos) nunca te atacan (lo aplica HostilityManager via isSoulmate/isAlly).
 * Todo a prueba de fallos: sin Citizens o sin dueño, no actua.
 */
public final class LunaBondPerks implements Listener {

    private final Plugin plugin;
    private final BondManager bond;
    private final AffinityManager affinity;   // null-safe (para detectar que el dueño dejo de quererla -> traicion)
    private final Supplier<AiConfig> cfg;
    private final NamespacedKey lazoKey;
    private NpcArmorManager armor;             // opcional: para que Luna se BLINDE (no defienda en camiseta) al pelear

    private final Map<UUID, Long> guardUntil = new ConcurrentHashMap<>();   // owner -> hasta cuando Luna escolta
    private final Map<UUID, UUID> threat = new ConcurrentHashMap<>();        // owner -> entidad que lo ataco
    private final Map<UUID, Long> threatTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSummon = new ConcurrentHashMap<>();    // cooldown del clic
    private final Map<Integer, Long> lunaHit = new ConcurrentHashMap<>();    // cooldown de golpe de Luna
    private final Map<UUID, Long> nearSince = new ConcurrentHashMap<>();     // rival -> cuando entro en la zona del dueño
    private final Map<UUID, Long> jealousyMsgTime = new ConcurrentHashMap<>(); // rival -> ultimo aviso de celos
    private final Map<UUID, Long> cannotHitMsgTime = new ConcurrentHashMap<>(); // owner -> ultimo aviso de "no puedes golpearla" (throttle)

    public LunaBondPerks(Plugin plugin, BondManager bond, AffinityManager affinity, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.bond = bond;
        this.affinity = affinity;
        this.cfg = cfg;
        this.lazoKey = new NamespacedKey(plugin, "lazo_luna");
    }

    /** Inyecta el gestor de armadura para que Luna se blinde al ponerse a defender (no pelee en camiseta). */
    public void setArmor(NpcArmorManager armor) { this.armor = armor; }

    private boolean isLazo(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        try {
            org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
            Byte b = m.getPersistentDataContainer().get(lazoKey, PersistentDataType.BYTE);
            if (b != null && b == (byte) 1) return true;
            // Fallback por NOMBRE para Lazos creados antes del tag PDC. Seguro: el poder igual exige ser el alma gemela.
            if (m.hasDisplayName()) {
                String dn = ChatColor.stripColor(m.getDisplayName());
                return dn != null && dn.toLowerCase(java.util.Locale.ROOT).contains("lazo de luna");
            }
        } catch (Throwable t) { return false; }
        return false;
    }

    private boolean hasLazo(Player p) {
        try { for (ItemStack it : p.getInventory().getContents()) if (isLazo(it)) return true; }
        catch (Throwable ignored) { }
        return false;
    }

    /** ¿Es el alma gemela (dueño del Lazo)? Lo usa la hostilidad para el "pase libre". */
    public boolean isSoulmate(UUID id) { return bond != null && bond.enabled() && bond.isOwner(id); }

    /** ¿Este persona es Luna o uno de "los suyos" (pase libre para el alma gemela)? */
    public boolean isAlly(String personaKey) {
        if (bond == null || personaKey == null) return false;
        if (personaKey.equals(bond.persona())) return true;
        try { return cfg.get() != null && cfg.get().bond.allies.contains(personaKey); } catch (Throwable t) { return false; }
    }

    // ───────── (1)+(2) clic derecho con el Lazo: Luna acude y te defiende ─────────
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isLazo(e.getItem())) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!isSoulmate(p.getUniqueId())) { msg(p, "&dEl Lazo no late en tus manos... no es tuyo."); return; }
        long now = System.currentTimeMillis();
        Long last = lastSummon.get(p.getUniqueId());
        if (last != null && now - last < 15000) { msg(p, "&7Luna necesita un momento antes de volver a acudir."); return; }
        NPC luna = findLuna();
        if (luna == null || !luna.isSpawned() || luna.getEntity() == null) { msg(p, "&7Luna no aparece por ningun lado ahora mismo."); return; }
        lastSummon.put(p.getUniqueId(), now);
        try { luna.teleport(p.getLocation(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); } catch (Throwable ignored) { }
        guardUntil.put(p.getUniqueId(), now + 30000);   // 30 s de escolta
        // se prepara para defender: se BLINDA y DESENFUNDA (nada de pelear en camiseta y a puño).
        AiConfig c0 = cfg.get();
        gearUp(luna, c0 == null ? null : c0.match(bond.persona()));
        msg(p, "&d&lLuna acude a tu llamado &7y se pone a tu lado, lista para protegerte.");
        try {
            p.getWorld().playSound(p.getLocation(), "block.amethyst_block.chime", 1f, 1.3f);
            luna.getEntity().getWorld().spawnParticle(Particle.HEART, luna.getEntity().getLocation().add(0, 1.8, 0), 6, 0.3, 0.3, 0.3, 0.01);
        } catch (Throwable ignored) { }
    }

    /** Si el alma gemela recibe dano de una entidad, Luna la fija como objetivo (para defenderlo). */
    @EventHandler(ignoreCancelled = true)
    public void onOwnerHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isSoulmate(p.getUniqueId())) return;
        Entity dmg = e.getDamager();
        if (dmg instanceof Projectile pr && pr.getShooter() instanceof Entity src) dmg = src;
        if (dmg == null || dmg.getUniqueId().equals(p.getUniqueId())) return;
        NPC luna = findLuna();
        if (luna != null && luna.getEntity() != null && luna.getEntity().getUniqueId().equals(dmg.getUniqueId())) return; // no a la propia Luna
        threat.put(p.getUniqueId(), dmg.getUniqueId());
        threatTime.put(p.getUniqueId(), now());
    }

    /**
     * El alma gemela NO PUEDE hacerle daño a Luna por NINGUN medio: ella JAMAS recibe el golpe de su dueño,
     * venga por mano, flecha/tridente, pocion arrojadiza O PERSISTENTE (AreaEffectCloud), TNT que encendio o
     * su mascota. No hay traicion por pegarle: simplemente esquiva con dulzura. La unica ruta de ruptura que
     * queda es el DESAMOR (afinidad <= bond.betrayal-affinity), en jealousyTick.
     * Prioridad LOWEST + cancela: asi ningun otro handler la pone "en guardia" ni le viste armadura por el golpe.
     * ignoreCancelled=false: aunque otro plugin lo cancele antes, el dueño igual ve el aviso dulce.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = false)
    public void onLunaAttacked(EntityDamageByEntityEvent e) {
        if (bond == null || !bond.hasOwner()) return;
        Player attacker = resolveAttacker(e.getDamager());   // mano, flecha, pocion persistente, TNT o mascota del dueño
        if (attacker == null) return;
        Entity victim = e.getEntity();
        AiConfig c = cfg.get();
        NpcPersona per = (c == null || victim.getName() == null) ? null : c.match(ChatColor.stripColor(victim.getName()));
        if (per == null || !bond.isProtectedHit(attacker.getUniqueId(), per.key)) return;
        e.setDamage(0.0);
        e.setCancelled(true);   // el dueño NO puede dañarla, punto.

        // aviso dulce con throttle (no spamear si esta pegando rapido)
        UUID oid = attacker.getUniqueId();
        long now = now();
        Long last = cannotHitMsgTime.get(oid);
        if (last == null || now - last > 5000L) {
            cannotHitMsgTime.put(oid, now);
            msg(attacker, (c != null ? c.bond.cannotHitMsg : "&d{npc} esquiva tu golpe: no puedes hacerle daño.").replace("{npc}", lunaName(c)));
            try {
                NPC luna = findLuna();
                if (luna != null && luna.getEntity() != null) {
                    lookAt(luna.getEntity(), attacker);
                    luna.getEntity().getWorld().spawnParticle(Particle.HEART, luna.getEntity().getLocation().add(0, 1.8, 0), 4, 0.25, 0.25, 0.25, 0.01);
                }
            } catch (Throwable ignored) { }
        }
    }

    /**
     * El dueño tampoco puede PRENDER a Luna (fire aspect, bola de fuego, etc.): cancelamos la combustion que
     * provoque y le apagamos el fuego. (La lava/fuego ambiental los corta NpcDamageListener.onEnvDamage.)
     */
    @EventHandler(ignoreCancelled = true)
    public void onLunaCombust(org.bukkit.event.entity.EntityCombustByEntityEvent e) {
        if (bond == null || !bond.hasOwner()) return;
        Entity victim = e.getEntity();
        AiConfig c = cfg.get();
        NpcPersona per = (c == null || victim.getName() == null) ? null : c.match(ChatColor.stripColor(victim.getName()));
        if (per == null || !per.key.equals(bond.persona())) return;
        Player p = resolveAttacker(e.getCombuster());
        if (p == null || !bond.isProtectedHit(p.getUniqueId(), per.key)) return;
        e.setCancelled(true);
        try { if (victim instanceof LivingEntity le) le.setFireTicks(0); } catch (Throwable ignored) { }
    }

    /**
     * Resuelve al JUGADOR responsable de un daño, incluyendo vias INDIRECTAS suyas: proyectil (flecha/tridente/
     * pocion arrojadiza), pocion PERSISTENTE (AreaEffectCloud), TNT que encendio y mascota domesticada. Asi el
     * dueño no puede herir a Luna por ningun medio. null si no hay un jugador detras.
     */
    private static Player resolveAttacker(Entity dmg) {
        if (dmg == null) return null;
        if (dmg instanceof Player p) return p;
        if (dmg instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (dmg instanceof org.bukkit.entity.AreaEffectCloud aec && aec.getSource() instanceof Player p) return p;
        if (dmg instanceof org.bukkit.entity.TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (dmg instanceof org.bukkit.entity.Tameable tm && tm.isTamed() && tm.getOwner() instanceof Player p) return p;
        return null;
    }

    // ───────── CELOS: un rival que ronda a tu protegido siente el "miedo primordial" de Luna ─────────
    public void jealousyTick() {
        if (bond == null || !bond.enabled() || !bond.hasOwner()) return;
        UUID oid = bond.ownerId();
        if (oid == null) return;
        Player owner = Bukkit.getPlayer(oid);
        if (owner == null || !owner.isOnline()) { nearSince.clear(); return; }

        AiConfig c = cfg.get();
        // TRAICION por desamor: si el dueño dejo caer su afinidad con Luna por debajo del umbral, ella rompe el pacto.
        if (affinity != null && c != null) {
            try {
                int aff = affinity.get(bond.persona(), oid);
                if (aff <= c.bond.betrayalAffinity) { betray(owner); return; }
            } catch (Throwable ignored) { }
        }

        if (!hasLazo(owner)) { nearSince.clear(); jealousyMsgTime.clear(); return; }   // sin el Lazo, sin aura de celos
        double r = c != null ? c.bond.jealousyRadius : 5.0;
        if (r <= 0) return;
        String lunaName = lunaName(c);
        String jmsg = (c != null ? c.bond.jealousyMsg : "&5La mirada de {npc} esta sobre ti.").replace("{npc}", lunaName);
        long now = now();
        java.util.Set<UUID> present = new java.util.HashSet<>();
        try {
            for (Entity ent : owner.getNearbyEntities(r, r, r)) {
                if (!(ent instanceof Player rival)) continue;   // jugadores: el genero no se puede saber, asi que cualquiera que se acerque demasiado
                if (rival.getUniqueId().equals(oid)) continue;
                if (rival.getGameMode() == org.bukkit.GameMode.CREATIVE || rival.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (rival.hasPermission("nemeles.npcai.bypass")) continue;
                present.add(rival.getUniqueId());
                Long since = nearSince.putIfAbsent(rival.getUniqueId(), now);
                if (since != null && now - since < 1500) continue;   // ~1.5 s de gracia antes de asustarlo
                applyFear(rival);
                Long lastMsg = jealousyMsgTime.get(rival.getUniqueId());
                if (lastMsg == null || now - lastMsg > 8000) {
                    jealousyMsgTime.put(rival.getUniqueId(), now);
                    msg(rival, jmsg);
                }
            }
        } catch (Throwable ignored) { }
        nearSince.keySet().retainAll(present);   // olvida a los que ya se alejaron
    }

    private void applyFear(Player rival) {
        try {
            PotionEffectType blind = byName("BLINDNESS");
            PotionEffectType dark = byName("DARKNESS");
            PotionEffectType slow = byName("SLOWNESS", "SLOW");
            PotionEffectType weak = byName("WEAKNESS");
            PotionEffectType naus = byName("NAUSEA", "CONFUSION");
            if (blind != null) rival.addPotionEffect(new PotionEffect(blind, 70, 0, true, false, false));
            if (dark != null) rival.addPotionEffect(new PotionEffect(dark, 70, 0, true, false, false));
            if (slow != null) rival.addPotionEffect(new PotionEffect(slow, 70, 1, true, false, false));
            if (weak != null) rival.addPotionEffect(new PotionEffect(weak, 70, 1, true, false, false));
            if (naus != null) rival.addPotionEffect(new PotionEffect(naus, 70, 0, true, false, false));
            rival.getWorld().spawnParticle(Particle.SMOKE, rival.getEyeLocation(), 6, 0.2, 0.2, 0.2, 0.01);
            rival.getWorld().playSound(rival.getLocation(), "entity.warden.heartbeat", 0.6f, 0.6f);
        } catch (Throwable ignored) { }
    }

    /** Luna se siente usada/traicionada: rompe el pacto, destruye el Lazo (permanente) y MATA a su alma gemela. */
    public void betray(Player owner) {
        if (bond == null || owner == null) return;
        try {
            AiConfig c = cfg.get();
            String lunaName = lunaName(c);
            msg(owner, (c != null ? c.bond.betrayalOwnerMsg : "&d{npc} te traiciona.").replace("{npc}", lunaName));
            String bc = (c != null ? c.bond.betrayalBroadcast : "").replace("{npc}", lunaName);
            if (bc != null && !bc.isBlank()) Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bc));
            removeAllLazos(owner);                 // el Lazo se pierde PARA SIEMPRE
            bond.adminReset();                      // Luna queda libre de nuevo
            guardUntil.remove(owner.getUniqueId());
            NPC luna = findLuna();
            if (luna != null && luna.getEntity() != null) {
                lookAt(luna.getEntity(), owner);
                try { luna.teleport(owner.getLocation(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN); } catch (Throwable ignored) { }
                if (luna.getEntity() instanceof Player pl) { try { pl.swingMainHand(); } catch (Throwable ignored) { } }
            }
            try {
                owner.getWorld().strikeLightningEffect(owner.getLocation());   // solo efecto visual (sin fuego)
                owner.getWorld().spawnParticle(Particle.HEART, owner.getLocation().add(0, 1, 0), 10, 0.4, 0.5, 0.4, 0.02);
                owner.getWorld().playSound(owner.getLocation(), "entity.player.attack.crit", 1.2f, 0.8f);
            } catch (Throwable ignored) { }
            // la asesina cumple su palabra: muerte directa (no derribado).
            try { owner.setHealth(0.0); } catch (Throwable t) { try { owner.damage(1000.0); } catch (Throwable ignored) { } }
        } catch (Throwable ignored) { }
    }

    private void removeAllLazos(Player p) {
        try {
            ItemStack[] cont = p.getInventory().getContents();
            for (int i = 0; i < cont.length; i++) if (isLazo(cont[i])) p.getInventory().setItem(i, null);
            ItemStack off = p.getInventory().getItemInOffHand();
            if (isLazo(off)) p.getInventory().setItemInOffHand(null);
        } catch (Throwable ignored) { }
    }

    private String lunaName(AiConfig c) {
        try { NpcPersona per = c == null ? null : c.match(bond.persona()); if (per != null && per.name != null) return per.name; }
        catch (Throwable ignored) { }
        return "Luna";
    }

    // ───────── (3) aura: regen + resistencia mientras lleves el Lazo (tick cada ~3 s) ─────────
    public void auraTick() {
        if (bond == null || !bond.enabled() || !bond.hasOwner()) return;
        PotionEffectType regen = byName("REGENERATION");
        PotionEffectType res = byName("RESISTANCE", "DAMAGE_RESISTANCE");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isSoulmate(p.getUniqueId()) || !hasLazo(p)) continue;
            try {
                if (regen != null) p.addPotionEffect(new PotionEffect(regen, 90, 0, true, false, false));
                if (res != null) p.addPotionEffect(new PotionEffect(res, 90, 0, true, false, false));
            } catch (Throwable ignored) { }
        }
    }

    // ───────── (2) guardia: trae a Luna contigo y ataca a tu amenaza (tick cada ~0.5 s) ─────────
    public void guardTick() {
        if (guardUntil.isEmpty()) return;
        long now = now();
        NPC luna = findLuna();
        if (luna == null || !luna.isSpawned() || luna.getEntity() == null) { guardUntil.clear(); return; }
        Entity le = luna.getEntity();
        for (UUID ownerId : guardUntil.keySet().toArray(new UUID[0])) {
            Long until = guardUntil.get(ownerId);
            Player owner = Bukkit.getPlayer(ownerId);
            if (until == null || now > until || owner == null || !owner.isOnline()) {
                guardUntil.remove(ownerId);
                // termino la escolta: si ya no protege a nadie, guarda la armadura de batalla (vuelve dulce).
                if (guardUntil.isEmpty() && armor != null && le != null) { try { armor.disarm(le); } catch (Throwable ignored) { } }
                continue;
            }
            UUID thId = threat.get(ownerId);
            Long thTime = threatTime.get(ownerId);
            Entity target = (thId != null && thTime != null && now - thTime < 8000) ? Bukkit.getEntity(thId) : null;
            if (target != null && target.isValid() && target instanceof LivingEntity
                    && !target.getUniqueId().equals(ownerId) && target.getWorld().equals(le.getWorld())) {
                attackTarget(luna, le, target, now);
            } else {
                followOwner(luna, le, owner);
            }
        }
    }

    private void attackTarget(NPC luna, Entity le, Entity target, long now) {
        try {
            double dist = le.getLocation().distance(target.getLocation());
            AiConfig c = cfg.get();
            NpcPersona luPer = c == null ? null : c.match(bond.persona());
            gearUp(luna, luPer);   // que SAQUE su arma (espada) y este BLINDADA, no pelee a puño y en camiseta
            lookAt(le, target);
            if (dist > 3.2) {
                // a distancia: dispara si tiene pistola, y se acerca
                if (luPer != null && luPer.ranged && dist <= luPer.rangedRange && target instanceof LivingEntity lt) {
                    Long last = lunaHit.get(luna.getId());
                    if (last == null || now - last >= Math.max(600L, luPer.rangedCooldownMs)) {
                        lunaHit.put(luna.getId(), now);
                        try { lt.damage(luPer.rangedDamage); } catch (Throwable ignored) { }
                        try { le.getWorld().playSound(le.getLocation(), "entity.firework_rocket.blast", 1.1f, 1.4f); } catch (Throwable ignored) { }
                    }
                }
                try { luna.getNavigator().getLocalParameters().speedModifier(1.35f); luna.getNavigator().setTarget(target, true); } catch (Throwable ignored) { }
            } else if (target instanceof LivingEntity lt) {
                // melee
                Long last = lunaHit.get(luna.getId());
                if (last == null || now - last >= 800L) {
                    lunaHit.put(luna.getId(), now);
                    double dmg = (c != null && c.hostDamage > 0) ? Math.max(5.0, c.hostDamage) : 6.0;
                    try { lt.damage(dmg); } catch (Throwable ignored) { }
                    if (le instanceof Player pl) { try { pl.swingMainHand(); } catch (Throwable ignored) { } }
                }
                try { luna.getNavigator().setTarget(target, true); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private void followOwner(NPC luna, Entity le, Player owner) {
        try {
            double d2 = le.getWorld().equals(owner.getWorld()) ? le.getLocation().distanceSquared(owner.getLocation()) : Double.MAX_VALUE;
            if (d2 > 40 * 40) { le.teleport(owner.getLocation()); return; }
            if (d2 > 3.5 * 3.5) { luna.getNavigator().getLocalParameters().speedModifier(1.2f); luna.getNavigator().setTarget(owner, false); }
            else if (luna.getNavigator().isNavigating()) luna.getNavigator().cancelNavigation();
        } catch (Throwable ignored) { }
    }

    /** Prepara a Luna para la pelea: BLINDA (armadura defensiva) + DESENFUNDA su arma. Idempotente y a prueba de fallos. */
    private void gearUp(NPC luna, NpcPersona persona) {
        if (luna == null || persona == null) return;
        try { if (armor != null && luna.getEntity() != null) armor.onAttacked(luna.getEntity(), persona); } catch (Throwable ignored) { }
        equipWeapon(luna, persona);
    }

    /** Le pone su arma en la mano (espada de Luna) via el trait Equipment de Citizens, para que no pelee a puño. */
    private void equipWeapon(NPC luna, NpcPersona persona) {
        if (luna == null || persona == null || persona.weapon == null || persona.weapon.isBlank()) return;
        try {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(persona.weapon.toUpperCase(java.util.Locale.ROOT));
            if (m == null) return;
            net.citizensnpcs.api.trait.trait.Equipment eq = luna.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class);
            org.bukkit.inventory.ItemStack cur = eq.get(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND);
            if (cur == null || cur.getType() != m) {
                eq.set(net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HAND, new org.bukkit.inventory.ItemStack(m));
            }
        } catch (Throwable ignored) { }
    }

    private NPC findLuna() {
        try {
            NPCRegistry reg = CitizensAPI.getNPCRegistry();
            if (reg == null) return null;
            AiConfig c = cfg.get();
            String key = bond.persona();
            for (NPC npc : reg) {
                if (npc == null || !npc.isSpawned()) continue;
                String n = npc.getName();
                if (n == null) continue;
                NpcPersona per = c == null ? null : c.match(ChatColor.stripColor(n));
                if (per != null && per.key.equals(key)) return npc;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void lookAt(Entity who, Entity at) {
        try { who.lookAt(at.getLocation().getX(), at.getLocation().getY() + 1.2, at.getLocation().getZ(),
                io.papermc.paper.entity.LookAnchor.EYES); } catch (Throwable ignored) { }
    }

    private static PotionEffectType byName(String... names) {
        for (String n : names) { try { PotionEffectType t = PotionEffectType.getByName(n); if (t != null) return t; } catch (Throwable ignored) { } }
        return null;
    }

    private static long now() { return System.currentTimeMillis(); }

    private void msg(Player p, String amp) { p.sendMessage(ChatColor.translateAlternateColorCodes('&', amp)); }
}
