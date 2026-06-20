package com.nemeles.core.region;

import com.nemeles.core.api.region.RegionService;
import com.nemeles.core.api.region.RegionSnapshot;
import com.nemeles.core.api.territory.TerritoryService;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;

/** Implementacion de {@link RegionService} sobre WorldGuard. Aisla la dependencia de WG del resto. */
public final class WorldGuardRegionService implements RegionService {

    private final RegionContainer container;
    private final StateFlag permadeath;
    private final StateFlag allowCrime;

    public WorldGuardRegionService(StateFlag permadeath, StateFlag allowCrime) {
        this.container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.permadeath = permadeath;
        this.allowCrime = allowCrime;
    }

    private RegionQuery query() {
        return container.createQuery();
    }

    private com.sk89q.worldedit.util.Location adapt(Location loc) {
        return BukkitAdapter.adapt(loc);
    }

    @Override
    public boolean isSafezone(Location loc) {
        StateFlag.State pvp = query().queryState(adapt(loc), (RegionAssociable) null, Flags.PVP);
        return pvp == StateFlag.State.DENY;
    }

    @Override
    public boolean canCommitCrime(Location loc) {
        if (isSafezone(loc)) {
            return false;
        }
        if (allowCrime == null) {
            return true;
        }
        return query().queryState(adapt(loc), (RegionAssociable) null, allowCrime) != StateFlag.State.DENY;
    }

    @Override
    public boolean isPermadeathZone(Location loc) {
        if (permadeath == null) {
            return false;
        }
        // SEGURIDAD (anti-footgun): la zona negra (muerte permanente) SOLO cuenta si la concede una
        // región CONCRETA (p.ej. "el_erial"), NUNCA el __global__. Poner el flag en __global__ por
        // error convertía el mundo entero en zona negra (toda muerte = instantánea + wipe). Ahora se
        // ignora el global: hay que marcar permadeath en una región real para que aplique.
        ApplicableRegionSet set = query().getApplicableRegions(adapt(loc));
        for (ProtectedRegion r : set) {
            if ("__global__".equals(r.getId())) {
                continue;
            }
            if (r.getFlag(permadeath) == StateFlag.State.ALLOW) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RegionSnapshot getRegionAt(Location loc) {
        ApplicableRegionSet set = query().getApplicableRegions(adapt(loc));
        ProtectedRegion best = null;
        for (ProtectedRegion r : set) {
            if (best == null || r.getPriority() > best.getPriority()) {
                best = r;
            }
        }
        if (best == null) {
            return null;
        }
        Set<String> owners = new HashSet<>();
        best.getOwners().getUniqueIds().forEach(u -> owners.add(u.toString()));
        owners.addAll(best.getOwners().getPlayers());
        return new RegionSnapshot(best.getId(), best.getPriority(), owners);
    }

    @Override
    public Set<String> regionIdsAt(Location loc) {
        ApplicableRegionSet set = query().getApplicableRegions(adapt(loc));
        Set<String> ids = new HashSet<>();
        for (ProtectedRegion r : set) {
            ids.add(r.getId());
        }
        return ids;
    }

    private RegionManager manager(World world) {
        return world == null ? null : container.get(BukkitAdapter.adapt(world));
    }

    @Override
    public boolean regionExists(World world, String regionId) {
        RegionManager m = manager(world);
        return m != null && m.hasRegion(regionId);
    }

    @Override
    public boolean regionContains(String regionId, Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        RegionManager m = manager(loc.getWorld());
        if (m == null) return false;
        ProtectedRegion r = m.getRegion(regionId);
        return r != null && r.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @Override
    public Set<Long> regionChunkKeys(World world, String regionId) {
        Set<Long> out = new HashSet<>();
        RegionManager m = manager(world);
        if (m == null) return out;
        ProtectedRegion r = m.getRegion(regionId);
        if (r == null) return out;
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 max = r.getMaximumPoint();
        int minCX = min.getBlockX() >> 4, maxCX = max.getBlockX() >> 4;
        int minCZ = min.getBlockZ() >> 4, maxCZ = max.getBlockZ() >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                out.add(TerritoryService.chunkKey(cx, cz));
            }
        }
        return out;
    }
}
