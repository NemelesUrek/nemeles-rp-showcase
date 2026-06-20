package com.nemeles.combat;

/** Skin COSMETICA de un arma (de config.yml). NO cambia stats ni comportamiento: solo el model-data/visual.
 *  El arma sigue identificandose por su gun_id (PDC); la skin es un PDC aparte que solo altera applyVisual. */
public final class GunSkin {
    public String id;        // id de la skin (p.ej. deagle_blaze)
    public String weapon;    // id del arma base a la que aplica (pistol/smg/rifle/...)
    public int modelData;    // CustomModelData de la skin -> el pack/Geyser lo mapea a su textura/identifier
    public String name;      // nombre mostrado (admite codigos & de color)
}
