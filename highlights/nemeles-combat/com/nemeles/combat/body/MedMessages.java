package com.nemeles.combat.body;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mensajes de heridas DATA-DRIVEN (los escribe el equipo de IAs, se recargan sin recompilar):
 * plugins/NemelesCombat/mensajes_heridas.yml — formato: lista "CLAVE|texto" bajo la clave 'mensajes',
 * o secciones CLAVE: [textos]. {parte} se sustituye por la parte del cuerpo. Si una clave no existe,
 * el codigo usa sus textos integrados (fallback): el yml solo ANADE variedad.
 */
public final class MedMessages {

    private final Map<String, List<String>> byKey = new HashMap<>();

    public MedMessages(Plugin plugin) { reload(plugin); }

    public void reload(Plugin plugin) {
        byKey.clear();
        try {
            File f = new File(plugin.getDataFolder(), "mensajes_heridas.yml");
            if (!f.exists()) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            // formato 1: lista plana "CLAVE|texto"
            for (String line : y.getStringList("mensajes")) {
                int i = line.indexOf('|');
                if (i <= 0) continue;
                byKey.computeIfAbsent(line.substring(0, i).trim().toUpperCase(Locale.ROOT),
                        k -> new java.util.ArrayList<>()).add(line.substring(i + 1).trim());
            }
            // formato 2: secciones CLAVE: [textos]
            for (String key : y.getKeys(false)) {
                if (key.equals("mensajes")) continue;
                List<String> l = y.getStringList(key);
                if (!l.isEmpty()) byKey.computeIfAbsent(key.toUpperCase(Locale.ROOT),
                        k -> new java.util.ArrayList<>()).addAll(l);
            }
            plugin.getLogger().info("[MED] mensajes_heridas.yml: " + byKey.size() + " claves cargadas.");
        } catch (Throwable t) {
            plugin.getLogger().warning("[MED] mensajes_heridas.yml: " + t.getMessage());
        }
    }

    /** Variante aleatoria de la clave, o null si el yml no la trae (usa entonces el texto integrado). */
    public String pick(String key) {
        List<String> l = byKey.get(key.toUpperCase(Locale.ROOT));
        if (l == null || l.isEmpty()) return null;
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    /** Variante del yml si existe; si no, una de las integradas. */
    public String pickOr(String key, String... fallback) {
        String s = pick(key);
        if (s != null) return s;
        return fallback[ThreadLocalRandom.current().nextInt(fallback.length)];
    }
}
