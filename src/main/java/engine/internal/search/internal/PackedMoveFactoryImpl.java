/*  ──────────────────────────────────────────────────────────────────────────────
 *  engine/internal/search/internal/IntPackedMoveFactory.java
 *  Entire concrete implementation – no captured-piece byte
 *  ──────────────────────────────────────────────────────────────────────────────
 */
package engine.internal.search.internal;

import engine.Move;
import engine.internal.search.PackedMoveFactory;

/** 16-bit move stored in an {@code int}. */
public final class PackedMoveFactoryImpl implements PackedMoveFactory {
  /* ── encoding helpers ────────────────────────────────────────────────── */

  private static int promoCharToIndex(char c) {
    return switch (c) {
      case 'n' -> 0;
      case 'b' -> 1;
      case 'r' -> 2;
      case 'q' -> 3;
      default -> 0; // also covers '\0'
    };
  }

  private static char indexToPromoChar(int idx) {
    return switch (idx) {
      case 0 -> 'n';
      case 1 -> 'b';
      case 2 -> 'r';
      case 3 -> 'q';
      default -> '\0';
    };
  }

  /* ── high-level conversions ─────────────────────────────────────────── */

  @Override
  public int toPacked(Move m) {

    // core squares -----------------------------------------------------
    int packed = (m.from() << 6) | m.to();

    // move type --------------------------------------------------------
    int type = m.isCastle() ? 3 : m.isEnPassant() ? 2 : m.isPromotion() ? 1 : 0;
    packed |= type << 14;

    // promotion piece --------------------------------------------------
    if (m.isPromotion()) packed |= promoCharToIndex(m.promotion()) << 12;

    // upper 16 bits remain zero
    return packed;
  }

  @Override
  public Move toUnpacked(final int bits) {

    return new Move() {

      /* ── cached bit slices ─────────────────────────────────────── */
      private int fromInternal() {
        return (bits >>> 6) & 0x3F;
      }

      private int toInternal() {
        return bits & 0x3F;
      }

      private int typeInternal() {
        return (bits >>> 14) & 0x3;
      }

      private int promoIdxInternal() {
        return (bits >>> 12) & 0x3;
      }

      /* ── Move interface ───────────────────────────────────────── */

      @Override
      public int from() {
        return fromInternal();
      }

      @Override
      public int to() {
        return toInternal();
      }

      @Override
      public boolean isPromotion() {
        return typeInternal() == 1;
      }

      @Override
      public boolean isEnPassant() {
        return typeInternal() == 2;
      }

      @Override
      public boolean isCastle() {
        return typeInternal() == 3;
      }

      // Capture is not encoded; only EP is guaranteed to be a capture
      @Override
      public boolean isCapture() {
        return isEnPassant();
      }

      @Override
      public boolean isNull() {
        return bits == 0;
      }

      @Override
      public char promotion() {
        return isPromotion() ? indexToPromoChar(promoIdxInternal()) : '\0';
      }

      @Override
      public String toUci() {
        if (isNull()) return "0000";
        String uci = square(from()) + square(to());
        return isPromotion() ? uci + promotion() : uci;
      }

      @Override
      public String toString() {
        return toUci();
      }

      /* ── helper ──────────────────────────────────────────────── */
      private String square(int sq) {
        return "" + (char) ('a' + (sq & 7)) + (char) ('1' + (sq >>> 3));
      }
    };
  }
}
