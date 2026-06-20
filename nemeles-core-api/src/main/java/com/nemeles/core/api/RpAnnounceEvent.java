package com.nemeles.core.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Titular RP generico para anunciar momentos importantes (guerra declarada/ganada, mafia fundada/disuelta,
 * etc.) en Discord. Los plugins lo disparan con el texto YA RESUELTO (nombres incluidos, no ids), y
 * DiscordSRV (alerts.yml) lo convierte en una cronica de "El Faro de Bahia Negra" sin que ningun modulo
 * dependa de Discord. Campos: emoji + title (cabecera) + body (cuerpo) + category (para el footer).
 *
 * <p>Se marca async/sync automaticamente segun desde donde se dispara, asi es seguro lanzarlo tanto desde
 * el hilo principal como desde un callback de BD.</p>
 */
public final class RpAnnounceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String emoji;
    private final String title;
    private final String body;
    private final String category;

    public RpAnnounceEvent(String emoji, String title, String body, String category) {
        super(!Bukkit.isPrimaryThread());
        this.emoji = emoji == null ? "" : emoji;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.category = (category == null || category.isBlank()) ? "sucesos" : category;
    }

    public String emoji() { return emoji; }
    public String title() { return title; }
    public String body() { return body; }
    public String category() { return category; }

    /** Dispara el evento de forma segura (no propaga excepciones de los listeners). */
    public static void fire(String emoji, String title, String body, String category) {
        try { Bukkit.getPluginManager().callEvent(new RpAnnounceEvent(emoji, title, body, category)); }
        catch (Throwable ignored) { }
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
