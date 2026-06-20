package com.nemeles.npcai.listener;

import com.nemeles.npcai.ConversationManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Captura el chat mientras hablas con un NPC (privado, no va al chat global). Crossplay vía Geyser. */
public final class NpcChatListener implements Listener {

    private final Plugin plugin;
    private final ConversationManager mgr;

    public NpcChatListener(Plugin plugin, ConversationManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!mgr.isActive(p.getUniqueId())) return;
        e.setCancelled(true); // conversación privada con el NPC
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> mgr.handle(p, msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { mgr.end(e.getPlayer().getUniqueId()); }
}
