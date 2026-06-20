package com.nemeles.core.api.faction;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Evento de ciclo de vida de una faccion/mafia (acoplamiento suelto entre modulos). Se dispara cuando
 * ocurre un hito relevante de una faccion: creacion, disolucion, declaracion de guerra de territorio, etc.
 * Los modulos lo escuchan en try/catch sin acoplarse al modulo de facciones.
 */
public final class FactionLifecycleEvent extends Event {

    /** Tipo de hito. */
    public enum Kind {
        CREATED,
        DISBANDED,
        WAR_DECLARED,
        WAR_ENDED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Kind kind;
    private final int factionId;        // faccion principal del hito (p.ej. el atacante en WAR_DECLARED)
    private final int otherFactionId;   // faccion secundaria si aplica (p.ej. el defensor); -1 si no aplica
    private final int territoryId;      // territorio implicado si aplica (guerras); -1 si no aplica

    public FactionLifecycleEvent(Kind kind, int factionId) {
        this(kind, factionId, -1, -1);
    }

    public FactionLifecycleEvent(Kind kind, int factionId, int otherFactionId, int territoryId) {
        this.kind = kind;
        this.factionId = factionId;
        this.otherFactionId = otherFactionId;
        this.territoryId = territoryId;
    }

    public Kind kind() { return kind; }
    public int factionId() { return factionId; }
    public int otherFactionId() { return otherFactionId; }
    public int territoryId() { return territoryId; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
