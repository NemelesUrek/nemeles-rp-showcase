package com.nemeles.npcai.command;

import com.nemeles.npcai.ConversationManager;
import com.nemeles.npcai.NemelesNpcAiPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.entity.Entity;
import com.nemeles.npcai.AffinityManager;
import com.nemeles.npcai.AiConfig;
import com.nemeles.npcai.NpcPersona;
import com.nemeles.npcai.RelationInfo;

/** /npcai (admin: reload/list, jugador: salir) y /hablar &lt;mensaje&gt; (alternativa al chat). */
public final class NpcAiCommands implements CommandExecutor {

    private final NemelesNpcAiPlugin plugin;
    private final ConversationManager mgr;
    private final Supplier<AffinityManager> affinity;   // null-safe

    public NpcAiCommands(NemelesNpcAiPlugin plugin, ConversationManager mgr, Supplier<AffinityManager> affinity) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.affinity = affinity;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("relacion") || name.equals("relación")) {
            if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
            showRelation(p, a.length >= 1 ? String.join(" ", a) : null);
            return true;
        }

        if (name.equals("hablar") || name.equals("decir")) {
            if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
            if (a.length == 0) { p.sendMessage("§7Uso: /hablar <mensaje>"); return true; }
            if (!mgr.isActive(p.getUniqueId())) { p.sendMessage("§7No estás hablando con nadie. Haz clic derecho en un NPC."); return true; }
            mgr.handle(p, String.join(" ", a));
            return true;
        }

        // /voz — enlace a la pagina para hablar con los NPCs por MICROFONO (el navegador transcribe).
        if (name.equals("voz") || name.equals("microfono") || name.equals("voice")) {
            if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
            com.nemeles.npcai.VoiceServer v = plugin.voice();
            if (v == null) { p.sendMessage("§7La página de voz está desactivada (voice-chat.enabled) o su puerto está ocupado."); return true; }
            String link = v.issueToken(p);
            p.sendMessage("§d§lHabla con los NPCs por micrófono §7— abre este enlace en tu navegador:");
            p.sendMessage(net.kyori.adventure.text.Component.text("  ➤ ABRIR LA PÁGINA DE VOZ (clic aquí)")
                    .color(net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link))
                    .hoverEvent(net.kyori.adventure.text.Component.text(link)));
            p.sendMessage("§7Si el clic no funciona (Bedrock), cópialo: §f" + link);
            p.sendMessage("§8Pulsa el micro y habla cerca de un NPC: la charla se inicia sola. Di «adiós» para terminar.");
            return true;
        }

        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "salir" -> {
                if (s instanceof Player p) { mgr.end(p); p.sendMessage("§7Terminaste la conversación."); }
                else s.sendMessage("Solo jugadores.");
            }
            case "reload" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                plugin.reloadAll();
                s.sendMessage("§aNemelesNpcAI recargado. NPCs: §f" + plugin.personaKeys());
            }
            case "list" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                s.sendMessage("§eNPCs de IA (nombra una entidad con una de estas claves): §f" + plugin.personaKeys());
            }
            case "skin" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                plugin.applyConfiguredSkins(s);
            }
            case "roster" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Ejecutalo en el juego, donde quieras el centro de la ciudad."); return true; }
                if (plugin.life() == null) { s.sendMessage("§cCitizens no está instalado: no puedo crear NPCs."); return true; }
                String op = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (op.equals("clear")) {
                    if (a.length < 3 || !a[2].equalsIgnoreCase("confirmar")) {
                        p.sendMessage("§eEsto BORRARÁ todos los NPCs de la ciudad. Confirma: §f/npcai roster clear confirmar");
                        return true;
                    }
                    int n = plugin.life().clearRoster();
                    p.sendMessage("§aBorrados §f" + n + "§a NPC(s) de la ciudad.");
                } else {
                    p.sendMessage("§7Creando los NPCs de la ciudad alrededor de ti (los que falten)...");
                    int n = plugin.life().spawnRoster(p);
                    p.sendMessage("§aCreados §f" + n + "§a NPC(s) nuevos. Pasearán solos; haz clic derecho para hablar. "
                            + "§7(de §f" + plugin.personaCount() + "§7 personas en total)");
                }
            }
            case "protas", "protagonistas" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Ejecútalo en el juego, donde quieras que aparezcan."); return true; }
                if (plugin.life() == null) { s.sendMessage("§cCitizens no está instalado: no puedo crear NPCs."); return true; }
                String op = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (op.equals("clear")) {
                    if (a.length < 3 || !a[2].equalsIgnoreCase("confirmar")) {
                        p.sendMessage("§eEsto BORRARÁ a los 5 protagonistas (para re-spawnearlos). Confirma: §f/npcai protas clear confirmar");
                        return true;
                    }
                    int n = plugin.life().clearProtagonists();
                    p.sendMessage("§aBorrados §f" + n + "§a protagonista(s). Vuelve a crearlos con §f/npcai protas");
                } else {
                    p.sendMessage("§7Creando a los protagonistas que falten alrededor de ti...");
                    int n = plugin.life().spawnProtagonists(p);
                    p.sendMessage("§aCreados §f" + n + "§a protagonista(s): §7Simón, Maid, Miss, Chibi, Luna. "
                            + "§7Haz clic derecho para hablar.");
                    // Re-aplica las skins configuradas (tambien a los que YA existian con skin random).
                    plugin.applyConfiguredSkins(p);
                }
            }
            case "bond" -> {
                com.nemeles.npcai.BondManager b = plugin.bond();
                if (b == null || !b.enabled()) { s.sendMessage("§7El vínculo de alma gemela no está activo."); return true; }
                String op = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (op.equals("reset")) {
                    if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                    b.adminReset();
                    s.sendMessage("§aVínculo de alma gemela de §f" + b.persona() + "§a reiniciado: vuelve a estar libre.");
                } else if (op.equals("grant") || op.equals("dar") || op.equals("restaurar")) {
                    if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                    Player target;
                    if (a.length >= 3) target = Bukkit.getPlayerExact(a[2]);
                    else target = (s instanceof Player p) ? p : null;
                    if (target == null) { s.sendMessage("§cJugador no encontrado/no conectado. Uso: §f/npcai bond grant <jugador>"); return true; }
                    NpcPersona per = plugin.cfg().match(b.persona());
                    String npcName = per != null ? per.name : b.persona();
                    b.adminGrant(target, npcName);
                    s.sendMessage("§aVínculo de §f" + npcName + "§a concedido a §f" + target.getName() + "§a (y entregado el Lazo).");
                } else if (b.hasOwner()) {
                    s.sendMessage("§d" + b.persona() + " §7ya tiene compañero del alma: §f" + b.ownerName());
                } else {
                    s.sendMessage("§d" + b.persona() + " §7aún no tiene compañero del alma. Alguien podría ganárselo... si está a la altura.");
                }
            }
            case "vida", "salud" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                com.nemeles.npcai.NpcCombatManager cm = plugin.combat();
                if (cm == null) { s.sendMessage("§cCitizens/combate no disponible."); return true; }
                int n = cm.sweepExisting();
                s.sendMessage("§aAplicada vida a §f" + n + "§a NPC(s) (ahora son danables).");
            }
            case "relacion", "relación", "rel" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
                String target = a.length >= 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : null;
                showRelation(p, target);
            }
            case "afinidad", "amistad" -> {
                if (!s.hasPermission("nemeles.npcai.admin")) { s.sendMessage("§cSin permiso."); return true; }
                if (!(s instanceof Player p)) { s.sendMessage("Ejecútalo en el juego."); return true; }
                AffinityManager aff = affinity == null ? null : affinity.get();
                if (aff == null) { p.sendMessage("§cLa afinidad no está disponible (BD apagada)."); return true; }
                String op = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (op.equals("reset")) {
                    if (a.length >= 3) {
                        NpcPersona per = plugin.cfg().match(String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
                        if (per == null) { p.sendMessage("§7No conozco a ese NPC. Claves: §f" + plugin.personaKeys()); return true; }
                        aff.set(per.key, p.getUniqueId(), 0);
                        p.sendMessage("§aTu amistad con §f" + per.name + "§a vuelve a §f0§a (neutral).");
                    } else {
                        int n = 0;
                        for (String key : plugin.cfg().personas.keySet()) {
                            try { aff.set(key, p.getUniqueId(), 0); n++; } catch (Throwable ignored) { }
                        }
                        // ademas: cura + calma a los NPCs spawneados (si estaban heridos seguirian atacando aunque seas neutral).
                        int healed = plugin.combat() != null ? plugin.combat().pacifyAll() : 0;
                        p.sendMessage("§aTu amistad con §f" + n + "§a NPC(s) vuelve a §f0§a (neutral). §7Curados y calmados: §f" + healed);
                    }
                } else if (op.equals("set") && a.length >= 4) {
                    NpcPersona per = plugin.cfg().match(a[2]);
                    if (per == null) { p.sendMessage("§7No conozco a §f" + a[2] + "§7. Claves: §f" + plugin.personaKeys()); return true; }
                    int val;
                    try { val = Integer.parseInt(a[3]); } catch (NumberFormatException ex) { p.sendMessage("§7Valor no válido (-100..100)."); return true; }
                    val = Math.max(-100, Math.min(100, val));
                    aff.set(per.key, p.getUniqueId(), val);
                    p.sendMessage("§aTu afinidad con §f" + per.name + "§a fijada a §f" + val + "§a.");
                } else {
                    p.sendMessage("§e/npcai afinidad reset [npc]   §7(sin npc = TODOS a 0)   §7·   §e/npcai afinidad set <npc> <-100..100>");
                }
            }
            default -> s.sendMessage("§e/npcai list | reload | skin | roster | protas | bond [reset|grant <jugador>] | vida | relacion [nombre] | afinidad reset [npc] | salir   §7·   §e/hablar <mensaje>   §7·   §e/relacion");
        }
        return true;
    }

    /** Muestra en chat la relacion del jugador con un NPC: por NOMBRE si se da, si no el que tiene delante. */
    private void showRelation(Player p, String byName) {
        AiConfig cfg = plugin.cfg();
        NpcPersona persona = (byName != null && !byName.isBlank()) ? cfg.match(byName) : facingPersona(p, cfg);
        if (persona == null) {
            if (byName != null && !byName.isBlank())
                p.sendMessage("§7No conozco a ningun NPC llamado §f" + byName + "§7. Usa §f/npcai list§7 para ver las claves.");
            else
                p.sendMessage("§7Ponte mirando a un NPC (o usa §f/relacion <nombre>§7).");
            return;
        }
        AffinityManager aff = affinity == null ? null : affinity.get();
        for (String line : RelationInfo.chatLines(cfg, aff, persona, p)) {
            p.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    /** El persona del NPC mas cercano al que el jugador esta MIRANDO (cono frontal), o null. */
    private NpcPersona facingPersona(Player p, AiConfig cfg) {
        org.bukkit.util.Vector dir = p.getEyeLocation().getDirection();
        org.bukkit.Location eye = p.getEyeLocation();
        NpcPersona best = null;
        double bestDot = 0.55;
        double range = 6.0;
        for (Entity ent : p.getNearbyEntities(range, range, range)) {
            NpcPersona per = cfg.match(ent.getName() == null ? null : org.bukkit.ChatColor.stripColor(ent.getName()));
            if (per == null) continue;
            org.bukkit.util.Vector to = ent.getLocation().add(0, ent.getHeight() * 0.5, 0)
                    .toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.1 || dist > range) continue;
            double dot = to.normalize().dot(dir);
            if (dot > bestDot) { bestDot = dot; best = per; }
        }
        return best;
    }
}
