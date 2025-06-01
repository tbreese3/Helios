package engine.internal.search.internal;

public final class PreCompMoveGenTables {
  private PreCompMoveGenTables() {}

  public static final long[] ROOKMASK_PEXT = {
          0x000101010101017EL, 0x000202020202027CL, 0x000404040404047AL, 0x0008080808080876L,
          0x001010101010106EL, 0x002020202020205EL, 0x004040404040403EL, 0x008080808080807EL,
          0x0001010101017E00L, 0x0002020202027C00L, 0x0004040404047A00L, 0x0008080808087600L,
          0x0010101010106E00L, 0x0020202020205E00L, 0x0040404040403E00L, 0x0080808080807E00L,
          0x00010101017E0100L, 0x00020202027C0200L, 0x00040404047A0400L, 0x0008080808760800L,
          0x00101010106E1000L, 0x00202020205E2000L, 0x00404040403E4000L, 0x00808080807E8000L,
          0x000101017E010100L, 0x000202027C020200L, 0x000404047A040400L, 0x0008080876080800L,
          0x001010106E101000L, 0x002020205E202000L, 0x004040403E404000L, 0x008080807E808000L,
          0x0001017E01010100L, 0x0002027C02020200L, 0x0004047A04040400L, 0x0008087608080800L,
          0x0010106E10101000L, 0x0020205E20202000L, 0x0040403E40404000L, 0x0080807E80808000L,
          0x00017E0101010100L, 0x00027C0202020200L, 0x00047A0404040400L, 0x0008760808080800L,
          0x00106E1010101000L, 0x00205E2020202000L, 0x00403E4040404000L, 0x00807E8080808000L,
          0x007E010101010100L, 0x007C020202020200L, 0x007A040404040400L, 0x0076080808080800L,
          0x006E101010101000L, 0x005E202020202000L, 0x003E404040404000L, 0x007E808080808000L,
          0x7E01010101010100L, 0x7C02020202020200L, 0x7A04040404040400L, 0x7608080808080800L,
          0x6E10101010101000L, 0x5E20202020202000L, 0x3E40404040404000L, 0x7E80808080808000L,
  };

  public static final long[] BISHOPMASK_PEXT = {
          0x0040201008040200L, 0x0000402010080400L, 0x0000004020100A00L, 0x0000000040221400L,
          0x0000000002442800L, 0x0000000204085000L, 0x0000020408102000L, 0x0002040810204000L,
          0x0020100804020000L, 0x0040201008040000L, 0x00004020100A0000L, 0x0000004022140000L,
          0x0000000244280000L, 0x0000020408500000L, 0x0002040810200000L, 0x0004081020400000L,
          0x0010080402000200L, 0x0020100804000400L, 0x004020100A000A00L, 0x0000402214001400L,
          0x0000024428002800L, 0x0002040850005000L, 0x0004081020002000L, 0x0008102040004000L,
          0x0008040200020400L, 0x0010080400040800L, 0x0020100A000A1000L, 0x0040221400142200L,
          0x0002442800284400L, 0x0004085000500800L, 0x0008102000201000L, 0x0010204000402000L,
          0x0004020002040800L, 0x0008040004081000L, 0x00100A000A102000L, 0x0022140014224000L,
          0x0044280028440200L, 0x0008500050080400L, 0x0010200020100800L, 0x0020400040201000L,
          0x0002000204081000L, 0x0004000408102000L, 0x000A000A10204000L, 0x0014001422400000L,
          0x0028002844020000L, 0x0050005008040200L, 0x0020002010080400L, 0x0040004020100800L,
          0x0000020408102000L, 0x0000040810204000L, 0x00000A1020400000L, 0x0000142240000000L,
          0x0000284402000000L, 0x0000500804020000L, 0x0000201008040200L, 0x0000402010080400L,
          0x0002040810204000L, 0x0004081020400000L, 0x000A102040000000L, 0x0014224000000000L,
          0x0028440200000000L, 0x0050080402000000L, 0x0020100804020000L, 0x0040201008040200L,
  };

