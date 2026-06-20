package com.nemeles.combat.listener;

import com.nemeles.combat.CombatConfig;
import com.nemeles.combat.DownedManager;
import com.nemeles.combat.body.BodyManager;
import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HOSPITAL NPC (check-in estilo FiveM): clic derecho a una entidad llamada "hospital" = curacion
 * TOTAL del cuerpo por una tarifa. Si hay medicos JUGADORES (curso avanzado) conectados, el
 * hospital te manda con ellos (los jugadores-medico son SIEMPRE el camino barato y social;
 * el NPC es la red de seguridad cuando la ciudad esta vacia de batas blancas).
 */
public final class HospitalListener implements Listener {

    private final Plugin plugin;
    private final BodyManager body;
    private final DownedManager downed;
    private final CombatConfig cfg;
    private final Map<UUID, Long> busy = new ConcurrentHashMap<>();   // anti doble-clic durante el cobro async
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>(); // 30 min entre check-ins (balance r18)

    public HospitalListener(Plugin plugin, BodyManager body, DownedManager downed, CombatConfig cfg) {
        this.plugin = plugin;
        this.body = body;
        this.downed = downed;
        this.cfg = cfg;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity ent = e.getRightClicked();
        String name = ent.getCustomName() != null ? ent.getCustomName() : ent.getName();
        if (name == null) return;
        name = ChatColor.stripColor(name).toLowerCase(Locale.ROOT).trim();
        if (!name.equals(cfg.hospitalNpcName)) return;
        e.setCancelled(true);
        Player clicker = e.getPlayer();
        if (downed.isDowned(clicker.getUniqueId())) return;

        // TRASLADO REAL: si llegas con un DERRIBADO a hombros, el hospital atiende AL HERIDO
        // (el porteador paga; cooldown y vetas se aplican al paciente). Cierra el bucle
        // transfusion -> hombro -> hospital que promete el resto del sistema.
        Player patient = clicker;
        for (org.bukkit.entity.Entity pass : clicker.getPassengers()) {
            if (pass instanceof Player carried && downed.isDowned(carried.getUniqueId())) { patient = carried; break; }
        }
        final Player p = patient;
        final boolean traslado = p != clicker;

        // candados anti-abuso (balance ChatGPT r18): cooldown 30 min + nada de curar a BUSCADOS (3★+)
        Long cd = cooldown.get(p.getUniqueId());
        if (cd != null && cd > System.currentTimeMillis()) {
            long min = (cd - System.currentTimeMillis()) / 60_000L + 1;
            p.sendMessage(ChatColor.YELLOW + "[Hospital] " + ChatColor.GRAY
                    + "Acabas de pasar por quirófano. Vuelve en " + min + " min (o búscate un médico).");
            return;
        }
        try {
            if (NemelesApi.wanted().getStars(p.getUniqueId()) >= 3) {
                p.sendMessage(ChatColor.RED + "[Hospital] El recepcionista mira tu cara, mira el cartel de SE BUSCA... y niega con la cabeza. Aquí no.");
                return;
            }
        } catch (Throwable ignored) { }

        if (cfg.hospitalOnlyWithoutMedics) {
            int medics = 0;
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.getUniqueId().equals(p.getUniqueId()) && body.courseOf(o) >= 2) medics++;
            }
            if (medics >= cfg.hospitalMaxMedics) {
                p.sendMessage(ChatColor.YELLOW + "[Hospital] " + ChatColor.GRAY
                        + "Hay médicos colegiados de servicio en la ciudad: búscalos (te saldrá más barato y más humano). "
                        + "El hospital solo atiende cuando no queda nadie con bata.");
                return;
            }
        }

        boolean wounded = traslado || body.painOf(p.getUniqueId()) >= 10
                || body.body(p.getUniqueId()).values().stream().anyMatch(BodyManager.PartState::any);
        if (!wounded) {
            p.sendMessage(ChatColor.GRAY + "[Hospital] Estás más sano que el agua. Fuera de mi consulta.");
            return;
        }
        Long b = busy.get(clicker.getUniqueId());
        if (b != null && b > System.currentTimeMillis()) return;
        busy.put(clicker.getUniqueId(), System.currentTimeMillis() + 5000L);

        BigDecimal fee = BigDecimal.valueOf(cfg.hospitalFeeCents).movePointLeft(2);
        try {
            // paga siempre el que LLEGA al mostrador (en traslado: el porteador corre con la factura)
            NemelesApi.economy().withdraw(clicker.getUniqueId(), MoneyType.EFECTIVO, fee, "hospital:checkin")
                    .whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        busy.remove(clicker.getUniqueId());
                        if (err != null || res == null || !res.success()) {
                            if (err == null && clicker.isOnline()) {
                                clicker.sendMessage(ChatColor.RED + "[Hospital] Sin $" + fee.toPlainString()
                                        + " en efectivo no hay milagros. La sanidad de Bahía Negra es así.");
                            }
                            return;
                        }
                        if (!p.isOnline()) {   // el paciente se fue entre el clic y el cobro: reversa
                            try { NemelesApi.economy().deposit(clicker.getUniqueId(), MoneyType.EFECTIVO, fee, "hospital:refund"); }
                            catch (Throwable ignored) { }
                            return;
                        }
                        if (traslado) {
                            try { downed.revive(p.getUniqueId(), clicker.getUniqueId()); } catch (Throwable ignored) { }
                        }
                        body.hospitalTreat(p.getUniqueId());   // cura al 65%, NO quita infecciones declaradas
                        cooldown.put(p.getUniqueId(), System.currentTimeMillis() + 30 * 60_000L);
                        try {
                            // OJO: clampar contra el MAX_HEALTH real (la infeccion lo reduce; 20.0 fijo lanzaria IAE)
                            var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                            double max = attr != null ? attr.getValue() : 20.0;
                            p.setHealth(Math.min(max, p.getHealth() + 10));
                        } catch (Throwable ignored) { }
                        try {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15 * 20, 0, false, false));
                            p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.2f);
                        } catch (Throwable ignored) { }
                        p.sendMessage(ChatColor.GREEN + "[Hospital] Te cosen, te entablillan, te vendan"
                                + (traslado ? "" : " y te cobran $" + fee.toPlainString()) + ". " + ChatColor.GRAY
                                + "Sales remendado (no como nuevo: eso lo hacen los médicos de verdad). 15s en observación.");
                        if (traslado) {
                            clicker.sendMessage(ChatColor.GREEN + "[Hospital] Entregas a " + p.getName()
                                    + " en urgencias y pagas $" + fee.toPlainString() + ". "
                                    + ChatColor.GRAY + "Hoy alguien te debe la vida.");
                            try { NemelesApi.skills().grantXp(clicker, "medic", 12); } catch (Throwable ignored) { }
                        }
                    }));
        } catch (Throwable t) {
            busy.remove(clicker.getUniqueId());
        }
    }
}
