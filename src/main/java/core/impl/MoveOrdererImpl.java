package core.impl;

import core.contracts.MoveOrderer;
import core.contracts.PositionFactory;

import static core.contracts.PositionFactory.META;

/**
  * A sophisticated move orderer that utilizes Static Exchange Evaluation (SEE)
  * for tactical moves and standard heuristics (Killers, History) for quiet moves.
  */
public final class MoveOrdererImpl implements MoveOrderer {

// --- Score Constants ---
    // We define distinct ranges to ensure strict ordering hierarchy:
    // TT Move > Good Tactical (SEE >= 0) > Killers > History > Bad Tactical (SEE < 0)

    private static final int SCORE_TT_MOVE = 2_000_000;

    // Good Tactical (SEE >= 0). SEE scores are in centipawns.
    // We add a large base to prioritize them. Max practical SEE is around +2000.
    private static final int SCORE_GOOD_TACTICAL_BASE = 1_500_000;

    // Killer moves
    private static final int SCORE_KILLER_1 = 1_000_000;
    private static final int SCORE_KILLER_2 = 990_000;

    // History scores for other quiet moves (must be below killers).
    private static final int SCORE_HISTORY_MAX = 980_000;

    // Bad Tactical (SEE < 0). Centered around a lower base.
    private static final int SCORE_BAD_TACTICAL_BASE = 500_000;


// --- Piece Values for SEE ---
        private static final int[] PIECE_VALUES = {100, 320, 330, 500, 900, 10000}; // P,N,B,R,Q,K
    // MVV_LVA_SCORES and its static initializer are removed as SEE replaces it.

// --- Scratch Buffers ---
        private final int[] moveScores = new int[256]; // Assumes max 256 moves
private final int[][] history;

