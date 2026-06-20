package com.nemeles.core.command;

import com.nemeles.core.api.BlackMarketAdEvent;
import com.nemeles.core.api.NemelesApi;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /mercadonegro &lt;categoria&gt; &lt;precio&gt; &lt;descripcion&gt; — publica un trapicheo en el canal
 * #mercado-negro de Discord para cerrar encuentros y transacciones ilegales. NO se difunde in-game (es un
 * tablon de Discord): solo confirma al anunciante.
 *
 * <p>v2: el vendedor SIEMPRE sale como un ALIAS ANÓNIMO (jamás el nombre de cuenta de Minecraft — eso
 * filtraba la identidad que el resto del servidor oculta). Anuncios por CATEGORÍA (cada una con emoji,
 * color y cooldown propios), precio formateado, saneo endurecido (markdown + URLs + pings) y anti-spam
 * por categoría. {@code /mercadonegro lista} muestra las categorías in-game.</p>
 */
public final class BlackMarketCommand implements CommandExecutor {

    /** Una categoría de anuncio: id interno + etiqueta (jerga) + emoji + color del embed + cooldown propio. */
    private record Cat(String id, String label, String emoji, String color, int cooldown) {}

    private final Plugin plugin;
    private final Map<String, Cat> cats = new LinkedHashMap<>();
    /** clave "uuid:categoria" -> último uso (ms). Anti-spam por categoría, con purga periódica. */
    private final Map<String, Long> lastUse = new HashMap<>();
    /** uuid -> último uso GLOBAL (ms): tope anti-spam entre categorías (que no rote y inunde el canal). */
    private final Map<UUID, Long> lastGlobal = new HashMap<>();

    public BlackMarketCommand(Plugin plugin) {
        this.plugin = plugin;
        loadCats();
    }

