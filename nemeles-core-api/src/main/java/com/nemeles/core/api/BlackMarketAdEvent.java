package com.nemeles.core.api;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Un jugador publica un anuncio en el MERCADO NEGRO: trapicheos, ventas ilegales, encuentros para
 * transacciones turbias. DiscordSRV lo enruta a su propio canal (#mercado-negro) via alerts.yml.
 *
 * <p>v2: el anuncio es ESTRUCTURADO — vendedor ANÓNIMO (nunca el nombre de cuenta), categoría (con su
 * emoji y color), precio y zona, además del texto ya saneado. Las 6 alertas por categoría de alerts.yml
 * interpolan estos campos. Se conserva el constructor de 3 args (v1) por retrocompatibilidad.</p>
 */
public final class BlackMarketAdEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String seller;         // alias anónimo del anunciante (jamás el nombre de cuenta)
    private final String category;       // id interno de la categoría (hierro, polvo, ruedas...)
    private final String categoryEmoji;  // emoji de la categoría (viene de config, no se hardcodea)
    private final String categoryColor;  // color hex sugerido (las alertas usan color fijo por Condition)
    private final String priceText;      // precio ya formateado ("$1.250" / "A convenir")
    private final String zone;           // distrito / mundo donde rondaba (ambiente)
    private final String message;        // el anuncio ya saneado

    /** Constructor completo (v2). */
    public BlackMarketAdEvent(String seller, String category, String categoryEmoji, String categoryColor,
                              String priceText, String zone, String message) {
        super(!Bukkit.isPrimaryThread());
        this.seller = (seller == null || seller.isBlank()) ? "Un desconocido" : seller;
        this.category = (category == null || category.isBlank()) ? "encargos" : category;
        this.categoryEmoji = categoryEmoji == null ? "" : categoryEmoji;
        this.categoryColor = (categoryColor == null || categoryColor.isBlank()) ? "#1A1A1A" : categoryColor;
        this.priceText = (priceText == null || priceText.isBlank()) ? "A convenir" : priceText;
        this.zone = zone == null ? "" : zone;
        this.message = message == null ? "" : message;
    }

    /** Constructor v1 (retrocompatibilidad): sin categoría ni precio. */
    public BlackMarketAdEvent(String seller, String zone, String message) {
        this(seller, "encargos", "", "#1A1A1A", "A convenir", zone, message);
    }

    public String seller() { return seller; }
    public String category() { return category; }
    public String categoryEmoji() { return categoryEmoji; }
    public String categoryColor() { return categoryColor; }
    public String priceText() { return priceText; }
    public String zone() { return zone; }
    public String message() { return message; }

    /** Dispara el anuncio v2 de forma segura (nunca propaga excepciones al llamante). */
    public static void fire(String seller, String category, String categoryEmoji, String categoryColor,
                            String priceText, String zone, String message) {
        try { Bukkit.getPluginManager().callEvent(
                new BlackMarketAdEvent(seller, category, categoryEmoji, categoryColor, priceText, zone, message)); }
        catch (Throwable ignored) { }
    }

    /** Dispara el anuncio v1 (retrocompatibilidad). */
    public static void fire(String seller, String zone, String message) {
        try { Bukkit.getPluginManager().callEvent(new BlackMarketAdEvent(seller, zone, message)); }
        catch (Throwable ignored) { }
    }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
