package com.nemeles.jobs.model;

import org.bukkit.Material;

/**
 * Una accion remunerada de un trabajo: romper/cosechar cierto material.
 * @param material     bloque objetivo
 * @param payout       pago base (efectivo) antes del multiplicador de nivel
 * @param xp           XP base que otorga
 * @param unlockLevel  nivel minimo del trabajo para cobrar por esta accion
 */
public record JobAction(Material material, double payout, int xp, int unlockLevel) {}
