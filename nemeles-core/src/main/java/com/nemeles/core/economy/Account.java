package com.nemeles.core.economy;

import com.nemeles.core.api.economy.MoneyType;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Cuenta en memoria de un jugador: los 4 saldos en centimos + un lock para mutaciones atomicas. */
final class Account {

    private final UUID uuid;
    private final ReentrantLock lock = new ReentrantLock();
    private long efectivo;
    private long banco;
    private long sucio;
    private long limpio;

    Account(UUID uuid) {
        this.uuid = uuid;
    }

    UUID uuid() { return uuid; }
    ReentrantLock lock() { return lock; }

    long get(MoneyType type) {
        return switch (type) {
            case EFECTIVO -> efectivo;
            case BANCO -> banco;
            case SUCIO -> sucio;
            case LIMPIO -> limpio;
        };
    }

    void set(MoneyType type, long value) {
        switch (type) {
            case EFECTIVO -> efectivo = value;
            case BANCO -> banco = value;
            case SUCIO -> sucio = value;
            case LIMPIO -> limpio = value;
        }
    }

    void setAll(long efectivo, long banco, long sucio, long limpio) {
        this.efectivo = efectivo;
        this.banco = banco;
        this.sucio = sucio;
        this.limpio = limpio;
    }

    long efectivo() { return efectivo; }
    long banco() { return banco; }
    long sucio() { return sucio; }
    long limpio() { return limpio; }
}
