package engine.internal.search.internal;

import engine.Move;
import engine.Piece;
import engine.internal.PieceImpl;
import engine.internal.search.PackedMoveFactory;

/**
 * Concrete implementation of {@link PackedMoveFactory} that follows the canonical 32‑bit layout
 * and now also <strong>encodes the captured piece</strong> (type + colour) into bits 23‑16.
 * <p>
 * Encoding of the captured‑piece byte:
 * <pre>
 *  bit3      bit2‑0
 *  ┌─┬───────────┐
 *  │C│  typeId   │
 *  └─┴───────────┘  (C = 0 white, 1 black)
 * </pre>
 * where <code>typeId</code> is 1=pawn, 2=knight, 3=bishop, 4=rook, 5=queen, 6=king.
 */
public final class IntPackedMoveFactory implements PackedMoveFactory {

    // -------------------------------------------------------------------------------------------------------------
    //   Conversion helpers: Piece ↔ byte
    // -------------------------------------------------------------------------------------------------------------

    private static int pieceToByte(Piece p) {
        if (p == null) return 0;
        int typeId = switch (p.type()) {
            case PAWN   -> 1;
            case KNIGHT -> 2;
            case BISHOP -> 3;
            case ROOK   -> 4;
            case QUEEN  -> 5;
            case KING   -> 6;
        };
        int colorBit = p.color() == Piece.Color.BLACK ? 1 : 0;
        return (colorBit << 3) | typeId;   // fits in low 4 bits, but we store in full byte
    }

    private static Piece byteToPiece(int b) {
        if (b == 0) return null; // no capture
        int typeId   =  b & 0b111;
        int colorBit = (b >>> 3) & 1;
        Piece.Type type = switch (typeId) {
            case 1 -> Piece.Type.PAWN;
            case 2 -> Piece.Type.KNIGHT;
            case 3 -> Piece.Type.BISHOP;
            case 4 -> Piece.Type.ROOK;
            case 5 -> Piece.Type.QUEEN;
            case 6 -> Piece.Type.KING;
            default -> throw new IllegalArgumentException("invalid captured byte " + b);
        };
        Piece.Color color = colorBit == 1 ? Piece.Color.BLACK : Piece.Color.WHITE;
        return new PieceImpl(type, color);
    }

    // -------------------------------------------------------------------------------------------------------------
    //   High‑level conversions required by the factory interface
    // -------------------------------------------------------------------------------------------------------------

    @Override
    public int toPacked(Move m) {
        int flags = 0;
        if (m.isCapture())   flags |= 1;
        if (m.isPromotion()) flags |= 2;
        if (m.isCastle())    flags |= 4;
        if (m.isEnPassant()) flags |= 8;

        int promoCode = switch (m.promotion()) {
            case 'n' -> 1; case 'b' -> 2; case 'r' -> 3; case 'q' -> 4; default -> 0;
        };

        int capturedByte = pieceToByte(m.capturedPiece());

        return (promoCode    << 24)
                | (capturedByte << 16)
                | (flags        << 12)
                | (m.to()       << 6)
                |  m.from();
    }

    @Override
    public Move toUnpacked(final int bits) {
        return new Move() {
            private int  fromSq()     { return  bits        & 0x3F; }
            private int  toSq()       { return (bits >>> 6) & 0x3F; }
            private int  flagNibble() { return (bits >>> 12) & 0xF; }
            private int  capByte()    { return (bits >>> 16) & 0xFF; }
            private int  promoByte()  { return (bits >>> 24) & 0xFF; }

            @Override public int from()              { return fromSq(); }
            @Override public int to()                { return toSq(); }
            @Override public boolean isCapture()     { return (flagNibble() & 1) != 0; }
            @Override public boolean isPromotion()   { return (flagNibble() & 2) != 0; }
            @Override public boolean isCastle()      { return (flagNibble() & 4) != 0; }
            @Override public boolean isEnPassant()   { return (flagNibble() & 8) != 0; }
            @Override public boolean isDoublePawnPush() {
                if (isCapture() || isPromotion() || isCastle()) return false;
                int diff = to() - from();
                return diff == 16 || diff == -16;
            }
            @Override public boolean isNull()        { return bits == 0; }
            @Override public char promotion() {
                return switch (promoByte()) {
                    case 1 -> 'n'; case 2 -> 'b'; case 3 -> 'r'; case 4 -> 'q'; default -> '\0';
                };
            }
            @Override public Piece capturedPiece()   { return byteToPiece(capByte()); }
            @Override public String toUci() {
                if (isNull()) return "0000";
                return square(from()) + square(to()) + (isPromotion() ? String.valueOf(promotion()) : "");
            }
            @Override public String toString() { return toUci(); }

            private String square(int sq) {
                return "" + (char)('a' + (sq & 7)) + (char)('1' + (sq >>> 3));
            }
        };
    }
}
