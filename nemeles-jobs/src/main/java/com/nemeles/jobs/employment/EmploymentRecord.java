package com.nemeles.jobs.employment;

/** Empleo de un jugador: su trabajo actual (null = sin empleo) y cuándo lo cambió por última vez. */
public final class EmploymentRecord {

    public String job;       // null = desempleado
    public long lastChange;  // epoch millis del ultimo cambio (para el cooldown)

    public EmploymentRecord(String job, long lastChange) {
        this.job = job;
        this.lastChange = lastChange;
    }
}