  public static final int[] ROOKOFFSET_PEXT = {
          0, 4160, 6240, 8320,
          10400, 12480, 14560, 16640,
          20800, 22880, 23936, 24992,
          26048, 27104, 28160, 29216,
          31296, 33376, 34432, 35584,
          36736, 37888, 39040, 40096,
          42176, 44256, 45312, 46464,
          48000, 49536, 50688, 51744,
          53824, 55904, 56960, 58112,
          59648, 61184, 62336, 63392,
          65472, 67552, 68608, 69760,
          70912, 72064, 73216, 74272,
          76352, 78432, 79488, 80544,
          81600, 82656, 83712, 84768,
          86848, 91008, 93088, 95168,
          97248, 99328, 101408, 103488,
  };

  public static final int[] BISHOPOFFSET_PEXT = {
          4096, 6208, 8288, 10368,
          12448, 14528, 16608, 20736,
          22848, 23904, 24960, 26016,
          27072, 28128, 29184, 31264,
          33344, 34400, 35456, 36608,
          37760, 38912, 40064, 42144,
          44224, 45280, 46336, 47488,
          49024, 50560, 51712, 53792,
          55872, 56928, 57984, 59136,
          60672, 62208, 63360, 65440,
          67520, 68576, 69632, 70784,
          71936, 73088, 74240, 76320,
          78400, 79456, 80512, 81568,
          82624, 83680, 84736, 86816,
          90944, 93056, 95136, 97216,
          99296, 101376, 103456, 107584,
  };

