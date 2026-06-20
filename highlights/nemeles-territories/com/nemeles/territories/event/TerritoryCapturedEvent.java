package com.nemeles.territories.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Se dispara cuando una faccion captura un territorio (acoplamiento suelto para otros modulos). */
public final class TerritoryCapturedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int territoryId;
    private final int fromFaction;   // -1 si era neutral
    private final int toFaction;

    public TerritoryCapturedEvent(int territoryId, int fromFaction, int toFaction) {
        this.territoryId = territoryId;
        this.fromFaction = fromFaction;
        this.toFaction = toFaction;
    }

    public int territoryId() { return territoryId; }
    public int fromFaction() { return fromFaction; }
    public int toFaction() { return toFaction; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
