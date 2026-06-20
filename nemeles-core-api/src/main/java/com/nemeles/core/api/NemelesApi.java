package com.nemeles.core.api;

import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.core.api.economy.EconomyService;
import com.nemeles.core.api.faction.FactionService;
import com.nemeles.core.api.police.WantedService;
import com.nemeles.core.api.combat.CombatService;
import com.nemeles.core.api.profile.ProfileService;
import com.nemeles.core.api.property.PropertyService;
import com.nemeles.core.api.region.RegionService;
import com.nemeles.core.api.skills.SkillService;
import com.nemeles.core.api.territory.TerritoryService;
import com.nemeles.core.api.vehicle.VehicleService;

/**
 * Fachada estatica del nucleo NemelesRP. El plugin NemelesCore registra aqui sus servicios
 * en onEnable; el resto de modulos (telefono, economia ilegal, facciones...) los consumen.
 *
 * <p>Uso desde un modulo: {@code NemelesApi.economy().deposit(uuid, MoneyType.LIMPIO, monto, "venta")}.
 * Comprueba {@link #isReady()} si tu modulo puede arrancar antes que el core.</p>
 */
public final class NemelesApi {

    private static DatabaseProvider database;
    private static EconomyService economy;
    private static ProfileService profiles;
    private static RegionService regions;
    private static WantedService wanted;
    private static FactionService factions;
    private static TerritoryService territories;
    private static CombatService combat;
    private static SkillService skills;
    private static VehicleService vehicles;
    private static PropertyService properties;

    private NemelesApi() {}

    // ─── Registro (lo llama NemelesCore) ─────────────────────────────
    public static void registerDatabase(DatabaseProvider provider) { database = provider; }
    public static void registerEconomy(EconomyService service)     { economy = service; }
    public static void registerProfiles(ProfileService service)    { profiles = service; }
    public static void registerRegions(RegionService service)      { regions = service; }
    public static void registerWanted(WantedService service)       { wanted = service; }
    public static void registerFactions(FactionService service)    { factions = service; }
    public static void registerTerritories(TerritoryService s)      { territories = s; }
    public static void registerCombat(CombatService service)        { combat = service; }
    public static void registerSkills(SkillService service)          { skills = service; }
    public static void registerVehicles(VehicleService service)      { vehicles = service; }
    public static void registerProperties(PropertyService service)   { properties = service; }

    private static com.nemeles.core.api.career.CareerService careers;
    private static com.nemeles.core.api.heat.HeatService heat;
    private static com.nemeles.core.api.identity.IdentityService identity;

    public static void registerCareers(com.nemeles.core.api.career.CareerService service) { careers = service; }
    public static void registerHeat(com.nemeles.core.api.heat.HeatService service) { heat = service; }
    /** Lo registra nemeles-identity (no el core). Puede no estar si ese plugin no carga. */
    public static void registerIdentity(com.nemeles.core.api.identity.IdentityService service) { identity = service; }

    public static com.nemeles.core.api.career.CareerService careers() { return require(careers, "CareerService"); }
    public static com.nemeles.core.api.heat.HeatService heat() { return require(heat, "HeatService"); }
    /** Puede devolver NULL si nemeles-identity no está cargado: el llamante DEBE tolerarlo (no usa require). */
    public static com.nemeles.core.api.identity.IdentityService identity() { return identity; }

    /** Limpia todas las referencias (lo llama NemelesCore en onDisable). */
    public static void clear() {
        database = null; economy = null; profiles = null; regions = null; wanted = null; factions = null;
        territories = null; combat = null; skills = null; vehicles = null; properties = null; careers = null;
        heat = null; identity = null;
    }

    // ─── Acceso (lo usan los modulos) ────────────────────────────────
    public static DatabaseProvider database() { return require(database, "DatabaseProvider"); }
    public static EconomyService economy()    { return require(economy, "EconomyService"); }
    public static ProfileService profiles()   { return require(profiles, "ProfileService"); }
    public static RegionService regions()     { return require(regions, "RegionService"); }
    public static WantedService wanted()      { return require(wanted, "WantedService"); }
    public static FactionService factions()   { return require(factions, "FactionService"); }
    public static TerritoryService territories() { return require(territories, "TerritoryService"); }
    public static CombatService combat()      { return require(combat, "CombatService"); }
    public static SkillService skills()       { return require(skills, "SkillService"); }
    public static VehicleService vehicles()   { return require(vehicles, "VehicleService"); }
    public static PropertyService properties() { return require(properties, "PropertyService"); }

    /** true cuando al menos la capa de datos del core esta lista. */
    public static boolean isReady() { return database != null; }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("NemelesCore: " + name + " aun no esta disponible. "
                    + "Asegurate de que NemelesCore cargo correctamente y de declarar 'depend: NemelesCore'.");
        }
        return value;
    }
}
