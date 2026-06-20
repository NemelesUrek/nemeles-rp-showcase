package com.nemeles.combat.listener;

import com.nemeles.combat.GunRegistry;
import com.nemeles.combat.render.GunModelService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Detecta cuando el jugador EMPUNA / GUARDA un arma para montar/quitar su modelo 3D.
 *
 * <p>El servicio {@link GunModelService} tiene ademas un tick de reconciliacion que cubre los casos
 * dificiles (arrastrar otro item al slot de la mano, drop, romper stack, derribado, muerte). Este
 * listener solo añade BAJA LATENCIA al cambiar de slot/mano/mundo (no esperar al proximo tick).
 */
public final class GunEquipListener implements Listener {

    private final GunRegistry guns;
    private final GunModelService gunModel;

    public GunEquipListener(GunRegistry guns, GunModelService gunModel) {
        this.guns = guns;
        this.gunModel = gunModel;
    }

    /** Cambio de slot de la hotbar: comparar el item del slot nuevo (aun NO aplicado al mainhand). */
    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        ItemStack next = p.getInventory().getItem(e.getNewSlot());   // el slot destino, antes de aplicarse
        String gunId = guns.gunIdOf(next);
        if (gunId != null) gunModel.onEquip(p, gunId, guns.skinIdOf(next));
        else gunModel.onHolster(p);
    }

    /** F (mano principal <-> secundaria): si el arma pasa a offhand, deja de empunarse en la principal. */
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        ItemStack toMain = e.getMainHandItem();   // lo que QUEDARA en la mano principal tras el swap
        String gunId = guns.gunIdOf(toMain);
        if (gunId != null) gunModel.onEquip(p, gunId, guns.skinIdOf(toMain));
        else gunModel.onHolster(p);
    }

    /** Los trackers de BetterModel pueden perderse al cambiar de mundo: re-evaluar el arma en mano. */
    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // cerrar cualquier modelo viejo del mundo anterior y re-montar segun el item en mano actual
        gunModel.cleanup(p);
        ItemStack inHand = p.getInventory().getItemInMainHand();
        String gunId = guns.gunIdOf(inHand);
        if (gunId != null) gunModel.onEquip(p, gunId, guns.skinIdOf(inHand));
    }
}