  /** flattened triples: offset, mask, hash */
  public static final long[] B_MAGICS = {
          66157L,
          0xFFBFDFEFF7FBFDFFL,
          1187473109101317119L,
          71730L,
          0xFFFFBFDFEFF7FBFFL,
          9223336714375004157L,
          37781L,
          0xFFFFFFBFDFEFF5FFL,
          288441550701068800L,
          21015L,
          0xFFFFFFFFBFDDEBFFL,
          1170795303134035968L,
          47590L,
          0xFFFFFFFFFDBBD7FFL,
          0xC03FE00100000000L,
          835L,
          0xFFFFFFFDFBF7AFFFL,
          2648129775020802048L,
          23592L,
          0xFFFFFDFBF7EFDFFFL,
          578730278520913668L,
          30599L,
          0xFFFDFBF7EFDFBFFFL,
          1155182238468407424L,
          68776L,
          0xFFDFEFF7FBFDFFFFL,
          0xFFA2FEFFBFEFB7FFL,
          19959L,
          0xFFBFDFEFF7FBFFFFL,
          593981333727348737L,
          21783L,
          0xFFFFBFDFEFF5FFFFL,
          288653413114708096L,
          64836L,
          0xFFFFFFBFDDEBFFFFL,
          306245323880337408L,
          23417L,
          0xFFFFFFFDBBD7FFFFL,
          2310347158529769472L,
          66724L,
          0xFFFFFDFBF7AFFFFFL,
          1187261314343337984L,
          74542L,
          0xFFFDFBF7EFDFFFFFL,
          9188469001234153344L,
          67266L,
          0xFFFBF7EFDFBFFFFFL,
          578171627018125376L,
          26575L,
          0xFFEFF7FBFDFFFDFFL,
          9222949822267379705L,
          67543L,
          0xFFDFEFF7FBFFFBFFL,
          9223020191524333565L,
          24409L,
          0xFFBFDFEFF5FFF5FFL,
          2306265224900983809L,
          30779L,
          0xFFFFBFDDEBFFEBFFL,
          4647151869788945290L,
          17384L,
          0xFFFFFDBBD7FFD7FFL,
          2314815028390789136L,
          18778L,
          0xFFFDFBF7AFFFAFFFL,
          0xFFDFEFFFDE39FFEFL,
          65109L,
          0xFFFBF7EFDFFFDFFFL,
          9223363241302802431L,
          20184L,
          0xFFF7EFDFBFFFBFFFL,
          9221115838862475263L,
          38240L,
          0xFFF7FBFDFFFDFBFFL,
          1090988818544L,
          16459L,
          0xFFEFF7FBFFFBF7FFL,
          9223230887045427193L,
          17432L,
          0xFFDFEFF5FFF5EFFFL,
          9223090009958119421L,
          81040L,
          0xFFBFDDEBFFEBDDFFL,
          4570867472252272639L,
          84946L,
          0xFFFDBBD7FFD7BBFFL,
          292734589976199165L,
          18276L,
          0xFFFBF7AFFFAFF7FFL,
          2305878434105819152L,
          8512L,
          0xFFF7EFDFFFDFEFFFL,
          612472197655543809L,
          78544L,
          0xFFEFDFBFFFBFDFFFL,
          2265045493362695L,
          19974L,
          0xFFFBFDFFFDFBF7FFL,
          553992241216L,
          23850L,
          0xFFF7FBFFFBF7EFFFL,
          276996120608L,
          11056L,
          0xFFEFF5FFF5EFDFFFL,
          27023813833162784L,
          68019L,
          0xFFDDEBFFEBDDBFFFL,
          0xD003FEFE04404080L,
          85965L,
          0xFFBBD7FFD7BBFDFFL,
          1152957239137415242L,
          80524L,
          0xFFF7AFFFAFF7FBFFL,
          9205355920559695872L,
          38221L,
          0xFFEFDFFFDFEFF7FFL,
          1188932777689485200L,
          64647L,
          0xFFDFBFFFBFDFEFFFL,
          9191846633066727416L,
          61320L,
          0xFFFDFFFDFBF7EFFFL,
          279189160064L,
          67281L,
          0xFFFBFFFBF7EFDFFFL,
          139594580032L,
          79076L,
          0xFFF5FFF5EFDFBFFFL,
          279198499008L,
          17115L,
          0xFFEBFFEBDDBFFFFFL,
          4503601791385728L,
          50718L,
          0xFFD7FFD7BBFDFFFFL,
          1152921764453941296L,
          24659L,
          0xFFAFFFAFF7FBFDFFL,
          2307531983360360472L,
          38291L,
          0xFFDFFFDFEFF7FBFFL,
          0xFFFFFFBFEFF80FDCL,
          30605L,
          0xFFBFFFBFDFEFF7FFL,
          68988172306L,
          37759L,
          0xFFFFFDFBF7EFDFFFL,
          576460886529573376L,
          4639L,
          0xFFFFFBF7EFDFBFFFL,
          594475220056658440L,
          21759L,
          0xFFFFF5EFDFBFFFFFL,
          576460752563503233L,
          67799L,
          0xFFFFEBDDBFFFFFFFL,
          1125900041076608L,
          22841L,
          0xFFFFD7BBFDFFFFFFL,
          576460756600479808L,
          66689L,
          0xFFFFAFF7FBFDFFFFL,
          603338863802321408L,
          62548L,
          0xFFFFDFEFF7FBFDFFL,
          0xFFFFFEFFBFEFF81DL,
          66597L,
          0xFFFFBFDFEFF7FBFFL,
          0xFFBFFFEFEFDFF70FL,
          86749L,
          0xFFFDFBF7EFDFBFFFL,
          1152921573842288770L,
          69558L,
          0xFFFBF7EFDFBFFFFFL,
          9203950263191257135L,
          61589L,
          0xFFF5EFDFBFFFFFFFL,
          9188469139741638527L,
          62533L,
          0xFFEBDDBFFFFFFFFFL,
          0xFFF1FFFFFFF7FFC1L,
          64387L,
          0xFFD7BBFDFFFFFFFFL,
          610242147571982367L,
          26581L,
          0xFFAFF7FBFDFFFFFFL,
          26177172852973578L,
          76355L,
          0xFFDFEFF7FBFDFFFFL,
          594615890450845658L,
          11140L,
          0xFFBFDFEFF7FBFDFFL,
          1152922330840178696L
  };

