package com.nemeles.core.api.combat;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Se dispara cuando un jugador muere de verdad (tras el downed o en zona negra). killer puede ser null. */
public final class PlayerKilledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final UUID killer;
    private final String cause;
    private final boolean permadeath;

    public PlayerKilledEvent(Player victim, UUID killer, String cause, boolean permadeath) {
        this.victim = victim;
        this.killer = killer;
        this.cause = cause;
        this.permadeath = permadeath;
    }

    public Player victim() { return victim; }
    public UUID killer() { return killer; }
    public String cause() { return cause; }
    public boolean permadeath() { return permadeath; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
