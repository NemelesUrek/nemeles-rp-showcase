package com.nemeles.core.economy;

import com.nemeles.core.api.db.DatabaseProvider;
import com.nemeles.core.api.economy.EconomyService;
import com.nemeles.core.api.economy.MarkedTrace;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.economy.TransactionResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Economia dual de NemelesRP. Toda operacion corre en el {@link DatabaseProvider#dbExecutor()} (fuera
 * del hilo principal), bloquea la(s) cuenta(s) afectada(s), persiste de forma transaccional (saldo +
 * audit-log) y revierte la memoria si la BD falla. El dinero se maneja como long de centimos.
 */
public final class DualEconomyService implements EconomyService {

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private final DatabaseProvider db;
    private final Logger log;
    private final MoneyType legacyType;
    private final Map<UUID, Account> cache = new ConcurrentHashMap<>();
    private final BalanceDao balances = new BalanceDao();
    private final AuditDao audit = new AuditDao();
    private final AtomicLong txSeq;

    public DualEconomyService(DatabaseProvider db, Logger log, MoneyType legacyType) {
        this.db = db;
        this.log = log;
        this.legacyType = legacyType;
        this.txSeq = new AtomicLong(loadMaxTx());
    }

    private long loadMaxTx() {
        try (Connection c = db.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT MAX(tx_id) FROM core_audit_log")) {
            if (rs.next()) {
                long v = rs.getLong(1);
                return rs.wasNull() ? 0L : v;
            }
        } catch (SQLException e) {
            log.warning("[ECO] No se pudo leer MAX(tx_id): " + e.getMessage());
        }
        return 0L;
    }

