package com.nemeles.jobs.model;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Definicion de un trabajo: su id, nombre, categoria y las acciones remuneradas por material. */
public final class JobDefinition {

    private final String id;
    private final String displayName;
    private final JobCategory category;
    private final Map<Material, JobAction> actions = new LinkedHashMap<>();

    public JobDefinition(String id, String displayName, JobCategory category) {
        this.id = id;
        this.displayName = displayName;
        this.category = category;
    }

    public JobDefinition add(JobAction action) {
        actions.put(action.material(), action);
        return this;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public JobCategory category() { return category; }
    public JobAction action(Material material) { return actions.get(material); }
    public Map<Material, JobAction> actions() { return Collections.unmodifiableMap(actions); }
}
