package com.nemeles.jobs.weed;

import org.bukkit.Location;

/** Una planta de marihuana viva. La fuente de verdad es la BD (sobrevive reinicios). */
public final class WeedPlant {

    public final String id;
    public final String owner;
    public final String world;
    public final int x;
    public final int y;
    public final int z;
    public final long plantedAt;
    public int stage;
    public String state; // GROWING | DEAD
    public boolean cared;  // ya recibio fertilizante (si no, puede pudrirse al cosechar)
    public int quality;    // 0 = sin cuidar, 1 = fertilizada BIEN (minijuego ok), -1 = mezcla fallida

    public WeedPlant(String id, String owner, String world, int x, int y, int z,
                     long plantedAt, int stage, String state) {
        this.id = id;
        this.owner = owner;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.plantedAt = plantedAt;
        this.stage = stage;
        this.state = state;
    }

    public String locKey() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public static String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
