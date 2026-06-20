package com.nemeles.core.api.property;

import java.util.UUID;

/** Vista inmutable de una propiedad. */
public record PropertySnapshot(int id, String name, PropertyType type, String world, String regionId,
                               String ownerType, UUID ownerUuid, int ownerFaction, boolean rented,
                               long priceCents, long rentCents, long paidUntil, boolean forSale, String state) {

    public boolean hasOwner() { return !"NONE".equals(ownerType); }
}