    private <T> CompletableFuture<T> supply(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, db.dbExecutor());
    }

    private Account account(Connection c, UUID uuid) throws SQLException {
        Account a = cache.get(uuid);
        if (a != null) {
            return a;
        }
        // get()+put() NO es atomico: dos hilos en cache-miss simultaneo para el MISMO uuid crearian dos
        // Account con LOCKS distintos y se romperia la exclusion por cuenta (doble pago de interes,
        // lost-update en deposit/withdraw/transfer). Cargamos fuera del lock del mapa y resolvemos con
        // putIfAbsent: todos terminan usando la MISMA instancia (la ganadora), un solo lock por jugador.
        Account fresh = new Account(uuid);
        balances.loadOrCreate(c, fresh);
        Account existing = cache.putIfAbsent(uuid, fresh);
        return existing != null ? existing : fresh;
    }

    private static long toCentsOrNeg(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return -1L;
        }
        try {
            return Money.toCents(amount);
        } catch (ArithmeticException e) {
            return -1L;
        }
    }

    private static void safeRollback(Connection c) {
        try { c.rollback(); } catch (SQLException ignored) { }
    }

    private static void restoreAutoCommit(Connection c) {
        try { c.setAutoCommit(true); } catch (SQLException ignored) { }
    }

    /**
     * Registra un lote de dinero limpio "marcado" (rastreable) producto de un lavado. Guarda el ORIGEN
     * ({@code channel}, ej. "business:ID"/"fachada:ID") y la {@code zona} (region donde se lavo, o null) para
     * que la policia pueda "seguir el dinero". El cajero lo "limpiara" (cleared_tx/cleared_at).
     */
    private void insertMarkedMoney(Connection c, String code, UUID owner, long amountCents, long sourceTx,
                                   String channel, String zona) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_marked_money (code, owner_uuid, amount_cents, source_tx_id, created_at, cleared, channel, zona) "
                        + "VALUES (?, ?, ?, ?, ?, 0, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, owner.toString());
            ps.setLong(3, amountCents);
            ps.setLong(4, sourceTx);
            ps.setLong(5, System.currentTimeMillis());
            if (channel == null) ps.setNull(6, java.sql.Types.VARCHAR); else ps.setString(6, trunc(channel, 48));
            if (zona == null) ps.setNull(7, java.sql.Types.VARCHAR); else ps.setString(7, trunc(zona, 48));
            ps.executeUpdate();
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Mejor-esfuerzo: resuelve el nombre de la ZONA (region de mayor prioridad) en la que esta el jugador
     * AHORA, para etiquetar el lavado. Se llama en el hilo del invocador (normalmente el principal) ANTES
     * de ir al executor de BD, para no consultar WorldGuard fuera de hilo. Devuelve null si no aplica.
     */
    private static String resolveZona(UUID player) {
        try {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(player);
            if (p == null) return null;
            var snap = com.nemeles.core.api.NemelesApi.regions().getRegionAt(p.getLocation());
            return snap == null ? null : snap.id();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ─── API ──────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<BigDecimal> balance(UUID player, MoneyType type) {
        return supply(() -> {
            try (Connection c = db.dataSource().getConnection()) {
                Account a = account(c, player);
                a.lock().lock();
                try {
                    return Money.fromCents(a.get(type));
                } finally {
                    a.lock().unlock();
                }
            }
        });
    }

    /** Snapshot de los 4 saldos (interfaz EconomyService; usado por comandos y la billetera). */
    @Override
    public CompletableFuture<Map<MoneyType, BigDecimal>> balances(UUID player) {
        return supply(() -> {
            try (Connection c = db.dataSource().getConnection()) {
                Account a = account(c, player);
                a.lock().lock();
                try {
                    Map<MoneyType, BigDecimal> m = new EnumMap<>(MoneyType.class);
                    for (MoneyType t : MoneyType.values()) {
                        m.put(t, Money.fromCents(a.get(t)));
                    }
                    return m;
                } finally {
                    a.lock().unlock();
                }
            }
        });
    }

    /** Lectura SINCRONA desde cache (para PAPI/scoreboard). Devuelve 0 si la cuenta no esta cargada
     *  (y dispara una carga async para la proxima consulta). */
    public BigDecimal cachedBalance(UUID player, MoneyType type) {
        Account a = cache.get(player);
        if (a == null) {
            balance(player, type); // precarga async
            return BigDecimal.ZERO;
        }
        a.lock().lock();
        try {
            return Money.fromCents(a.get(type));
        } finally {
            a.lock().unlock();
        }
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID player, MoneyType type, BigDecimal amount, String reason) {
        return supply(() -> applySingle(player, type, amount, reason, true));
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID player, MoneyType type, BigDecimal amount, String reason) {
        return supply(() -> applySingle(player, type, amount, reason, false));
    }

    private TransactionResult applySingle(UUID player, MoneyType type, BigDecimal amount, String reason, boolean deposit)
            throws SQLException {
        long cents = toCentsOrNeg(amount);
        if (cents <= 0) {
            return TransactionResult.fail("MONTO_INVALIDO");
        }
        try (Connection c = db.dataSource().getConnection()) {
            Account a = account(c, player);
            a.lock().lock();
            try {
                long before = a.get(type);
                if (!deposit && before < cents) {
                    return TransactionResult.fail("FONDOS_INSUFICIENTES");
                }
                long after = deposit ? before + cents : before - cents;
                a.set(type, after);
                try {
                    c.setAutoCommit(false);
                    balances.update(c, a);
                    long tx = txSeq.incrementAndGet();
                    if (deposit) {
                        audit.insert(c, tx, null, player.toString(), type, cents, "DEPOSIT", reason);
                    } else {
                        audit.insert(c, tx, player.toString(), null, type, cents, "WITHDRAW", reason);
                    }
                    c.commit();
                    return TransactionResult.ok(Money.fromCents(after), tx);
                } catch (SQLException e) {
                    safeRollback(c);
                    a.set(type, before);
                    throw e;
                } finally {
                    restoreAutoCommit(c);
                }
            } finally {
                a.lock().unlock();
            }
        }
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(UUID from, MoneyType fromType,
                                                         UUID to, MoneyType toType,
                                                         BigDecimal amount, String reason) {
        return supply(() -> {
            long cents = toCentsOrNeg(amount);
            if (cents <= 0) {
                return TransactionResult.fail("MONTO_INVALIDO");
            }
            if (from.equals(to) && fromType == toType) {
                return TransactionResult.fail("MISMA_CUENTA");
            }
            try (Connection c = db.dataSource().getConnection()) {
                Account a1 = account(c, from);
                Account a2 = account(c, to);
                // Lock en orden de UUID para evitar deadlocks (relevante con MySQL multi-hilo).
                Account first = from.compareTo(to) <= 0 ? a1 : a2;
                Account second = (first == a1) ? a2 : a1;
                first.lock().lock();
                second.lock().lock();
                try {
                    long fromBefore = a1.get(fromType);
                    if (fromBefore < cents) {
                        return TransactionResult.fail("FONDOS_INSUFICIENTES");
                    }
                    long toBefore = a2.get(toType);
                    a1.set(fromType, fromBefore - cents);
                    a2.set(toType, toBefore + cents);
                    try {
                        c.setAutoCommit(false);
                        balances.update(c, a1);
                        balances.update(c, a2);
                        long tx = txSeq.incrementAndGet();
                        audit.insert(c, tx, from.toString(), to.toString(), fromType, cents, "TRANSFER", reason);
                        c.commit();
                        return TransactionResult.ok(Money.fromCents(a1.get(fromType)), tx);
                    } catch (SQLException e) {
                        safeRollback(c);
                        a1.set(fromType, fromBefore);
                        a2.set(toType, toBefore);
                        throw e;
                    } finally {
                        restoreAutoCommit(c);
                    }
                } finally {
                    second.lock().unlock();
                    first.lock().unlock();
                }
            }
        });
    }

    @Override
    public CompletableFuture<TransactionResult> launder(UUID player, BigDecimal amount, BigDecimal feeRatio, String channel) {
        // Resolvemos la ZONA aqui (hilo del invocador, no el de BD) para no tocar WorldGuard fuera de hilo.
        final String zona = resolveZona(player);
        return supply(() -> {
            long cents = toCentsOrNeg(amount);
            if (cents <= 0) {
                return TransactionResult.fail("MONTO_INVALIDO");
            }
            if (feeRatio == null || feeRatio.signum() < 0 || feeRatio.compareTo(BigDecimal.ONE) >= 0) {
                return TransactionResult.fail("COMISION_INVALIDA");
            }
            long cleanCents;
            try {
                cleanCents = Money.toCents(amount.multiply(BigDecimal.ONE.subtract(feeRatio)));
            } catch (ArithmeticException e) {
                return TransactionResult.fail("MONTO_INVALIDO");
            }
            try (Connection c = db.dataSource().getConnection()) {
                Account a = account(c, player);
                a.lock().lock();
                try {
                    long sucioBefore = a.get(MoneyType.SUCIO);
                    long limpioBefore = a.get(MoneyType.LIMPIO);
                    if (sucioBefore < cents) {
                        return TransactionResult.fail("FONDOS_INSUFICIENTES");
                    }
                    a.set(MoneyType.SUCIO, sucioBefore - cents);
                    a.set(MoneyType.LIMPIO, limpioBefore + cleanCents);
                    try {
                        c.setAutoCommit(false);
                        balances.update(c, a);
                        long tx = txSeq.incrementAndGet();
                        audit.insert(c, tx, player.toString(), player.toString(), MoneyType.SUCIO, cents, "LAUNDER", channel);
                        // Dinero limpio "marcado" (rastreable): guarda origen (channel) y zona para la policia.
                        insertMarkedMoney(c, "MK-" + tx, player, cleanCents, tx, channel, zona);
                        c.commit();
                        return TransactionResult.ok(Money.fromCents(a.get(MoneyType.LIMPIO)), tx);
                    } catch (SQLException e) {
                        safeRollback(c);
                        a.set(MoneyType.SUCIO, sucioBefore);
                        a.set(MoneyType.LIMPIO, limpioBefore);
                        throw e;
                    } finally {
                        restoreAutoCommit(c);
                    }
                } finally {
                    a.lock().unlock();
                }
            }
        });
    }

    @Override
    public MoneyType legacyVaultType() {
        return legacyType;
    }

    // ─── CAJERO: limpia el rastro del dinero LIMPIO marcado ─────────────────
    /** Suma (en unidades) del dinero marcado SIN limpiar del jugador. Lectura async. */
    public CompletableFuture<BigDecimal> markedTotal(UUID player) {
        return supply(() -> {
            try (Connection c = db.dataSource().getConnection()) {
                return Money.fromCents(sumUnclearedMarked(c, player));
            }
        });
    }

    // ─── Inteligencia policial: "seguir el dinero" ──────────────────────────

    /**
     * Historial de lotes marcados (lavados) del jugador, del mas reciente al mas antiguo, limitado a
     * {@code limit}. Lee core_marked_money en el executor de BD (fuera del hilo principal).
     */
    @Override
    public CompletableFuture<List<MarkedTrace>> markedHistory(UUID owner, int limit) {
        int cap = limit <= 0 ? 10 : Math.min(limit, 100);
        return supply(() -> {
            List<MarkedTrace> out = new ArrayList<>();
            try (Connection c = db.dataSource().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT code, amount_cents, channel, zona, created_at, cleared, cleared_at "
                                 + "FROM core_marked_money WHERE owner_uuid = ? "
                                 + "ORDER BY created_at DESC, code DESC")) {
                ps.setString(1, owner.toString());
                ps.setMaxRows(cap);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && out.size() < cap) {
                        String code = rs.getString(1);
                        BigDecimal amt = Money.fromCents(rs.getLong(2));
                        String channel = rs.getString(3);
                        String zona = rs.getString(4);
                        long createdAt = rs.getLong(5);
                        boolean cleared = rs.getInt(6) != 0;
                        long clearedAt = rs.getLong(7); // 0/NULL si sigue marcado
                        out.add(new MarkedTrace(code, amt, channel, zona, createdAt, cleared, clearedAt));
                    }
                }
            }
            return out;
        });
    }

    /**
     * Resumen del dinero rastreable del jugador en UNIDADES: {@code "marcado_sin_limpiar"} (lavado que aun
     * no paso por cajero) y {@code "ya_limpiado"} (lavado ya limpiado en cajero). Lectura async.
     */
    @Override
    public CompletableFuture<Map<String, BigDecimal>> markedSummary(UUID owner) {
        return supply(() -> {
            long sinLimpiar = 0L;
            long limpiado = 0L;
            try (Connection c = db.dataSource().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT cleared, COALESCE(SUM(amount_cents), 0) FROM core_marked_money "
                                 + "WHERE owner_uuid = ? GROUP BY cleared")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getInt(1) != 0) limpiado = rs.getLong(2);
                        else sinLimpiar = rs.getLong(2);
                    }
                }
            }
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            m.put("marcado_sin_limpiar", Money.fromCents(sinLimpiar));
            m.put("ya_limpiado", Money.fromCents(limpiado));
            return m;
        });
    }

    /**
     * Cajero: convierte el dinero LIMPIO marcado del jugador en BANCO "sin rastro" cobrando una comision
     * (sumidero) y marcando las filas como cleared=1. El monto procesado es min(marcado, LIMPIO actual).
     */
    public CompletableFuture<TransactionResult> cashier(UUID player, BigDecimal feeRatio) {
        return supply(() -> {
            if (feeRatio == null || feeRatio.signum() < 0 || feeRatio.compareTo(BigDecimal.ONE) >= 0) {
                return TransactionResult.fail("COMISION_INVALIDA");
            }
            try (Connection c = db.dataSource().getConnection()) {
                Account a = account(c, player);
                a.lock().lock();
                try {
                    long markedSum = sumUnclearedMarked(c, player);
                    if (markedSum <= 0) {
                        return TransactionResult.fail("SIN_MARCADO");
                    }
                    long limpio = a.get(MoneyType.LIMPIO);
                    long amt = Math.min(markedSum, limpio);
                    if (amt <= 0) {
                        return TransactionResult.fail("SIN_LIMPIO");
                    }
                    long fee = Money.toCents(Money.fromCents(amt).multiply(feeRatio));
                    if (fee < 0) fee = 0;
                    if (fee > amt) fee = amt;
                    long net = amt - fee;
                    long banco = a.get(MoneyType.BANCO);
                    a.set(MoneyType.LIMPIO, limpio - amt);
                    a.set(MoneyType.BANCO, banco + net);
                    try {
                        c.setAutoCommit(false);
                        balances.update(c, a);
                        long tx = txSeq.incrementAndGet();
                        audit.insert(c, tx, player.toString(), player.toString(), MoneyType.LIMPIO, amt, "CASHIER", "cajero fee=" + feeRatio);
                        clearMarked(c, player, amt, tx, System.currentTimeMillis());
                        c.commit();
                        return TransactionResult.ok(Money.fromCents(a.get(MoneyType.BANCO)), tx);
                    } catch (SQLException e) {
                        safeRollback(c);
                        a.set(MoneyType.LIMPIO, limpio);
                        a.set(MoneyType.BANCO, banco);
                        throw e;
                    } finally {
                        restoreAutoCommit(c);
                    }
                } finally {
                    a.lock().unlock();
                }
            }
        });
    }

    private long sumUnclearedMarked(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM core_marked_money WHERE owner_uuid = ? AND cleared = 0")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * Limpia el rastro SOLO del importe efectivamente procesado por el cajero ({@code amt}), no de TODO
     * el dinero marcado del jugador. Recorre las filas sin limpiar de la mas antigua a la mas nueva:
     * las que caben enteras se marcan cleared=1; la fila "frontera" se reduce a su remanente (sigue
     * marcada). Asi sum(marcado sin limpiar) baja EXACTAMENTE en {@code amt} y la trazabilidad policial
     * del dinero que NO paso por el cajero se conserva. (Antes se limpiaba todo aunque amt < marcado.)
     */
    private void clearMarked(Connection c, UUID owner, long amt, long clearedTx, long clearedAt) throws SQLException {
        if (amt <= 0) return;
        List<String> toClear = new ArrayList<>();
        String frontierCode = null;
        long frontierRemainder = 0L;
        long remaining = amt;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT code, amount_cents FROM core_marked_money WHERE owner_uuid = ? AND cleared = 0 "
                + "ORDER BY created_at ASC, code ASC")) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && remaining > 0) {
                    String code = rs.getString(1);
                    long rowAmt = rs.getLong(2);
                    if (rowAmt <= remaining) {
                        toClear.add(code);
                        remaining -= rowAmt;
                    } else {
                        frontierCode = code;
                        frontierRemainder = rowAmt - remaining; // porcion que sigue marcada
                        remaining = 0;
                    }
                }
            }
        }
        if (!toClear.isEmpty()) {
            // Sello policial: ademas de cleared=1 anotamos QUE tx del cajero lo limpio y CUANDO.
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE core_marked_money SET cleared = 1, cleared_tx = ?, cleared_at = ? WHERE code = ?")) {
                for (String code : toClear) {
                    up.setLong(1, clearedTx);
                    up.setLong(2, clearedAt);
                    up.setString(3, code);
                    up.addBatch();
                }
                up.executeBatch();
            }
        }
        if (frontierCode != null) {
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE core_marked_money SET amount_cents = ? WHERE code = ?")) {
                up.setLong(1, frontierRemainder);
                up.setString(2, frontierCode);
                up.executeUpdate();
            }
        }
    }

    // ─── INTERES DE AHORROS (saldo BANCO) ───────────────────────────────────

    /**
     * Interes (en centimos) que generaria un saldo de BANCO dado, con los topes anti-abuso. PURO (sin IO):
     * {@code interes = floor( min(banco, principalCap) * rate )}, acotado por {@code maxPayout}; 0 si el
     * banco esta por debajo de {@code minBalance}. Floor (RoundingMode.DOWN) para no redondear a favor del
     * jugador. El tope al PRINCIPAL es lo que hace el rendimiento LINEAL (no exponencial) y evita cifras
     * exorbitantes: por mucho banco que tengas, solo los primeros {@code principalCap} generan interes.
     */
    public static long interestCents(long bancoCents, BigDecimal rate, long principalCapCents,
                                     long maxPayoutCents, long minBalanceCents) {
        if (rate == null || rate.signum() <= 0) return 0L;
        if (bancoCents < minBalanceCents) return 0L;
        long principal = Math.min(bancoCents, Math.max(0L, principalCapCents));
        if (principal <= 0L) return 0L;
        BigDecimal raw = BigDecimal.valueOf(principal).multiply(rate).setScale(0, RoundingMode.DOWN);
        // Saturamos contra el techo (o Long.MAX si no hay) ANTES de pasar a long: asi un principal-cap o
        // una rate absurdos en config no provocan overflow (longValueExact lanzaria) — degradan a un tope.
        long ceiling = (maxPayoutCents > 0L) ? maxPayoutCents : Long.MAX_VALUE;
        long interest = raw.min(BigDecimal.valueOf(ceiling)).longValue();
        return Math.max(0L, interest);
    }

    /**
     * Paga el interes de los AHORROS (BANCO) al jugador si ya toca (pasaron &gt;= {@code intervalMillis}
     * desde el ultimo pago registrado en core_interest). La PRIMERA vez solo ARRANCA el reloj (no paga),
     * para que crear cuenta o depositar no dispare un pago instantaneo. Atomico: lockea la cuenta y re-lee
     * el timestamp DENTRO del lock (serializa por jugador, evita doble pago), luego persiste saldo + audit
     * + timestamp en una transaccion (revierte la memoria si la BD falla). Pensado para llamarse SOLO con
     * jugadores conectados (asi estar offline no acumula periodos).
     */
    public CompletableFuture<InterestResult> payInterestIfDue(UUID player, BigDecimal rate,
                                                              long principalCapCents, long maxPayoutCents,
                                                              long minBalanceCents, long intervalMillis) {
        return supply(() -> {
            final long now = System.currentTimeMillis();
            try (Connection c = db.dataSource().getConnection()) {
                Account a = account(c, player);
                a.lock().lock();
                try {
                    Long last = readInterestLastPaid(c, player);
                    if (last == null) {
                        insertInterestClock(c, player, now); // arranca el reloj, sin pagar
                        return InterestResult.clockStarted(now + intervalMillis);
                    }
                    if (now - last < intervalMillis) {
                        return InterestResult.notDue(last + intervalMillis);
                    }
                    long banco = a.get(MoneyType.BANCO);
                    long interest = interestCents(banco, rate, principalCapCents, maxPayoutCents, minBalanceCents);
                    if (interest <= 0L) {
                        // Toca, pero el banco no genera (por debajo del minimo). Avanzamos el reloj IGUAL
                        // (consume el intervalo): el interes premia MANTENER ahorros un intervalo entero, no
                        // tenerlos justo en el instante del check. Asi se cierra "deposita justo antes ->
                        // cobra el tope -> retira" en la cadencia del check en vez del intervalo real.
                        updateInterestPaid(c, player, now, 0L);
                        return InterestResult.belowMin();
                    }
                    long after = banco + interest;
                    a.set(MoneyType.BANCO, after);
                    try {
                        c.setAutoCommit(false);
                        balances.update(c, a);
                        long tx = txSeq.incrementAndGet();
                        audit.insert(c, tx, null, player.toString(), MoneyType.BANCO, interest, "INTEREST", "interes de ahorros");
                        updateInterestPaid(c, player, now, interest);
                        c.commit();
                        return InterestResult.paid(Money.fromCents(interest), Money.fromCents(after), now + intervalMillis);
                    } catch (SQLException e) {
                        safeRollback(c);
                        a.set(MoneyType.BANCO, banco);
                        throw e;
                    } finally {
                        restoreAutoCommit(c);
                    }
                } finally {
                    a.lock().unlock();
                }
            }
        });
    }

    private Long readInterestLastPaid(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_paid_at FROM core_interest WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void insertInterestClock(Connection c, UUID player, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_interest (uuid, last_paid_at, total_paid_cents) VALUES (?, ?, 0)")) {
            ps.setString(1, player.toString());
            ps.setLong(2, now);
            ps.executeUpdate();
        }
    }

    private void updateInterestPaid(Connection c, UUID player, long now, long interest) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE core_interest SET last_paid_at = ?, total_paid_cents = total_paid_cents + ? WHERE uuid = ?")) {
            ps.setLong(1, now);
            ps.setLong(2, interest);
            ps.setString(3, player.toString());
            ps.executeUpdate();
        }
    }
}
