package com.nemeles.core.api.combat;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Se dispara cuando un jugador es derribado (downed). attacker puede ser null (entorno). */
public final class PlayerDownedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player victim;
    private final UUID attacker;

    public PlayerDownedEvent(Player victim, UUID attacker) {
        this.victim = victim;
        this.attacker = attacker;
    }

    public Player victim() { return victim; }
    public UUID attacker() { return attacker; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
