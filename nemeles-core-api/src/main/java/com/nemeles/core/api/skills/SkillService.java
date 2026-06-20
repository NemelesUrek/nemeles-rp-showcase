package com.nemeles.core.api.skills;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * API de habilidades (la implementa nemeles-jobs / JobManager). Otros modulos conceden XP y leen niveles
 * sin acoplarse: p.ej. el combate sube la habilidad "medic" al reanimar.
 */
public interface SkillService {

    /** Nivel actual de la habilidad (1 si no se ha practicado). */
    int getLevel(UUID player, String skillId);

    /** Concede XP a una habilidad SIN pago en dinero (respeta el xpFactor del empleo). */
    void grantXp(Player player, String skillId, int xp);

    /** ¿El jugador tiene elegido ese perk (id estable skill.tier.opt)? */
    boolean hasPerk(UUID player, String perkId);

    /** Suma de los efectos de los perks elegidos del jugador para esa clave (0 si ninguno). */
    double perkValue(UUID player, String skillId, String effectKey);

    /** Opción elegida ('A'/'B') en ese hito de la habilidad, o '\0' si ninguna. */
    char perkChoice(UUID player, String skillId, int tier);
}