    /* MVV_LVA static block removed */

public MoveOrdererImpl(int[][] history) {
this.history = history;
}

@Override
public void orderMoves(long[] bb, int[] moves, int count, int ttMove, int[] killers) {
boolean whiteToMove = PositionFactory.whiteToMove(bb[META]);

// 1. Score every move
for (int i = 0; i < count; i++) {
int move = moves[i];
if (move == ttMove) {
moveScores[i] = SCORE_TT_MOVE;
continue;
}

int moveType = (move >>> 14) & 0x3;
// int moverType = ((move >>> 16) & 0xF) % 6; // Not needed for SEE scoring
int toSquare = move & 0x3F;
int fromSquare = (move >>> 6) & 0x3F;

            // Determine if the move is tactical (Capture, EP, or Promotion)
            int capturedPieceType = getCapturedPieceType(bb, toSquare, whiteToMove);
            boolean isCapture = (capturedPieceType != -1) || (moveType == 2); // Includes En Passant
            boolean isPromotion = (moveType == 1);

            if (isCapture || isPromotion) {
                // Use SEE for all tactical moves
                int seeScore = see(bb, move);

                if (seeScore >= 0) {
                    // Winning or equal exchange - prioritized by the actual gain.
                    moveScores[i] = SCORE_GOOD_TACTICAL_BASE + seeScore;
                } else {
                    // Losing exchange (searched after quiet moves)
                    // Add the negative score (e.g., -100 is better than -500)
                    moveScores[i] = SCORE_BAD_TACTICAL_BASE + seeScore;
                }

                // Optional: Boost Queen promotions slightly as they are often the best tactical move
                if (isPromotion) {
                    int promoType = (move >>> 12) & 0x3;
                    if (promoType == 3) { // Queen (based on MoveGeneratorImpl promo bits)
                        moveScores[i] += 50;
                    }
                }

            } else { // Quiet move (including castling)
                int score = 0;

                // Check Killers first
                if (killers != null) {
                    if (move == killers[0]) {
                        score = SCORE_KILLER_1;
                    } else if (move == killers[1]) {
                        score = SCORE_KILLER_2;
                    }
                }

                // If not a killer, use History Heuristic
                if (score == 0) {
                    // Scale down history. The divisor depends on how large history values grow (depth^2).
                    // 128 is a reasonable starting point for scaling.
                    int historyScore = history[fromSquare][toSquare] / 128;
                    // Cap the score so it doesn't exceed killers.
                    score = Math.min(SCORE_HISTORY_MAX, historyScore);
                }
                moveScores[i] = score;
            }
}

// 2. Sort the move list in-place using insertion sort
for (int i = 1; i < count; i++) {
int currentMove = moves[i];
int currentScore = moveScores[i];
int j = i - 1;

// Shift elements to the right until the correct spot for the current move is found
while (j >= 0 && moveScores[j] < currentScore) {
moves[j + 1] = moves[j];
moveScores[j + 1] = moveScores[j];
j--;
}
moves[j + 1] = currentMove;
moveScores[j + 1] = currentScore;
}
}

@Override
public int seePrune(long[] bb, int[] moves, int count) {
int goodMovesCount = 0;
for (int i = 0; i < count; i++) {
            // We check >= 0 because see() handles promotions internally now.
if (see(bb, moves[i]) >= 0) {
moves[goodMovesCount++] = moves[i];
}
}
return goodMovesCount;
}

/**
  * Calculates the Static Exchange Evaluation for a move.
 * Correctly handles promotions and en passant.
  */
        @Override
public int see(long[] bb, int move) {
int from = (move >>> 6) & 0x3F;
int to = move & 0x3F;
int moverType = getMoverPieceType(move); // Initial mover type (e.g., Pawn)
boolean initialStm = PositionFactory.whiteToMove(bb[META]);

        final int moveType = (move >>> 14) & 0x3;
        final boolean isEp = (moveType == 2);
        final boolean isPromo = (moveType == 1);

int victimType = getCapturedPieceType(bb, to, initialStm);

        // If not a capture, EP, or promotion, SEE is 0.
        if (victimType == -1 && !isEp && !isPromo) {
            return 0;
        }

        if (isEp) victimType = 0; // EP always captures a pawn

        // Calculate initial gain from the capture itself.
        int initialGain = (victimType != -1) ? PIECE_VALUES[victimType] : 0;

        // Handle Promotions: Adjust gain and the type of the piece now on the 'to' square.
        if (isPromo) {
            int promoType = (move >>> 12) & 0x3; // N=0, B=1, R=2, Q=3 (based on MoveGeneratorImpl)
            int promoPieceType = promoType + 1; // N=1 ... Q=4

            // Gain from promotion itself (Value of Promo - Value of Pawn)
            initialGain += (PIECE_VALUES[promoPieceType] - PIECE_VALUES[0]);

            // The piece participating in subsequent exchanges is the promoted piece
            moverType = promoPieceType;
        }

int[] gain = new int[32];
int d = 0;
gain[d] = initialGain;

long occ = (bb[PositionFactory.WP] | bb[PositionFactory.WN] | bb[PositionFactory.WB] | bb[PositionFactory.WR] | bb[PositionFactory.WQ] | bb[PositionFactory.WK] |
bb[PositionFactory.BP] | bb[PositionFactory.BN] | bb[PositionFactory.BB] | bb[PositionFactory.BR] | bb[PositionFactory.BQ] | bb[PositionFactory.BK]);

occ ^= (1L << from); // Remove the first attacker

        // FIX: Handle EP Occupancy correctly by removing the captured pawn.
        if (isEp) {
            int epPawnSq = initialStm ? to - 8 : to + 8;
            occ ^= (1L << epPawnSq);
        }

boolean stm = !initialStm;

while (true) {
d++;
            // The cost to recapture is the value of the piece we just moved (or promoted to).
gain[d] = PIECE_VALUES[moverType] - gain[d - 1];

            // Optimization: SEE Pruning (a.k.a. "May I recapture?")
            // If the current side can choose not to recapture and end up better off, they will.
            // If the max possible gain from this point is negative, they stop the exchange.
            if (Math.max(-gain[d-1], gain[d]) < 0) break;


long[] outAttackerBit = new long[1];
moverType = getLeastValuableAttacker(bb, to, stm, occ, outAttackerBit);

if (moverType == -1) break;

occ ^= outAttackerBit[0]; // Remove the next attacker
stm = !stm;
}

// Negamax unrolling of the capture sequence
while (--d > 0) {
gain[d - 1] = -Math.max(-gain[d - 1], gain[d]);
}
return gain[0];
}

private int getMoverPieceType(int move) {
return ((move >>> 16) & 0xF) % 6;
}

/**
  * Finds the piece type of the least valuable attacker to a square.
  * Optimized by combining Queen checks with Bishop/Rook checks.
  */
        private int getLeastValuableAttacker(long[] bb, int to, boolean stm, long occ, long[] outAttackerBit) {
long toBB = 1L << to;
long attackers;
        // Constants from MoveGeneratorImpl (same package)
        final long FILE_A = MoveGeneratorImpl.FILE_A;
        final long FILE_H = MoveGeneratorImpl.FILE_H;

// Pawns
if (stm) { // White attackers
            // Note: FILE_H is 0x8080... FILE_A is 0x0101...
attackers = (((toBB & ~FILE_H) >>> 7) | ((toBB & ~FILE_A) >>> 9)) & bb[PositionFactory.WP] & occ;
} else { // Black attackers
attackers = (((toBB & ~FILE_A) << 7) | ((toBB & ~FILE_H) << 9)) & bb[PositionFactory.BP] & occ;
}
if (attackers != 0) {
outAttackerBit[0] = attackers & -attackers;
return 0;
}

// Knights
attackers = PreCompMoveGenTables.KNIGHT_ATK[to] & bb[stm ? PositionFactory.WN : PositionFactory.BN] & occ;
if (attackers != 0) {
outAttackerBit[0] = attackers & -attackers;
return 1;
}

        // Optimization: Calculate slider attacks once and reuse
        long bishopAttacks = MoveGeneratorImpl.bishopAtt(occ, to);
// Bishops
attackers = bishopAttacks & bb[stm ? PositionFactory.WB : PositionFactory.BB] & occ;
if (attackers != 0) {
outAttackerBit[0] = attackers & -attackers;
return 2;
}

        long rookAttacks = MoveGeneratorImpl.rookAtt(occ, to);
// Rooks
attackers = rookAttacks & bb[stm ? PositionFactory.WR : PositionFactory.BR] & occ;
if (attackers != 0) {
outAttackerBit[0] = attackers & -attackers;
return 3;
}

// Queens (combine pre-calculated attacks)
attackers = (bishopAttacks | rookAttacks) & bb[stm ? PositionFactory.WQ : PositionFactory.BQ] & occ;
if (attackers != 0) {
outAttackerBit[0] = attackers & -attackers;
return 4;
}

// King
attackers = PreCompMoveGenTables.KING_ATK[to] & bb[stm ? PositionFactory.WK : PositionFactory.BK] & occ;
if (attackers != 0) {
outAttackerBit[0] = attackers & -attackers;
return 5;
}

outAttackerBit[0] = 0L;
return -1;
}

/**
  * Determines the type of piece on a given square.
  * @return The piece type (0-5 for P,N,B,R,Q,K), or -1 if the square is empty.
  */
        private int getCapturedPieceType(long[] bb, int toSquare, boolean whiteToMove) {
long toBit = 1L << toSquare;
int start = whiteToMove ? PositionFactory.BP : PositionFactory.WP;
int end = whiteToMove ? PositionFactory.BK : PositionFactory.WK;

for (int pieceType = start; pieceType <= end; pieceType++) {
if ((bb[pieceType] & toBit) != 0) {
return pieceType % 6; // Return 0-5
}
}
return -1; // Not a capture
}
}