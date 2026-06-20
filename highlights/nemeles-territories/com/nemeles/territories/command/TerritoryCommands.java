package com.nemeles.territories.command;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.faction.Faction;
import com.nemeles.core.api.territory.TerritoryResult;
import com.nemeles.core.api.territory.TerritoryService;
import com.nemeles.core.api.territory.TerritorySnapshot;
import com.nemeles.territories.TerritoryManager;
import com.nemeles.territories.TerritoryServiceImpl;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** /territorio (admin) y /turf (jugador). */
public final class TerritoryCommands implements CommandExecutor {

    private final Plugin plugin;
    private final TerritoryServiceImpl svc;
    private final TerritoryManager manager;

    public TerritoryCommands(Plugin plugin, TerritoryServiceImpl svc, TerritoryManager manager) {
        this.plugin = plugin;
        this.svc = svc;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("territorio")) return admin(sender, args);
        return turf(sender, args);
    }

    // ─── /territorio (admin) ─────────────────────────────────
    private boolean admin(CommandSender s, String[] a) {
        if (a.length == 0) { adminHelp(s); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "crear", "create" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cSolo jugadores (usa tu seleccion de WorldEdit)."); return true; }
                if (a.length < 2) { s.sendMessage("§e/territorio crear <nombre> [tier]"); return true; }
                int tier = a.length >= 3 ? parseInt(a[2], 1) : 1;
                Set<Long> chunks = selectionChunks(p);
                if (chunks == null || chunks.isEmpty()) {
                    s.sendMessage("§cNo tienes una seleccion de WorldEdit valida. Usa §f//wand§c y marca 2 esquinas.");
                    return true;
                }
                TerritoryResult r = svc.define(a[1], p.getWorld(), tier, chunks);
                if (r.success()) {
                    s.sendMessage("§a[Territorios] Creado §f" + a[1] + " §a(" + chunks.size() + " chunks, tier " + clampTier(tier) + ", id " + r.territoryId() + ").");
                } else {
                    s.sendMessage("§c[Territorios] No se pudo crear: " + err(r.errorCode()));
                }
            }
            case "addchunk" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cSolo jugadores."); return true; }
                TerritorySnapshot t = byName(s, a);
                if (t == null) return true;
                long key = TerritoryService.chunkKeyOf(p.getLocation());
                TerritoryResult r = svc.addChunks(t.id(), p.getWorld(), Set.of(key));
                if (r.success()) s.sendMessage("§a[Territorios] Chunk anadido a §f" + t.name() + "§a.");
                else s.sendMessage("§c[Territorios] No se pudo: " + err(r.errorCode()));
            }
            case "borrar", "delete" -> {
                TerritorySnapshot t = byName(s, a);
                if (t == null) return true;
                svc.dissolve(t.id());
                s.sendMessage("§a[Territorios] Borrado §f" + t.name() + "§a.");
            }
            case "dueno", "dueño", "owner" -> {
                TerritorySnapshot t = byName(s, a);
                if (t == null) return true;
                if (a.length < 3) { s.sendMessage("§e/territorio dueno <nombre> <tag|neutral>"); return true; }
                int facId;
                if (a[2].equalsIgnoreCase("neutral")) {
                    facId = -1;
                } else {
                    facId = factionIdByTag(a[2]);
                    if (facId < 0) { s.sendMessage("§cNo existe una mafia con tag '" + a[2] + "'."); return true; }
                }
                svc.setOwner(t.id(), facId, "admin");
                s.sendMessage("§a[Territorios] Dueno de §f" + t.name() + " §a-> " + (facId < 0 ? "neutral" : a[2]) + ".");
            }
            case "renta", "income" -> {
                TerritorySnapshot t = byName(s, a);
                if (t == null) return true;
                if (a.length < 3) { s.sendMessage("§e/territorio renta <nombre> <cantidad>"); return true; }
                long cents = Math.round(parseDouble(a[2], 0) * 100.0);
                svc.setIncome(t.id(), cents);
                s.sendMessage("§a[Territorios] Renta base de §f" + t.name() + " §a-> $" + (cents / 100.0) + "/periodo.");
            }
            case "tier" -> {
                TerritorySnapshot t = byName(s, a);
                if (t == null) return true;
                if (a.length < 3) { s.sendMessage("§e/territorio tier <nombre> <1-3>"); return true; }
                int tier = parseInt(a[2], 1);
                svc.setTier(t.id(), tier);
                s.sendMessage("§a[Territorios] Tier de §f" + t.name() + " §a-> " + clampTier(tier) + ".");
            }
            case "info" -> {
                TerritorySnapshot t = byName(s, a);
                if (t == null) return true;
                sendInfo(s, t);
            }
            case "recargar", "reload" -> {
                plugin.reloadConfig();
                s.sendMessage("§e[Territorios] Config recargada (algunos valores requieren reiniciar para aplicarse).");
            }
            default -> adminHelp(s);
        }
        return true;
    }

    // ─── /turf (jugador) ─────────────────────────────────────
    private boolean turf(CommandSender s, String[] a) {
        switch (a.length == 0 ? "lista" : a[0].toLowerCase(Locale.ROOT)) {
            case "lista", "list" -> {
                s.sendMessage("§6== Territorios ==");
                var all = svc.all();
                if (all.isEmpty()) { s.sendMessage("§7No hay territorios definidos."); return true; }
                for (TerritorySnapshot t : all) {
                    String owner = t.isNeutral() ? "§7neutral" : "§e" + tag(t.ownerFactionId());
                    String st = svc.isContested(t.id()) ? "§cEN DISPUTA"
                            : ("DECAY".equals(t.state()) ? "§8DECAY" : "§aactivo");
                    s.sendMessage("§7- §f" + t.name() + " §7(T" + t.tier() + ") · " + owner + " §7· " + st);
                }
            }
            case "info" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cSolo jugadores."); return true; }
                var ot = svc.territoryAt(p.getLocation());
                if (ot.isEmpty()) { s.sendMessage("§7No estas dentro de ningun territorio."); return true; }
                sendInfo(s, ot.get());
            }
            case "mias", "mine" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cSolo jugadores."); return true; }
                int fac = factionId(p);
                if (fac < 0) { s.sendMessage("§cNo estas en ninguna mafia."); return true; }
                var owned = svc.ownedBy(fac);
                s.sendMessage("§6== Territorios de tu mafia (" + owned.size() + "/" + manager.maxZonesPerFaction() + ") ==");
                if (owned.isEmpty()) s.sendMessage("§7Tu mafia no controla territorios.");
                for (TerritorySnapshot t : owned) {
                    s.sendMessage("§7- §f" + t.name() + " §7(T" + t.tier() + ", " + t.state() + ")");
                }
            }
            case "atacar", "attack" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cSolo jugadores."); return true; }
                String err = manager.attemptDeclare(p);
                if (err != null) p.sendMessage(err);
            }
            case "guerra", "war" -> { return guerra(s, a); }
            default -> s.sendMessage("§e/turf <lista|info|mias|atacar|guerra>");
        }
        return true;
    }

    // ─── /turf guerra ────────────────────────────────────────
    private boolean guerra(CommandSender s, String[] a) {
        String sub = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "declarar", "declare" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cSolo jugadores."); return true; }
                if (a.length < 3) { s.sendMessage("§e/turf guerra declarar <territorio>"); return true; }
                String name = a[2];
                String err = manager.declareWar(p, name);
                if (err != null) p.sendMessage(err);
                else p.sendMessage("§6[Guerra] §eProcesando la declaracion sobre §f" + name + "§e...");
            }
            case "estado", "status" -> {
                var lines = manager.warStatusLines();
                s.sendMessage("§4== Guerras de territorio ==");
                if (lines.isEmpty()) { s.sendMessage("§7No hay guerras activas."); return true; }
                for (String line : lines) s.sendMessage(line);
            }
            default -> {
                s.sendMessage("§e/turf guerra declarar <territorio> §7- arriesga un bote para atacar una zona con dueno");
                s.sendMessage("§e/turf guerra estado §7- guerras en curso");
            }
        }
        return true;
    }

    // ─── WorldEdit: chunks de la seleccion del admin ─────────
    private Set<Long> selectionChunks(Player p) {
        try {
            com.sk89q.worldedit.bukkit.BukkitPlayer wep = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(p);
            com.sk89q.worldedit.LocalSession session = com.sk89q.worldedit.WorldEdit.getInstance().getSessionManager().get(wep);
            com.sk89q.worldedit.regions.Region region = session.getSelection(wep.getWorld());
            com.sk89q.worldedit.math.BlockVector3 min = region.getMinimumPoint();
            com.sk89q.worldedit.math.BlockVector3 max = region.getMaximumPoint();
            int minCX = min.getBlockX() >> 4, maxCX = max.getBlockX() >> 4;
            int minCZ = min.getBlockZ() >> 4, maxCZ = max.getBlockZ() >> 4;
            Set<Long> chunks = new HashSet<>();
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    chunks.add(TerritoryService.chunkKey(cx, cz));
                }
            }
            return chunks;
        } catch (Throwable t) {
            return null;
        }
    }

    // ─── helpers ─────────────────────────────────────────────
    private TerritorySnapshot byName(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage("§cIndica el nombre del territorio."); return null; }
        var ot = svc.getTerritoryByName(a[1]);
        if (ot.isEmpty()) { s.sendMessage("§cNo existe el territorio '" + a[1] + "'."); return null; }
        return ot.get();
    }

    private void sendInfo(CommandSender s, TerritorySnapshot t) {
        s.sendMessage("§6== " + t.name() + " §6(id " + t.id() + ") ==");
        s.sendMessage("§7Mundo: §f" + t.world() + " §7· chunks: §f" + t.chunkCount() + " §7· tier: §f" + t.tier());
        s.sendMessage("§7Dueno: " + (t.isNeutral() ? "§7neutral" : "§e" + tag(t.ownerFactionId())));
        s.sendMessage("§7Estado: §f" + (svc.isContested(t.id()) ? "EN DISPUTA" : t.state()));
        s.sendMessage("§7Renta base: §f$" + (t.incomeCents() / 100.0) + "§7/periodo");
        long now = System.currentTimeMillis();
        if (t.isShielded(now)) s.sendMessage("§7Escudo: §f" + ((t.shieldUntil() - now) / 60000) + " min restantes");
    }

    private void adminHelp(CommandSender s) {
        s.sendMessage("§6== /territorio (admin) ==");
        s.sendMessage("§e/territorio crear <nombre> [tier] §7- desde tu seleccion de WorldEdit");
        s.sendMessage("§e/territorio addchunk <nombre> §7- anade el chunk actual");
        s.sendMessage("§e/territorio borrar|info <nombre>");
        s.sendMessage("§e/territorio dueno <nombre> <tag|neutral>");
        s.sendMessage("§e/territorio renta <nombre> <cantidad> §7· §e/territorio tier <nombre> <1-3>");
    }

    private int factionId(Player p) {
        try { return NemelesApi.factions().getFactionOf(p.getUniqueId()).map(Faction::id).orElse(-1); }
        catch (Throwable t) { return -1; }
    }

    private int factionIdByTag(String tag) {
        try { return NemelesApi.factions().getFactionByTag(tag).map(Faction::id).orElse(-1); }
        catch (Throwable t) { return -1; }
    }

    private String tag(int id) {
        try { return NemelesApi.factions().getFaction(id).map(Faction::tag).orElse("?"); }
        catch (Throwable t) { return "?"; }
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    private static double parseDouble(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
    private static int clampTier(int t) { return Math.max(1, Math.min(3, t)); }
    private static String err(String code) { return code == null ? "desconocido" : code; }
}
