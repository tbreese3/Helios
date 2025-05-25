package engine.internal.search;

import engine.Move;

/**
 * Minimal factory interface that converts between the public {@link Move} façade and a
 * compact, engine‑specific <code>int</code> representation.
 * <p>
 * The default accessors below assume the <strong>canonical 32‑bit layout</strong> used elsewhere in
 * the engine:
 *
 * <pre>
 * 31           24 23           16 15  12 11      6 5       0
 * ┌────────────┬──────────────┬──────┬──────────┬──────────┐
 * │ promotion  │ captured     │flag  │  toSq    │  fromSq  │
 * └────────────┴──────────────┴──────┴──────────┴──────────┘
 *        8            8          4        6         6  bits
 * </pre>
 *
 * If you use a different bit‑packing scheme, simply override the default methods in your concrete
 * implementation class.
 */
public interface PackedMoveFactory {

    /** Convert a high‑level {@link Move} object into its packed <code>int</code> form. */
    int toPacked(Move unpackedMove);

    /** Reconstruct an immutable {@link Move} view from the packed <code>int</code> value. */
    Move toUnpacked(int packedMove);

    // -------------------------------------------------------------------------------------------------------------
    // Default field accessors (32‑bit canonical format).
    // -------------------------------------------------------------------------------------------------------------

    /** 0–63 origin square. */
    default int from(int packed)       { return  packed        & 0x3F; }

    /** 0–63 destination square. */
    default int to(int packed)         { return (packed >>> 6) & 0x3F; }

    /** 4‑bit flag nibble (capture, promotion, castle, en‑passant). */
    default int flags(int packed)      { return (packed >>> 12) & 0xF; }

    /** 8‑bit captured‑piece code (0 if none). */
    default int captured(int packed)   { return (packed >>> 16) & 0xFF; }

    /** 8‑bit promotion‑piece code (0 if not a promotion). */
    default int promotion(int packed)  { return (packed >>> 24) & 0xFF; }
}

