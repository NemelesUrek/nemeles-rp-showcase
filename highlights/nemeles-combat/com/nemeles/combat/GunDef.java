package com.nemeles.combat;

import org.bukkit.Material;

/** Definicion de un arma (cargada de config.yml; NO en BD). */
public final class GunDef {

    public String id;
    public Material baseItem;
    public double damage;
    public long fireDelayMs;   // 1000 / cadencia
    public double range;
    public int magSize;
    public String ammoId;      // null = cuerpo a cuerpo (sin municion)
    public int reloadTicks;
    public int pellets;        // 1 salvo escopeta
    public double spreadDegrees;
    public boolean legal;      // true = comprable en EFECTIVO; false = mercado negro (SUCIO)
    public int modelData;      // CustomModelData para la textura del pack (0 = ninguno, paridad vanilla)
    public String fireSound;   // sonido de disparo del pack (ej "nemeles:gun.pistol"); "" = vanilla
    public int tier;           // 0=pistola/cuchillo (siempre), 1=smg, 2=rifle/sniper: gating por alerta/evento
}
