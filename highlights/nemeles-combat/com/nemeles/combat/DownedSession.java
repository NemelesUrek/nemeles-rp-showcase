package com.nemeles.combat;

import com.nemeles.core.api.combat.DownState;
import org.bukkit.Location;
import org.bukkit.boss.BossBar;

import java.util.UUID;

/** Sesion de derribo en memoria (NO se persiste: desconectar derribado = muerte por combat-log). */
public final class DownedSession {

    public final UUID player;
    public DownState state = DownState.DOWNED;
    public UUID source;          // agresor (nullable = entorno)
    public int bleedoutLeft;
    public int bleedoutTotal;
    public long downedAt;
    public Location downLoc;
    public BossBar bar;
    public int channelTicks;     // progreso de reanimacion (en ticks de 1s)
    public UUID reviver;
    public boolean masked;
    public String cause;
    public boolean stabilized;   // transfusion recibida: puede arrastrarse (traslado)
    public int transfusedTotal;  // segundos extra ya recibidos (tope en config)
    public int transfuseTicks;   // progreso del canal de transfusion
    public UUID transfuser;

    public DownedSession(UUID player) {
        this.player = player;
    }
}
