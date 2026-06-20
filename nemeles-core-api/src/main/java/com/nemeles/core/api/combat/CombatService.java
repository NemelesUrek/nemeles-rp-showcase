package com.nemeles.core.api.combat;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Sistema de combate (armas, derribado/downed, reanimacion EMT, muerte). Lo registra el modulo
 * nemeles-combat; otros modulos (territorios, policia, telefono) lo consumen en try/catch sin acoplarse.
 * Lecturas sincronas desde cache en memoria.
 */
public interface CombatService {

    // ── estado ──
    DownState stateOf(UUID player);
    boolean isDowned(UUID player);                 // DOWNED || BEING_REVIVED
    int bleedoutSecondsLeft(UUID player);          // -1 si no esta derribado

    // ── armas (otros modulos: drops, "rastrear arma", mercado negro...) ──
    boolean isGun(ItemStack item);
    String gunIdOf(ItemStack item);                // null si no es arma
    ItemStack createGun(String gunId);             // item de arma listo, o null si no existe
    ItemStack createAmmo(String ammoId, int amount); // item de municion, o null si no existe
    /** Item de EQUIPO MEDICO (venda, bisturi, bolsa de sangre...) o null si no existe. Default por compat. */
    default ItemStack createMedItem(String medId, int amount) { return null; }

    // ── control (atracos, eventos, captura policial) ──
    boolean forceDown(UUID player, UUID source, String cause);
    boolean revive(UUID target, UUID byReviver);
    void finishOff(UUID player, String cause);
}
