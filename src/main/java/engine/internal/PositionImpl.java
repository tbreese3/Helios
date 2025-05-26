package engine.internal;

import engine.*;
import java.util.Objects;

/**
 * Immutable implementation of {@link Position} that keeps the *exact* FEN string supplied by the
 * caller. Parsing is performed once in the constructor to validate and extract the fields that the
 * {@code Position} interface exposes, but {@link #toFen()} simply returns the original, trimmed FEN
 * – preserving non‑canonical whitespace, digit‑grouping, or castling‑ordering if that is what the
 * caller provided.
 */
public final class PositionImpl implements Position {

  /* ────── constants ────── */
  private static final int CR_WK = 0x1; // K
  private static final int CR_WQ = 0x2; // Q
  private static final int CR_BK = 0x4; // k
  private static final int CR_BQ = 0x8; // q

  private static final char EMPTY = '.';

  /* ────── immutable state ────── */
  private final char[] board; // 0=a1 .. 63=h8 (little‑endian rank‑file)
  private final boolean whiteToMove;
  private final int castlingRights;
  private final int enPassantSquare; // 0‑63 or −1
  private final int halfmoveClock;
  private final int fullmoveNumber;

  private final String originalFen; // verbatim (trimmed) input
  private final String canonicalFen; // normalised – piece placement + rights ordering etc.

  /* ────── construction ────── */
  public PositionImpl(String fen) {
    Objects.requireNonNull(fen, "FEN must not be null");
    this.originalFen = fen.trim();

    String[] tokens = this.originalFen.split("\\s+");
    if (tokens.length != 6) {
      throw new IllegalArgumentException("FEN must have six fields – got " + tokens.length);
    }

    /* 1) board */
    this.board = parseBoard(tokens[0]);

    /* 2) active colour */
    switch (tokens[1]) {
      case "w" -> this.whiteToMove = true;
      case "b" -> this.whiteToMove = false;
      default -> throw new IllegalArgumentException("Invalid active colour: " + tokens[1]);
    }

    /* 3) castling rights */
    this.castlingRights = parseCastling(tokens[2]);

    /* 4) en‑passant */
    this.enPassantSquare = parseEnPassant(tokens[3]);

    /* 5) half‑move clock */
    try {
      this.halfmoveClock = Integer.parseUnsignedInt(tokens[4]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid half‑move clock: " + tokens[4]);
    }

    /* 6) full‑move number */
    try {
      this.fullmoveNumber = Integer.parseUnsignedInt(tokens[5]);
      if (fullmoveNumber < 1) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid full‑move number: " + tokens[5]);
    }

    /* build canonical Fen once so equals/hashCode are semantics‑based */
    this.canonicalFen = buildCanonicalFen();
  }

  /* ────── interface implementation ────── */
  @Override
  public boolean whiteToMove() {
    return whiteToMove;
  }

  @Override
  public int halfmoveClock() {
    return halfmoveClock;
  }

  @Override
  public int fullmoveNumber() {
    return fullmoveNumber;
  }

  @Override
  public int castlingRights() {
    return castlingRights;
  }

  @Override
  public int enPassantSquare() {
    return enPassantSquare;
  }

  /** Returns <em>exactly</em> the FEN string that was supplied to the constructor (trimmed). */
  @Override
  public String toFen() {
    return originalFen;
  }

  /* ────── equality & hashing based on canonical form ────── */
  @Override
  public int hashCode() {
    return canonicalFen.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Position)) return false;

    // When comparing two ImmutablePosition objects we already have a cached canonical form.
    if (o instanceof PositionImpl ip) {
      return this.canonicalFen.equals(ip.canonicalFen);
    }

    // Fallback: compare with the raw FEN of the other implementation (may be non‑canonical).
    return this.canonicalFen.equals(((Position) o).toFen().trim());
  }

  /* ────── utility ────── */
  @Override
  public String toString() {
    return originalFen;
  }

  /* ────── parsing helpers (unchanged) ────── */
  private static char[] parseBoard(String field) {
    char[] board = new char[64];
    for (int i = 0; i < 64; ++i) board[i] = EMPTY;

    int rank = 7, file = 0;
    for (int idx = 0; idx < field.length(); ++idx) {
      char c = field.charAt(idx);
      if (c == '/') {
        rank--;
        file = 0;
        continue;
      }
      if (Character.isDigit(c)) {
        file += c - '0';
      } else {
        if (file >= 8) throw new IllegalArgumentException("Too many files in rank");
        board[rank * 8 + file] = c;
        file++;
      }
    }
    if (rank != 0 || file != 8) throw new IllegalArgumentException("Incomplete board in FEN");
    return board;
  }

  private static int parseCastling(String field) {
    if (field.equals("-")) return 0;
    int rights = 0;
    for (char c : field.toCharArray()) {
      switch (c) {
        case 'K' -> rights |= CR_WK;
        case 'Q' -> rights |= CR_WQ;
        case 'k' -> rights |= CR_BK;
        case 'q' -> rights |= CR_BQ;
        default -> throw new IllegalArgumentException("Invalid castling char: " + c);
      }
    }
    return rights;
  }

  private static int parseEnPassant(String field) {
    if (field.equals("-")) return -1;
    if (field.length() != 2) throw new IllegalArgumentException("Bad EP square: " + field);
    int file = field.charAt(0) - 'a';
    int rank = field.charAt(1) - '1';
    if (file < 0 || file > 7 || rank < 0 || rank > 7) {
      throw new IllegalArgumentException("Bad EP square: " + field);
    }
    return rank * 8 + file;
  }

  /* builds canonical FEN with normalised ordering and minimal digits */
  private String buildCanonicalFen() {
    StringBuilder sb = new StringBuilder(96);
    for (int rank = 7; rank >= 0; --rank) {
      int empty = 0;
      for (int file = 0; file < 8; ++file) {
        char p = board[rank * 8 + file];
        if (p == EMPTY) empty++;
        else {
          if (empty != 0) {
            sb.append(empty);
            empty = 0;
          }
          sb.append(p);
        }
      }
      if (empty != 0) sb.append(empty);
      if (rank != 0) sb.append('/');
    }
    sb.append(' ').append(whiteToMove ? 'w' : 'b').append(' ');
    if (castlingRights == 0) sb.append('-');
    else {
      if ((castlingRights & CR_WK) != 0) sb.append('K');
      if ((castlingRights & CR_WQ) != 0) sb.append('Q');
      if ((castlingRights & CR_BK) != 0) sb.append('k');
      if ((castlingRights & CR_BQ) != 0) sb.append('q');
    }
    sb.append(' ');
    if (enPassantSquare == -1) sb.append('-');
    else {
      int file = enPassantSquare & 7;
      int rank = enPassantSquare >>> 3;
      sb.append((char) ('a' + file)).append((char) ('1' + rank));
    }
    sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber);
    return sb.toString();
  }
}
