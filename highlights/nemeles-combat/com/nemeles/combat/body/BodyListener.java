package com.nemeles.combat.body;

import com.nemeles.combat.MedItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Traduce el dano del mundo a HERIDAS LOCALIZADAS + maneja la ficha medica (GUI). */
public final class BodyListener implements Listener {

    private final BodyManager mgr;
    private final boolean noNaturalRegen;

    public BodyListener(BodyManager mgr, boolean noNaturalRegen) {
        this.mgr = mgr;
        this.noNaturalRegen = noNaturalRegen;
    }

    /** Sin regeneracion natural de la BARRA de vida (diseño del server): los corazones solo vuelven
     *  con tratamiento medico, hospital, pociones o comida especial — no por estar lleno o en paz. */
    @EventHandler(ignoreCancelled = true)
    public void onRegen(org.bukkit.event.entity.EntityRegainHealthEvent e) {
        if (!noNaturalRegen) return;
        if (!(e.getEntity() instanceof Player)) return;
        var c = e.getRegainReason();
        if (c == org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED
                || c == org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN) {
            e.setCancelled(true);   // comida llena / regen pacifica: bloqueadas. Pociones y curas medicas pasan.
        }
    }

    /** Caidas, fuego y golpes -> heridas por parte (las BALAS llegan por el GunListener con punto exacto). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;
        mgr.interruptOnDamage(p.getUniqueId());   // un golpe corta cualquier tratamiento en curso
        var rng = ThreadLocalRandom.current();
        switch (e.getCause()) {
            case FALL -> {
                // dmg ~ altura-3. La severidad de FRACTURA/ESGUINCE es el GRADO (1-3): si la pierna
                // ya estaba tocada, wound() ESCALA la lesion (fisura -> fractura -> abierta) en vez
                // de repetir el mismo dano y el mismo texto.
                boolean left = rng.nextBoolean();
                if (dmg <= 2.5) {
                    mgr.wound(p, left ? BodyPart.ESPINILLA_IZQ : BodyPart.ESPINILLA_DER, BodyManager.WoundType.ESGUINCE, 1);
                } else if (dmg <= 5) {
                    mgr.wound(p, left ? BodyPart.ESPINILLA_IZQ : BodyPart.ESPINILLA_DER, BodyManager.WoundType.ESGUINCE, 2);
                    mgr.wound(p, left ? BodyPart.PIE_IZQ : BodyPart.PIE_DER, BodyManager.WoundType.ESGUINCE, 1);
                } else if (dmg <= 9) {
                    mgr.wound(p, left ? BodyPart.ESPINILLA_IZQ : BodyPart.ESPINILLA_DER, BodyManager.WoundType.FRACTURA, 2);
                    mgr.wound(p, left ? BodyPart.PIE_IZQ : BodyPart.PIE_DER, BodyManager.WoundType.ESGUINCE, 1);
                } else {
                    mgr.wound(p, left ? BodyPart.ESPINILLA_IZQ : BodyPart.ESPINILLA_DER, BodyManager.WoundType.FRACTURA, 3);
                    mgr.wound(p, left ? BodyPart.MUSLO_IZQ : BodyPart.MUSLO_DER, BodyManager.WoundType.FRACTURA, 2);
                }
            }
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, CAMPFIRE -> {
                if (rng.nextDouble() < 0.15) {   // el fuego "tickea" mucho: no quemar 15 partes por segundo
                    BodyPart[] all = BodyPart.values();
                    int grade = (e.getCause() == EntityDamageEvent.DamageCause.LAVA || dmg >= 4) ? 2 : 1;
                    mgr.wound(p, all[rng.nextInt(all.length)], BodyManager.WoundType.QUEMADURA, grade);
                }
            }
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> {
                BodyPart part = switch (rng.nextInt(6)) {
                    case 0 -> BodyPart.BRAZO_IZQ;
                    case 1 -> BodyPart.BRAZO_DER;
                    case 2 -> BodyPart.TORSO_SUP;
                    case 3 -> BodyPart.TORSO_INF;
                    case 4 -> BodyPart.MANO_IZQ;
                    default -> BodyPart.MUSLO_DER;
                };
                if (e instanceof EntityDamageByEntityEvent ev && ev.getDamager() instanceof Player atk) {
                    // PvP melee TIPADO por el arma en mano: arma blanca = corte/puñalada; puño/objeto romo = golpe/moratón
                    String wn = atk.getInventory().getItemInMainHand().getType().name();
                    boolean blade = wn.endsWith("_SWORD") || wn.endsWith("_AXE") || wn.equals("SHEARS") || wn.equals("TRIDENT");
                    if (blade) mgr.wound(p, part, BodyManager.WoundType.CORTE, Math.min(3, dmg / 2.0));
                    else mgr.wound(p, part, BodyManager.WoundType.CONTUSION, dmg <= 3 ? 1 : dmg <= 6 ? 2 : 3);
                } else {
                    // mobs/zombis: zarpazos/mordiscos en zonas expuestas
                    mgr.wound(p, part, BodyManager.WoundType.CORTE, Math.min(3, dmg / 2.5));
                }
            }
            case FALLING_BLOCK, FLY_INTO_WALL -> mgr.wound(p, BodyPart.CABEZA, BodyManager.WoundType.CONTUSION, Math.min(3, dmg / 2));
            default -> { }
        }
    }

    /** Items medicos de uso directo: analgesico (clic derecho) + candados anti uso-vanilla. */
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = MedItems.idOf(it);
        if (id == null) return;
        Player p = e.getPlayer();
        switch (id) {
            case MedItems.ANALGESICO -> {
                e.setCancelled(true);
                if (mgr.painkillerActive(p.getUniqueId())) {
                    p.sendMessage(org.bukkit.ChatColor.GRAY + "Ya vas hasta arriba de calmantes. Doblar la dosis no es el plan.");
                    return;
                }
                it.setAmount(it.getAmount() - 1);
                mgr.takePainkiller(p);
            }
            // el antibiotico NO se bebe como miel y el desinfectante no se rellena: se usan desde /cuerpo
            case MedItems.ANTIBIOTICO, MedItems.DESINFECTANTE -> e.setCancelled(true);
            default -> { }
        }
    }

    /** Que nadie se beba el antibiotico como si fuera miel (se aplican desde la ficha medica). */
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        if (MedItems.idOf(e.getItem()) != null) e.setCancelled(true);
    }

    /** Clic derecho a un JUGADOR llevando material médico (venda, botiquín...) = analizarlo/atenderlo:
     *  abre su ficha (GUI radiográfica en Java, ficha por chat en Bedrock). */
    @EventHandler
    public void onInteractPlayer(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;   // solo mano principal (evita doble disparo)
        if (!(e.getRightClicked() instanceof Player patient)) return;
        Player clicker = e.getPlayer();
        if (!MedItems.is(clicker.getInventory().getItemInMainHand(), MedItems.MEDKIT)) return;   // hay que llevar el BOTIQUÍN
        e.setCancelled(true);
        BodyGUI.open(clicker, patient, mgr);   // examinar/atender al paciente
    }

    /** Clic en la ficha medica: overview (examinar una zona) o detalle (tratar/volver/cerrar). */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player viewer)) return;
        String title = e.getView().getTitle();
        if (title == null) return;
        boolean overview = title.indexOf(BodyScreen.TAG_OVERVIEW) >= 0;
        boolean detail   = title.indexOf(BodyScreen.TAG_DETALLE) >= 0;
        if (!overview && !detail) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        UUID patientId = BodyGUI.VIEWING.get(viewer.getUniqueId());
        if (patientId == null) return;
        Player patient = Bukkit.getPlayer(patientId);
        if (patient == null) { viewer.closeInventory(); return; }
        int raw = e.getRawSlot();

        if (overview) {
            if (raw == BodyGUI.SLOT_CLOSE_OV) { viewer.closeInventory(); return; }
            BodyPart part = BodyGUI.partAt(raw);
            if (part == null) return;   // zona muerta (clic ya cancelado)
            if (tooFar(viewer, patient)) { farMsg(viewer); return; }
            BodyGUI.openDetail(viewer, patient, part, mgr);
            return;
        }

        // ── pantalla de DETALLE ──
        if (raw == BodyGUI.SLOT_CLOSE_D) { viewer.closeInventory(); return; }
        if (raw == BodyGUI.SLOT_BACK)    { BodyGUI.openOverview(viewer, patient, mgr); return; }
        if (raw == BodyGUI.SLOT_TREAT) {
            BodyPart part = BodyGUI.VIEWING_PART.get(viewer.getUniqueId());
            if (part == null) { BodyGUI.openOverview(viewer, patient, mgr); return; }
            if (tooFar(viewer, patient)) { farMsg(viewer); return; }
            mgr.treat(viewer, patient, part);
            // si treat abrió el MINIJUEGO canalizado, cerró el inventario: NO reabrir (hay que
            // estar agachado y quieto). Si fue instantáneo, re-renderiza con el nuevo estado.
            if (!mgr.isChanneling(viewer.getUniqueId())) BodyGUI.openDetail(viewer, patient, part, mgr);
        }
    }

    private boolean tooFar(Player viewer, Player patient) {
        if (viewer.getUniqueId().equals(patient.getUniqueId())) return false;
        return !patient.getWorld().equals(viewer.getWorld())
                || patient.getLocation().distanceSquared(viewer.getLocation()) > 25;
    }
    private void farMsg(Player viewer) {
        viewer.sendMessage(org.bukkit.ChatColor.RED + "El paciente se alejó demasiado.");
        viewer.closeInventory();
    }

    /** Al desconectar: limpia los mapas auxiliares (las HERIDAS sobreviven al relog a proposito). */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        mgr.cancelChannels(id);
        mgr.onQuit(id);
        BodyGUI.VIEWING.remove(id);        // el observador que sale: limpia los mapas de la ficha
        BodyGUI.VIEWING_PART.remove(id);   // (las HERIDAS viven en BodyManager y sobreviven al relog)
    }

    /** Al reaparecer: cuerpo nuevo (la muerte 'cura' todo... a su manera). */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        mgr.reset(e.getPlayer().getUniqueId());
        try {
            var attr = e.getPlayer().getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (attr != null) attr.setBaseValue(20.0);
        } catch (Throwable ignored) { }
    }
}
