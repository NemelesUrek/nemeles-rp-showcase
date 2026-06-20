package com.nemeles.core.api.police;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Se dispara cuando un policia ENCIERRA a un jugador (lo emite nemeles-police al condenar). */
public class PlayerJailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID prisoner;
    private final UUID officer;      // null si fue por consola/sistema
    private final int stars;         // estrellas en el momento del arresto
    private final int minutes;       // condena en minutos

    public PlayerJailedEvent(UUID prisoner, UUID officer, int stars, int minutes) {
        this.prisoner = prisoner;
        this.officer = officer;
        this.stars = stars;
        this.minutes = minutes;
    }

    public UUID getPrisoner() { return prisoner; }
    public UUID getOfficer()  { return officer; }
    public int getStars()     { return stars; }
    public int getMinutes()   { return minutes; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