  /** flattened triples: offset, mask, hash */
  public static final long[] R_MAGICS = {
          10890L,
          0xFFFEFEFEFEFEFE81L,
          0x80280013FF84FFFFL,
          56054L,
          0xFFFDFDFDFDFDFD83L,
          6916402019615277055L,
          67495L,
          0xFFFBFBFBFBFBFB85L,
          0xFFEFFAFFEFFDFFFFL,
          72797L,
          0xFFF7F7F7F7F7F789L,
          13511417407733898L,
          17179L,
          0xFFEFEFEFEFEFEF91L,
          13512448205127728L,
          63978L,
          0xFFDFDFDFDFDFDFA1L,
          9008441047646240L,
          56650L,
          0xFFBFBFBFBFBFBFC1L,
          13511211211554864L,
          15929L,
          0xFF7F7F7F7F7F7F81L,
          0xFFA8008DFF09FFF8L,
          55905L,
          0xFFFEFEFEFEFE81FFL,
          9205348825003917308L,
          26301L,
          0xFFFDFDFDFDFD83FFL,
          21992397144066L,
          78100L,
          0xFFFBFBFBFBFB85FFL,
          26389411528776L,
          86245L,
          0xFFF7F7F7F7F789FFL,
          9223345648611360696L,
          75228L,
          0xFFEFEFEFEFEF91FFL,
          0xFFFFCFFE7FCFFFAFL,
          31661L,
          0xFFDFDFDFDFDFA1FFL,
          26391501865056L,
          38053L,
          0xFFBFBFBFBFBFC1FFL,
          0xFFFFE7FF8FBFFFE8L,
          37433L,
          0xFF7F7F7F7F7F81FFL,
          26389090795544L,
          74747L,
          0xFFFEFEFEFE81FEFFL,
          13510901978890243L,
          53847L,
          0xFFFDFDFDFD83FDFFL,
          844476478521343L,
          70952L,
          0xFFFBFBFBFB85FBFFL,
          0xFFFDFFF7FBFEFFF7L,
          49447L,
          0xFFF7F7F7F789F7FFL,
          9205920450792660991L,
          62629L,
          0xFFEFEFEFEF91EFFFL,
          0xFFFEFFDFFDFFDFFFL,
          58996L,
          0xFFDFDFDFDFA1DFFFL,
          8939786031088007199L,
          36009L,
          0xFFBFBFBFBFC1BFFFL,
          2323998178495432720L,
          21230L,
          0xFF7F7F7F7F817FFFL,
          288371136597606656L,
          51882L,
          0xFFFEFEFE81FEFEFFL,
          18049583150530568L,
          11841L,
          0xFFFDFDFD83FDFDFFL,
          4505798785105924L,
          25794L,
          0xFFFBFBFB85FBFBFFL,
          0xFFFDFEFFF7FBFFF7L,
          49689L,
          0xFFF7F7F789F7F7FFL,
          0xFEBF7DFFF8FEFFF9L,
          63400L,
          0xFFEFEFEF91EFEFFFL,
          0xC00000FFE001FFE0L,
          33958L,
          0xFFDFDFDFA1DFDFFFL,
          2308130543272738823L,
          21991L,
          0xFFBFBFBFC1BFBFFFL,
          0xBFFBFAFFFB683F7FL,
          45618L,
          0xFF7F7F7F817F7FFFL,
          578702106657038400L,
          70134L,
          0xFFFEFE81FEFEFEFFL,
          2305852801742274608L,
          75944L,
          0xFFFDFD83FDFDFDFFL,
          581969641496L,
          68392L,
          0xFFFBFB85FBFBFBFFL,
          1112398102552L,
          66472L,
          0xFFF7F789F7F7F7FFL,
          4611686574627225624L,
          23236L,
          0xFFEFEF91EFEFEFFFL,
          36169744961110010L,
          19067L,
          0xFFDFDFA1DFDFDFFFL,
          4611712960757628934L,
          0L,
          0xFFBFBFC1BFBFBFFFL,
          0xFFFFFF7FFFBFBFFFL,
          43566L,
          0xFF7F7F817F7F7FFFL,
          140874929406016L,
          29810L,
          0xFFFE81FEFEFEFEFFL,
          2305845220786317312L,
          65558L,
          0xFFFD83FDFDFDFDFFL,
          0xFFFFF9FF7CFFF3FFL,
          77684L,
          0xFFFB85FBFBFBFBFFL,
          276144592896L,
          73350L,
          0xFFF789F7F7F7F7FFL,
          2305843214839435264L,
          61765L,
          0xFFEF91EFEFEFEFFFL,
          0xFFFFFF6FFE7FCFFDL,
          49282L,
          0xFFDFA1DFDFDFDFFFL,
          0xBFF7EFFFBFC00FFFL,
          78840L,
          0xFFBFC1BFBFBFBFFFL,
          68853737476L,
          82904L,
          0xFF7F817F7F7F7FFFL,
          0xFFFBFFEFA7FFA7FEL,
          24594L,
          0xFF81FEFEFEFEFEFFL,
          5669358141480L,
          9513L,
          0xFF83FDFDFDFDFDFFL,
          571239694356L,
          29012L,
          0xFF85FBFBFBFBFBFFL,
          0x8000002B00408028L,
          27684L,
          0xFF89F7F7F7F7F7FFL,
          4611686156948013096L,
          27901L,
          0xFF91EFEFEFEFEFFFL,
          8646911422261395496L,
          61477L,
          0xFFA1DFDFDFDFDFFFL,
          103093927960L,
          25719L,
          0xFFC1BFBFBFBFBFFFL,
          1769914688190546000L,
          50020L,
          0xFF817F7F7F7F7FFFL,
          2306924928660668456L,
          41547L,
          0x81FEFEFEFEFEFEFFL,
          0xFFFFF37EEFEFDFBEL,
          4750L,
          0x83FDFDFDFDFDFDFFL,
          4611688767357457345L,
          6014L,
          0x85FBFBFBFBFBFBFFL,
          0xBF7FFEFFBFFAF71FL,
          41529L,
          0x89F7F7F7F7F7F7FFL,
          0xFFFDFFFF777B7D6EL,
          84192L,
          0x91EFEFEFEFEFEFFFL,
          0xEEFFFFEFF0080BFEL,
          33433L,
          0xA1DFDFDFDFDFDFFFL,
          0xAFE0000FFF780402L,
          8555L,
          0xC1BFBFBFBFBFBFFFL,
          0xEE73FFFBFFBB77FEL,
          1009L,
          0x817F7F7F7F7F7FFFL,
          562962977269890L
  };