    /** Carga las categorías desde config (blackmarket.categorias). Tolerante a que falte la sección. */
    private void loadCats() {
        cats.clear();
        int defCd = Math.max(0, plugin.getConfig().getInt("blackmarket.cooldown-seconds", 90));
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("blackmarket.categorias");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) continue;
            String key = id.toLowerCase(Locale.ROOT);
            cats.put(key, new Cat(key,
                    c.getString("label", key),
                    c.getString("emoji", ""),
                    c.getString("color", "#1A1A1A"),
                    Math.max(0, c.getInt("cooldown", defCd))));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }

        if (!plugin.getConfig().getBoolean("blackmarket.enabled", true)) {
            p.sendMessage(ChatColor.RED + "El mercado negro está cerrado ahora mismo.");
            return true;
        }
        if (cats.isEmpty()) {
            p.sendMessage(ChatColor.RED + "El mercado negro está en mantenimiento (sin categorías configuradas).");
            return true;
        }
        purge();

        // /mn   ·   /mn lista   ·   /mn ayuda
        if (args.length == 0 || args[0].equalsIgnoreCase("lista") || args[0].equalsIgnoreCase("ayuda")
                || args[0].equalsIgnoreCase("help")) {
            sendList(p, label);
            return true;
        }

        Cat cat = cats.get(args[0].toLowerCase(Locale.ROOT));
        if (cat == null) {
            p.sendMessage(ChatColor.RED + "No conozco esa mercancía. " + ChatColor.GRAY + "Usa "
                    + ChatColor.WHITE + "/" + label + " lista" + ChatColor.GRAY + ".");
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.GRAY + "Uso: " + ChatColor.WHITE + "/" + label + " " + cat.id()
                    + " <precio> <descripción>");
            p.sendMessage(ChatColor.DARK_GRAY + "Ej: /" + label + " " + cat.id()
                    + " 1250 Dos fierros, zona puerto, esta noche.");
            return true;
        }

        long now = System.currentTimeMillis();

        // Cooldown GLOBAL por jugador (frena al troll que rota categorías para inundar el canal).
        int gCd = Math.max(0, plugin.getConfig().getInt("blackmarket.global-cooldown-seconds", 30));
        Long gPrev = lastGlobal.get(p.getUniqueId());
        if (gCd > 0 && gPrev != null) {
            long gRemain = (gPrev + gCd * 1000L) - now;
            if (gRemain > 0) {
                p.sendMessage(ChatColor.RED + "Baja el ritmo, que llamas la atención. "
                        + ChatColor.GRAY + "Vuelve en " + ((gRemain / 1000) + 1) + "s.");
                return true;
            }
        }

        // Cooldown por categoría.
        String ckey = p.getUniqueId() + ":" + cat.id();
        Long prev = lastUse.get(ckey);
        if (cat.cooldown() > 0 && prev != null) {
            long remainMs = (prev + cat.cooldown() * 1000L) - now;
            if (remainMs > 0) {
                p.sendMessage(ChatColor.RED + "Baja el ritmo, que llamas la atención. "
                        + ChatColor.GRAY + "Vuelve en " + ((remainMs / 1000) + 1) + "s.");
                return true;
            }
        }

        // Precio (opcional) + descripción.
        int maxLen = Math.max(20, plugin.getConfig().getInt("blackmarket.max-length", 200));
        BigDecimal price = parsePrice(args[1]);
        String priceText;
        String desc;
        if (price != null) {
            priceText = formatPrice(price);
            desc = (args.length >= 3) ? joinFrom(args, 2) : "";
        } else {
            // args[1] no es un número: todo desde args[1] es descripción; el precio queda "a convenir".
            priceText = "A convenir — pregunta por privado";
            desc = joinFrom(args, 1);
        }
        desc = sanitizeForDiscord(desc, maxLen);
        if (desc.isBlank()) {
            p.sendMessage(ChatColor.RED + "Ese anuncio no dice nada.");
            return true;
        }

        String seller = sellerAlias(p);
        String zone = p.getWorld() != null ? p.getWorld().getName() : "";
        lastUse.put(ckey, now);
        lastGlobal.put(p.getUniqueId(), now);

        BlackMarketAdEvent.fire(seller, cat.id(), cat.emoji(), cat.color(), priceText, zone, desc);

        p.sendMessage(ChatColor.DARK_GREEN + "[Mercado Negro] " + ChatColor.GRAY + "Tu anuncio de "
                + ChatColor.WHITE + (cat.emoji().isEmpty() ? "" : cat.emoji() + " ") + cat.label()
                + ChatColor.GRAY + " corre por los bajos fondos "
                + ChatColor.DARK_GRAY + "(#mercado-negro en Discord)" + ChatColor.GRAY + ".");
        p.sendMessage(ChatColor.GRAY + "Sales como " + ChatColor.WHITE + seller
                + ChatColor.GRAY + " — nadie sabe que eres tú. Cuida con quién quedas.");
        return true;
    }

    /** Alias ANÓNIMO del vendedor: el del sistema de identidad si está cargado; si no, fallback local. */
    private String sellerAlias(Player p) {
        UUID id = p.getUniqueId();
        try {
            var ids = NemelesApi.identity();
            if (ids != null) {
                String a = ids.anonAlias(id);
                if (a != null && !a.isBlank()) return a;
            }
        } catch (Throwable ignored) { }
        // Fallback (identity no cargado): MISMA fórmula del handle estable + prefijo configurable.
        String prefix = plugin.getConfig().getString("blackmarket.alias-prefix", "Desconocido");
        String handle = "#" + String.format("%08X", id.hashCode()).substring(4);
        return prefix + " " + handle;
    }

    private void sendList(Player p, String label) {
        p.sendMessage(ChatColor.DARK_GREEN.toString() + ChatColor.BOLD + "MERCADO NEGRO"
                + ChatColor.GRAY + " — elige qué mueves:");
        for (Cat c : cats.values()) {
            p.sendMessage(ChatColor.WHITE + " " + (c.emoji().isEmpty() ? "•" : c.emoji()) + " "
                    + ChatColor.GREEN + c.id() + ChatColor.GRAY + " — " + c.label());
        }
        p.sendMessage(ChatColor.GRAY + "Uso: " + ChatColor.WHITE + "/" + label
                + " <categoría> <precio> <descripción>");
        p.sendMessage(ChatColor.DARK_GRAY + "Ej: /" + label + " hierro 1250 Dos fierros, zona puerto, esta noche.");
    }

    /** Purga entradas de cooldown ya vencidas (evita que el mapa crezca indefinidamente). */
    private void purge() {
        if (lastUse.isEmpty() && lastGlobal.isEmpty()) return;
        long now = System.currentTimeMillis();
        // El cooldown más largo configurado marca cuándo una entrada ya no puede bloquear nada.
        long maxCd = 0L;
        for (Cat c : cats.values()) maxCd = Math.max(maxCd, c.cooldown() * 1000L);
        final long horizon = maxCd;
        lastUse.entrySet().removeIf(e -> e.getValue() + horizon < now);
        final long gHorizon = Math.max(0, plugin.getConfig().getInt("blackmarket.global-cooldown-seconds", 30)) * 1000L;
        lastGlobal.entrySet().removeIf(e -> e.getValue() + gHorizon < now);
    }

    // ── helpers de precio ──────────────────────────────────────────────
    /** Parsea "1250", "1.250", "1,250", "$1250" → BigDecimal; null si no es un número válido. */
    private static BigDecimal parsePrice(String s) {
        if (s == null) return null;
        String t = s.trim().replace("$", "").replace(".", "").replace(",", "").replace(" ", "");
        if (t.isEmpty() || !t.chars().allMatch(Character::isDigit)) return null;
        try {
            BigDecimal v = new BigDecimal(t);
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException e) { return null; }
    }

    /** Formato latino con punto de millar: 1250 → "$1.250". */
    private static String formatPrice(BigDecimal v) {
        try {
            DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
            sym.setGroupingSeparator('.');
            return "$" + new DecimalFormat("#,##0", sym).format(v);
        } catch (Throwable t) {
            return "$" + v.toPlainString();
        }
    }

    private static String joinFrom(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) { if (sb.length() > 0) sb.append(' '); sb.append(args[i]); }
        return sb.toString();
    }

    /**
     * Sanea el texto del anuncio para Discord: sin códigos de color, sin pings (@everyone/@here), sin
     * markdown activo (*_~`|&gt;), sin menciones/canales/roles/emojis crudos (&lt;@..&gt;), sin URLs
     * (anti-scam), sin controles ni separadores Unicode, espacios normalizados y recorte por palabra.
     */
    private static String sanitizeForDiscord(String raw, int maxLen) {
        if (raw == null) return "";
        String s = ChatColor.stripColor(raw).trim();
        s = s.replaceAll("(?i)@(everyone|here)", "@​$1");                  // romper pings
        s = s.replaceAll("([*_~`|>])", "$1​");                            // neutralizar markdown
        s = s.replaceAll("(?i)\\b(?:https?://|www\\.)\\S+", "[enlace retirado]"); // anti-scam
        s = s.replaceAll("<(?=[@#:])", "<​");                             // romper menciones/canales/roles/emojis crudos
        s = s.replaceAll("[\\u0000-\\u001F\\u007F\\u0085\\u2028\\u2029]", " ");  // controles + separadores Unicode (U+2028/29...)
        s = s.replaceAll("\\s+", " ").trim();                                  // normalizar espacios
        if (s.length() > maxLen) {
            String cut = s.substring(0, maxLen);
            int sp = cut.lastIndexOf(' ');
            if (sp > maxLen - 20 && sp > 0) cut = cut.substring(0, sp);        // retroceder a palabra
            s = cut.trim() + "…";
        }
        return s;
    }
}
