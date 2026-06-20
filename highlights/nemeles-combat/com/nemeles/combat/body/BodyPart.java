package com.nemeles.combat.body;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * Anatomia del jugador (15 partes, estilo Project Zomboid). El jugador mide 1.8 bloques:
 * el impacto se localiza por ALTURA relativa (0=pies, 1=coronilla) y LADO (izq/der respecto
 * a donde MIRA la victima, via producto cruzado con su direccion).
 */
public enum BodyPart {

    CABEZA("la cabeza", 2.0),
    CUELLO("el cuello", 1.6),
    TORSO_SUP("el pecho", 1.0),
    TORSO_INF("el abdomen", 1.0),
    INGLE("la ingle", 1.1),
    BRAZO_IZQ("el brazo izquierdo", 0.7),
    BRAZO_DER("el brazo derecho", 0.7),
    MANO_IZQ("la mano izquierda", 0.55),
    MANO_DER("la mano derecha", 0.55),
    MUSLO_IZQ("el muslo izquierdo", 0.8),
    MUSLO_DER("el muslo derecho", 0.8),
    ESPINILLA_IZQ("la espinilla izquierda", 0.7),
    ESPINILLA_DER("la espinilla derecha", 0.7),
    PIE_IZQ("el pie izquierdo", 0.55),
    PIE_DER("el pie derecho", 0.55);

    public final String display;     // "el pecho", "la mano izquierda"...
    public final double gunMult;     // multiplicador de dano de BALA al impactar aqui

    BodyPart(String display, double gunMult) {
        this.display = display;
        this.gunMult = gunMult;
    }

    public boolean isPierna() { return this == MUSLO_IZQ || this == MUSLO_DER || this == ESPINILLA_IZQ
            || this == ESPINILLA_DER || this == PIE_IZQ || this == PIE_DER; }
    public boolean isBrazo() { return this == BRAZO_IZQ || this == BRAZO_DER || this == MANO_IZQ || this == MANO_DER; }
    public boolean isVital() { return this == CABEZA || this == CUELLO || this == TORSO_SUP || this == TORSO_INF; }

    /**
     * Localiza la parte del cuerpo a partir del punto EXACTO de impacto (hitscan/proyectil).
     * relY: 0 = pies, 1 = coronilla. Lado: signo del producto cruzado (dirección de la víctima × vector al impacto).
     */
    public static BodyPart locate(LivingEntity victim, Vector hitPos) {
        Location feet = victim.getLocation();
        double relY = Math.max(0, Math.min(1, (hitPos.getY() - feet.getY()) / Math.max(0.1, victim.getHeight())));
        // lateralidad: ¿el impacto cayó a la izquierda o a la derecha de la víctima (según SU mirada)?
        Vector facing = feet.getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) facing = new Vector(0, 0, 1);
        facing.normalize();
        Vector toHit = hitPos.clone().subtract(feet.toVector()).setY(0);
        double lateral = facing.getX() * toHit.getZ() - facing.getZ() * toHit.getX();   // cross.y
        boolean left = lateral > 0;
        double absLat = Math.abs(lateral);

        if (relY >= 0.86) return CABEZA;
        if (relY >= 0.78) return CUELLO;
        if (relY >= 0.50) {
            // zona del torso: si el impacto entro muy lateral, son los brazos (o las manos abajo del todo)
            if (absLat > 0.26) {
                if (relY < 0.58) return left ? MANO_IZQ : MANO_DER;
                return left ? BRAZO_IZQ : BRAZO_DER;
            }
            return relY >= 0.66 ? TORSO_SUP : TORSO_INF;
        }
        if (relY >= 0.45 && absLat <= 0.15) return INGLE;
        if (relY >= 0.26) return left ? MUSLO_IZQ : MUSLO_DER;
        if (relY >= 0.09) return left ? ESPINILLA_IZQ : ESPINILLA_DER;
        return left ? PIE_IZQ : PIE_DER;
    }
}