  /** 88507 entries loaded from PrecomputedTables.bin */
  public static final long[] LOOKUP_TABLE = new long[88507];

  public static final long[] SLIDER_PEXT;

  public static final int[] B_BASE = new int[64];
  public static final long[] B_MASK = new long[64];
  public static final long[] B_HASH = new long[64];
  public static final int[] R_BASE = new int[64];
  public static final long[] R_MASK = new long[64];
  public static final long[] R_HASH = new long[64];

  public static final long[] PAWN_ATK_W = new long[64];
  public static final long[] PAWN_ATK_B = new long[64];

  public static final long[] KING_ATK = new long[64];
  public static final long[] KNIGHT_ATK = new long[64];

  public static final long[] W_PUSH  = new long[64];   // single +8
  public static final long[] B_PUSH  = new long[64];   // single –8
  public static final long[] W_PUSH2 = new long[64];   // double +16 (only rank-2)
  public static final long[] B_PUSH2 = new long[64];   // double –16 (only rank-7)

  public static final long[] W_CAP_L = new long[64];   // x7  (white ↙)
  public static final long[] W_CAP_R = new long[64];   // x9  (white ↘)
  public static final long[] B_CAP_L = new long[64];   // x-7 (black ↙ from its POV)
  public static final long[] B_CAP_R = new long[64];   // x-9 (black ↘)

