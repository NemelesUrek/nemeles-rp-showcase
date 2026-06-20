package com.nemeles.core.api.combat;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Se dispara cuando un medico reanima a un jugador derribado. */
public final class PlayerRevivedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player medic;
    private final Player patient;

    public PlayerRevivedEvent(Player medic, Player patient) {
        this.medic = medic;
        this.patient = patient;
    }

    public Player medic() { return medic; }
    public Player patient() { return patient; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
