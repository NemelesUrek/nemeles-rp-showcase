package com.nemeles.territories;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Guerra de territorio en curso (en memoria + persistida en territory_war).
 *
 * <p>Flujo: el ATACANTE deposita {@code potCents} en ESCROW (cuenta sintetica {@link #escrowAccount()})
 * al declarar. Hay una ventana de preparacion ({@code state==PREP} hasta {@code prepEndMs}) y luego una
 * ventana activa ({@code state==WINDOW} hasta {@code windowEndMs}). Durante la ventana activa la captura
 * por presencia (TerritoryManager) decide: si el atacante captura, recupera el bote; si la ventana expira
 * sin captura, el bote pasa al DEFENSOR.</p>
 */
public final class WarSession {

    public static final String PREP = "PREP";
    public static final String WINDOW = "WINDOW";
    public static final String ENDED = "ENDED";

    final long id;
    final int territoryId;
    final int attackerFaction;
    final int defenderFaction;   // -1 si la zona era neutral al declarar (no deberia, pero defensivo)
    final long potCents;
    final long prepEndMs;
    final long windowEndMs;
    String state;                // PREP | WINDOW | ENDED

    public WarSession(long id, int territoryId, int attackerFaction, int defenderFaction, long potCents,
                      long prepEndMs, long windowEndMs, String state) {
        this.id = id;
        this.territoryId = territoryId;
        this.attackerFaction = attackerFaction;
        this.defenderFaction = defenderFaction;
        this.potCents = potCents;
        this.prepEndMs = prepEndMs;
        this.windowEndMs = windowEndMs;
        this.state = state;
    }

    /** True mientras estamos en la ventana de preparacion (sin captura permitida). */
    boolean inPrep(long now) { return PREP.equals(state) && now < prepEndMs; }

    /** True mientras la ventana activa esta abierta (la captura por presencia decide). */
    boolean inWindow(long now) { return now >= prepEndMs && now < windowEndMs && !ENDED.equals(state); }

    /** True si la ventana activa ya expiro (hay que resolver al defensor). */
    boolean windowExpired(long now) { return now >= windowEndMs && !ENDED.equals(state); }

    /** Cuenta sintetica del escrow de esta guerra: 'nemeles-war:'+id (UUID determinista). */
    UUID escrowAccount() { return escrowAccount(id); }

    static UUID escrowAccount(long warId) {
        return UUID.nameUUIDFromBytes(("nemeles-war:" + warId).getBytes(StandardCharsets.UTF_8));
    }
}