  static {
    try (var in = PreCompMoveGenTables.class.getResourceAsStream("PrecomputedTables.Magic.bin")) {
      if (in == null) throw new IllegalStateException("PrecomputedTables.Magic.bin missing");
      var buf = java.nio.ByteBuffer.wrap(in.readAllBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      buf.asLongBuffer().get(LOOKUP_TABLE);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try (var in = PreCompMoveGenTables.class.getResourceAsStream("PrecomputedTables.Pext.bin")) {
      if (in == null) throw new IllegalStateException("PrecomputedTables.Pext.bin missing");

      byte[] raw = in.readAllBytes(); // full file
      int n = raw.length >>> 3; // 8-bytes → longs

      SLIDER_PEXT = new long[n]; // exact fit
      java.nio.ByteBuffer.wrap(raw)
              .order(java.nio.ByteOrder.LITTLE_ENDIAN)
              .asLongBuffer()
              .get(SLIDER_PEXT);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    for (int s = 0; s < 64; ++s) {
      B_BASE[s] = (int) B_MAGICS[s * 3];
      B_MASK[s] = B_MAGICS[s * 3 + 1];
      B_HASH[s] = B_MAGICS[s * 3 + 2];
      R_BASE[s] = (int) R_MAGICS[s * 3];
      R_MASK[s] = R_MAGICS[s * 3 + 1];
      R_HASH[s] = R_MAGICS[s * 3 + 2];
    }
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;
      if (r < 7 && f > 0) PAWN_ATK_W[sq] |= 1L << (sq + 7);
      if (r < 7 && f < 7) PAWN_ATK_W[sq] |= 1L << (sq + 9);
      if (r > 0 && f > 0) PAWN_ATK_B[sq] |= 1L << (sq - 9);
      if (r > 0 && f < 7) PAWN_ATK_B[sq] |= 1L << (sq - 7);
    }
    for (int sq = 0; sq < 64; ++sq) {
      int r = sq >>> 3, f = sq & 7;

      /* king & knight tables */
      long k = 0;
      for (int dr = -1; dr <= 1; ++dr)
        for (int df = -1; df <= 1; ++df) if ((dr | df) != 0) k = addToMask(k, r + dr, f + df);
      KING_ATK[sq] = k;
      KNIGHT_ATK[sq] = knightMask(r, f);
    }
    for (int sq = 0; sq < 64; sq++) {
      int file = sq & 7;
      int rank = sq >>> 3;

      /* single pushes */
      if (sq + 8 < 64)   W_PUSH[sq] = 1L << (sq + 8);
      if (sq - 8 >= 0)   B_PUSH[sq] = 1L << (sq - 8);

      /* double pushes (only from the starting rank) */
      if (rank == 1)     W_PUSH2[sq] = 1L << (sq + 16);
      if (rank == 6)     B_PUSH2[sq] = 1L << (sq - 16);

      /* captures */
      if (file != 0) {                               // not file A
        if (sq + 7 < 64)  W_CAP_L[sq] = 1L << (sq + 7);
        if (sq - 9 >= 0)  B_CAP_R[sq] = 1L << (sq - 9);
      }
      if (file != 7) {                               // not file H
        if (sq + 9 < 64)  W_CAP_R[sq] = 1L << (sq + 9);
        if (sq - 7 >= 0)  B_CAP_L[sq] = 1L << (sq - 7);
      }
    }
  }

  private static long addToMask(long m, int r, int f) {
    return (r >= 0 && r < 8 && f >= 0 && f < 8) ? m | (1L << ((r << 3) | f)) : m;
  }

  private static long knightMask(int r, int f) {
    long m = 0;
    int[] dr = {-2, -1, 1, 2, 2, 1, -1, -2};
    int[] df = {1, 2, 2, 1, -1, -2, -2, -1};
    for (int i = 0; i < 8; i++) m = addToMask(m, r + dr[i], f + df[i]);
    return m;
  }
}
