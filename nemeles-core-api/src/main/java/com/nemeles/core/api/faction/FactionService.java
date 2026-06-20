package com.nemeles.core.api.faction;

import org.bukkit.Location;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Sistema de facciones/mafias. Lo registra el módulo nemeles-factions y lo consumen otros módulos
 * (policía, futuros territorios) sin acoplarse. Lecturas síncronas desde caché; las escrituras mutan
 * la caché y persisten de forma asíncrona.
 */
public interface FactionService {

    // ── lecturas (caché) ──
    Optional<Faction> getFaction(int factionId);
    Optional<Faction> getFactionByTag(String tag);
    Optional<Faction> getFactionOf(UUID player);
    boolean isMember(UUID player, int factionId);
    int rankPriorityOf(UUID player);                 // 0..3, -1 si ninguna
    boolean hasPermission(UUID player, FactionPermission perm);
    Relation relation(int facA, int facB);
    boolean canDamage(UUID attacker, UUID victim);   // friendly-fire central
    UUID accountId(int factionId);                   // UUID sintético del banco
    Collection<Faction> all();
    Optional<Location> getHome(int factionId);       // home de la mafia (vacío si no hay)

    // ── escrituras (mutan caché + persisten async) ──
    FactionResult create(String name, String tag, UUID leader);
    FactionResult disband(int factionId);
    FactionResult addMember(int factionId, UUID player);
    FactionResult removeMember(UUID player);
    FactionResult setRank(int factionId, UUID player, int priority);
    FactionResult transferLeadership(int factionId, UUID newLeader);
    FactionResult setRelation(int facA, int facB, Relation rel);
    FactionResult setHome(int factionId, Location location);
    /** Define/borra un override de permiso para un rango (priority 1..3); el líder siempre puede todo. */
    FactionResult setRankPermission(int factionId, int rankPriority, FactionPermission perm, boolean allowed);
}
