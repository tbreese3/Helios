package core;

import static core.contracts.PositionFactory.*;

import core.contracts.MoveGenerator;
import core.contracts.PositionFactory;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MoveGeneratorDiffPerftTest {

    /* ───── engine bridge ───── */
    private static final String STOCKFISH = "C:\\Users\\tyler\\Documents\\HeliosMisc\\stockfish.exe";
    private static final PositionFactory PF  = new PositionFactoryImpl();
    private static final MoveGenerator   GEN = new MoveGeneratorImpl();

    private static final int MAX_PLY = 256, LIST_CAP = 256;
    private static final int[][] MOVES = new int[MAX_PLY][LIST_CAP];

    /* ───── progress bar ───── */
    private static final class Bar {
        private static final int W = 40, MASK = 0xFFFF;
        final long tot, start = System.nanoTime();
        final AtomicLong done = new AtomicLong();
        Bar(long tot){ this.tot = tot; }
        void inc(){ done.incrementAndGet(); }
        void tick(){
            long d = done.get();
            if ((d & MASK) != 0 && (tot<=0 || d!=tot)) return;
            long s = Math.max(1,(System.nanoTime()-start)/1_000_000_000L);
            double nps = d/(double)s;
            if (tot>0){
                double p = Math.min(100,d*100.0/tot);
                int f = (int)(p/100*W);
                System.err.printf("\r[%s] %5.1f%% %,d/%,d (%,.0f nps)",
                        "=".repeat(f)+" ".repeat(W-f),p,d,tot,nps);
            } else {
                char[] spin = {'|','/','-','\\'};
                System.err.printf("\r%c  %,d nodes (%,.0f nps)",
                        spin[(int)(d&3)],d,nps);
            }
            System.err.flush();
        }
    }

    /* ───── test-vector holder ───── */
    private record Vec(String fen,int depth,long nodes){}
    private final List<Vec> vecs = new ArrayList<>();

    @BeforeAll void load() throws Exception{
        try (var in = getClass().getResourceAsStream("/perft/qbb.txt");
             var br = new BufferedReader(
                     new InputStreamReader(
                             Objects.requireNonNull(in,"qbb.txt missing")))) {

            br.lines().map(String::trim)
                    .filter(l -> !(l.isEmpty() || l.startsWith("#")))
                    .forEach(l -> {
                        String[] p = l.split(";");
                        vecs.add(new Vec(p[0].trim(),
                                Integer.parseInt(p[1].trim()),
                                Long.parseLong(p[2].trim())));
                    });
        }
    }
    Stream<Vec> vecStream(){ return vecs.stream(); }

    /* ═══════════════════════════════════════════════ */
    @ParameterizedTest(name="diff-perft {index}")
    @MethodSource("vecStream")
    void diffPerft(Vec v) throws Exception {

        Bar bar = new Bar(v.nodes);

        long[] pos = PF.fromFen(normalizeFen(v.fen()));
        Deque<String> path = new ArrayDeque<>();

        Process sf = new ProcessBuilder(STOCKFISH).redirectErrorStream(true).start();
        try (var sin = new BufferedReader(new InputStreamReader(sf.getInputStream()));
             var sout = sf.getOutputStream()) {

            send(sout,"uci");  waitFor(sin,"uciok");
            send(sout,"ucinewgame");
            send(sout,"position fen "+PF.toFen(pos));
            send(sout,"iperft "+ v.depth());

            walk(pos, v.depth(), sin, sout, path, null, bar);

            for (String l; (l=sin.readLine())!=null; )
                if (l.startsWith("iperftok done")) break;

        } finally { sf.destroy(); }

        if (bar.done.get() < v.nodes)
            throw new AssertionError("Traversal finished early: "+bar.done.get()
                    +" of "+v.nodes+" nodes visited.");
        System.err.println();
    }

    /* ───── DFS driver ───── */
    private void walk(long[] pos,int depth,BufferedReader sin,OutputStream sout,
                      Deque<String> path, NodeDump parent, Bar bar) throws Exception {

        NodeDump node = parseNode(sin);
        compareNode(pos,node,parent,path);

        if (depth == 0) {           // this is a leaf (original perft node)
            bar.inc(); bar.tick();  // count it
            return;
        }

        List<String> children = legalMoves(pos);   // <── now really legal
        for (String uci : children){
            send(sout,"next");
            int mv = uciToInt(pos,uci);
            PF.makeMoveInPlace(pos,mv,GEN);
            path.addLast(uci);

            walk(pos,depth-1,sin,sout,path,node,bar);

            PF.undoMoveInPlace(pos);
            path.removeLast();
        }
    }

    /* ───── node parsing (unchanged) ───── */
    private record NodeDump(int cap,int qui,int eva,int leg,
                            List<String> capL,List<String> quiL,
                            List<String> evaL,List<String> legL,
                            String fen){}

    private static final Pattern CAT =
            Pattern.compile("^(Captures|Quiets|Evasions|Legal)\\s*\\((\\d+)\\):");

    private static NodeDump parseNode(BufferedReader in)throws IOException{
        int cap=0,qui=0,eva=0,leg=0;
        List<String> capL=new ArrayList<>(), quiL=new ArrayList<>(),
                evaL=new ArrayList<>(), legL=new ArrayList<>();
        String fen=null,cur=null,l;
        while((l=in.readLine())!=null){
            if(l.startsWith("FEN ")){ fen=l.substring(4).trim(); break; }
            Matcher m=CAT.matcher(l);
            if(m.find()){
                cur=m.group(1);
                int n=Integer.parseInt(m.group(2));
                switch(cur){
                    case "Captures"->cap=n; case "Quiets"->qui=n;
                    case "Evasions"->eva=n; case "Legal"->leg=n;
                }
                addMoves(bucket(cur,capL,quiL,evaL,legL),l,m.end());
            } else if(cur!=null && l.startsWith(cur)){
                addMoves(bucket(cur,capL,quiL,evaL,legL),
                        l,l.indexOf(':')+1);
            }
        }
        Objects.requireNonNull(fen,"EOF before FEN");

        capL.sort(null); quiL.sort(null); evaL.sort(null); legL.sort(null);
        return new NodeDump(cap,qui,eva,leg,capL,quiL,evaL,legL,fen);
    }
    private static List<String> bucket(String tag,
                                       List<String>a,List<String>b,List<String>c,List<String>d){
        return switch(tag){
            case "Captures"->a; case "Quiets"->b; case "Evasions"->c; default->d;
        };
    }
    private static void addMoves(List<String>d,String l,int from){
        if(from<0||from>=l.length()) return;
        for(String s:l.substring(from).trim().split("\\s+"))
            if(!s.isEmpty()) d.add(s);
    }

    /* ───── legal move list in SF-order ───── */
    private List<String> legalMoves(long[] pos){
        int[] buf = new int[LIST_CAP];
        boolean inCheck = GEN.kingAttacked(pos,(pos[META]&1L)==0);

        int n = inCheck ? GEN.generateEvasions(pos,buf,0)
                : GEN.generateQuiets(pos,buf,
                GEN.generateCaptures(pos,buf,0));

        List<String> moves = new ArrayList<>();
        for(int i=0;i<n;i++){
            int mv = buf[i];
            if(isLegal(pos,mv))
                moves.add(moveToUci(mv));
        }
        moves.sort(null);
        return moves;
    }

    /* ───── comparison ───── */
    private void compareNode(long[] pos, NodeDump sf,
                             NodeDump parent, Deque<String> path){

        String myFen = PF.toFen(pos);
        if(!myFen.equals(sf.fen))
            throw new AssertionError("FEN mismatch @ "+path+"\nMine : "+myFen+"\nSF   : "+sf.fen);

        int ply = path.size();
        int[] buf = MOVES[ply];
        boolean chk = GEN.kingAttacked(pos,(pos[META]&1L)==0);

        /* pseudo buckets (as Stockfish reports) */
        int cap = chk?0:GEN.generateCaptures(pos,buf,0);
        int quiTot = chk?0:GEN.generateQuiets(pos,buf,cap);
        int eva =  chk?   GEN.generateEvasions(pos,buf,0):0;
        int quiet = quiTot - cap;

        List<String> capMy = uciList(buf,0,        cap);         capMy.sort(null);
        List<String> quiMy = uciList(buf,cap,      quiet);       quiMy.sort(null);
        List<String> evaMy = uciList(buf,0,        eva);         evaMy.sort(null);

        /* build filtered legal list */
        List<String> legMy = new ArrayList<>();
        int totalPseudo = chk ? eva : quiTot;
        for(int i=0;i<totalPseudo;i++){
            int mv = buf[i];
            if(isLegal(pos,mv))
                legMy.add(moveToUci(mv));
        }
        legMy.sort(null);

        boolean bad =
                sf.cap!=cap || sf.qui!=quiet || sf.eva!=eva || sf.leg!=legMy.size()
                        || !sf.capL.equals(capMy) || !sf.quiL.equals(quiMy)
                        || !sf.evaL.equals(evaMy) || !sf.legL.equals(legMy);

        if(!bad) return;

        /* pretty diff */
        throw new AssertionError("""
                Bucket diff @ %s
                Fen   : %s
                Move  : %s
                Counts (SF|Mine)   cap %d|%d  qui %d|%d  eva %d|%d  leg %d|%d
                [CAP] missing:%s  extra:%s
                [QUI] missing:%s  extra:%s
                [EVA] missing:%s  extra:%s
                [LEG] missing:%s  extra:%s
                """.formatted(
                path, myFen, path.peekLast(),
                sf.cap, cap, sf.qui, quiet, sf.eva, eva,
                sf.leg, legMy.size(),
                diff(sf.capL,capMy)[0], diff(sf.capL,capMy)[1],
                diff(sf.quiL,quiMy)[0], diff(sf.quiL,quiMy)[1],
                diff(sf.evaL,evaMy)[0], diff(sf.evaL,evaMy)[1],
                diff(sf.legL,legMy)[0], diff(sf.legL,legMy)[1]));
    }

    private static String[] diff(List<String>a,List<String>b){
        Set<String> miss=new TreeSet<>(a); miss.removeAll(b);
        Set<String> extra=new TreeSet<>(b); extra.removeAll(a);
        return new String[]{miss.toString(),extra.toString()};
    }

    /* ───── helpers ───── */
    private boolean isLegal(long[] pos,int mv){
        if(!PF.makeMoveInPlace(pos,mv,GEN)) return false;
        PF.undoMoveInPlace(pos);
        return true;
    }

    private static void send(OutputStream o,String c)throws IOException{
        o.write((c+'\n').getBytes()); o.flush();
    }
    private static void waitFor(BufferedReader in,String tok)throws IOException{
        for(String l;(l=in.readLine())!=null && !l.contains(tok);){}
    }
    private static List<String> uciList(int[] buf,int from,int n){
        List<String> l=new ArrayList<>(n);
        for(int i=0;i<n;i++) l.add(moveToUci(buf[from+i]));
        return l;
    }
    private static String moveToUci(int m){
        int f=(m>>>6)&63,t=m&63,ty=(m>>>14)&3,pr=(m>>>12)&3;
        String s=""+(char)('a'+(f&7))+(char)('1'+(f>>3))
                +(char)('a'+(t&7))+(char)('1'+(t>>3));
        if(ty==1) s+="nbrq".charAt(pr);
        return s;
    }
    private static int uciToInt(long[] bb,String u){
        int f=(u.charAt(0)-'a')|((u.charAt(1)-'1')<<3);
        int t=(u.charAt(2)-'a')|((u.charAt(3)-'1')<<3);
        int pr=u.length()==5?"nbrq".indexOf(u.charAt(4)):-1;
        int mover=-1;
        for(int p=0;p<12;p++) if((bb[p]&(1L<<f))!=0){ mover=p; break; }
        int typ=0,pi=0;
        if(pr>=0){ typ=1; pi=pr; }
        else if((mover==WK||mover==BK)&&Math.abs(f-t)==2) typ=3;
        else if(mover==WP||mover==BP){
            long meta=bb[META]; int ep=(int)((meta&EP_MASK)>>>EP_SHIFT);
            if(ep!=EP_NONE && t==ep){
                boolean wP = mover==WP && (f>>3)==4 && (t==f+7||t==f+9);
                boolean bP = mover==BP && (f>>3)==3 && (t==f-7||t==f-9);
                if(wP||bP) typ=2;
            }
        }
        return (f<<6)|t|(typ<<14)|(pi<<12)|(mover<<16);
    }
    private static String normalizeFen(String fen){
        String[] p = fen.split(" "); p[2]="-"; return String.join(" ",p);
    }
}
