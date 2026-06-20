package com.nemeles.jobs.zone;

import org.bukkit.Location;

/** Zona física de trabajo: dentro de ella, practicar el oficio asignado da XP extra. Cuboide simple por coordenadas. */
public final class WorkZone {

    public final String id, jobId, world, display;
    public final double bonus;             // multiplicador de XP dentro de la zona (p.ej. 1.5 = +50%)
    public final int minX, minY, minZ, maxX, maxY, maxZ;

    public WorkZone(String id, String jobId, String world, String display, double bonus,
                    int x1, int y1, int z1, int x2, int y2, int z2) {
        this.id = id;
        this.jobId = jobId;
        this.world = world;
        this.display = display;
        this.bonus = bonus;
        this.minX = Math.min(x1, x2); this.maxX = Math.max(x1, x2);
        this.minY = Math.min(y1, y2); this.maxY = Math.max(y1, y2);
        this.minZ = Math.min(z1, z2); this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        double x = l.getX(), y = l.getY(), z = l.getZ();
        return x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1;
    }

    public int bonusPercent() { return (int) Math.round((bonus - 1.0) * 100); }
}
