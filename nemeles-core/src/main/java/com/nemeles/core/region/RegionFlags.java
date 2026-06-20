package com.nemeles.core.region;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;

import java.util.logging.Logger;

/**
 * Flags custom de WorldGuard de NemelesRP. DEBEN registrarse en onLoad() del plugin (antes de que
 * WorldGuard arranque), nunca en onEnable.
 */
public final class RegionFlags {

    /** Zona Negra: si una region tiene este flag en ALLOW, la muerte es permanente. */
    public static StateFlag PERMADEATH;
    /** Si una region tiene este flag en DENY, no se pueden cometer crimenes alli. */
    public static StateFlag ALLOW_CRIME;

    private RegionFlags() {}

    public static void register(Logger log) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        PERMADEATH = getOrCreate(registry, "nemeles-permadeath", false, log);
        ALLOW_CRIME = getOrCreate(registry, "nemeles-allow-crime", true, log);
        log.info("[WG] Flags de NemelesRP registrados (nemeles-permadeath, nemeles-allow-crime).");
    }

    private static StateFlag getOrCreate(FlagRegistry registry, String name, boolean def, Logger log) {
        try {
            StateFlag flag = new StateFlag(name, def);
            registry.register(flag);
            return flag;
        } catch (FlagConflictException e) {
            // Ya registrado (p.ej. tras un reload): reutiliza el existente.
            Flag<?> existing = registry.get(name);
            if (existing instanceof StateFlag sf) {
                return sf;
            }
            log.warning("[WG] Flag '" + name + "' en conflicto y no es StateFlag; se ignora.");
            return null;
        }
    }
}
