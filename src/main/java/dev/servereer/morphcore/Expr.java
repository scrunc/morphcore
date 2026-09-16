package dev.servereer.morphcore;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny, sandboxed math-expression engine for arena-morph reveal formulas. A formula string is
 * compiled ONCE into an AST ({@link #compile}) and then evaluated per changed cell ({@link #eval})
 * against a fixed variable array + the morph's {@link NoiseField}. No reflection, no scripting engine,
 * no I/O — just arithmetic — so a user-authored {@code morphs/*.yml} formula can never do anything but
 * return a number.
 *
 * <p>Variables (filled by {@code ArenaMorph.fillVars}, resolved to array indices at compile time so eval
 * does no map lookups): {@code x y z} (0-based local), {@code wx wy wz} (world), {@code dx dz} (offset from
 * centre), {@code r} (horizontal distance to centre), {@code angle} (atan2(dz,dx), −π..π), {@code cx cz}
 * (centre), {@code sizeX sizeY sizeZ}, {@code seamSin seamCos} (this morph's rift seam), {@code seed}.
 *
 * <p>Functions: {@code sqrt abs sign floor ceil round frac sin cos tan exp log} (1 arg);
 * {@code min max pow hypot atan2 noise ridge} (2 args); {@code clamp fbm} (3 args); {@code warped} (5 args).
 * {@code ridge(a,b) = 1-abs(noise(a,b))}. Operators: {@code + - * / %}, unary {@code -}, {@code ^} (power).
 */
public final class Expr {

    // --- variable slots (public so the caller fills the array by index) ---
    public static final int X = 0, Y = 1, Z = 2, WX = 3, WY = 4, WZ = 5, DX = 6, DZ = 7,
            R = 8, ANGLE = 9, CX = 10, CZ = 11, SIZEX = 12, SIZEY = 13, SIZEZ = 14,
            SEAMSIN = 15, SEAMCOS = 16, SEED = 17;
    public static final int VAR_COUNT = 18;

    private static final Map<String, Integer> VARS = new HashMap<>();
    static {
        VARS.put("x", X); VARS.put("y", Y); VARS.put("z", Z);
        VARS.put("wx", WX); VARS.put("wy", WY); VARS.put("wz", WZ);
        VARS.put("dx", DX); VARS.put("dz", DZ);
        VARS.put("r", R); VARS.put("angle", ANGLE);
        VARS.put("cx", CX); VARS.put("cz", CZ);
        VARS.put("sizeX", SIZEX); VARS.put("sizeY", SIZEY); VARS.put("sizeZ", SIZEZ);
        VARS.put("seamSin", SEAMSIN); VARS.put("seamCos", SEAMCOS); VARS.put("seed", SEED);
        VARS.put("pi", -1); VARS.put("PI", -1);   // handled as constant
    }

    private final Node root;
    private Expr(Node root) { this.root = root; }

    public static Expr compile(String src) {
        return new Expr(new Parser(src).parseAll());
    }

    public double eval(double[] vars, NoiseField noise) {
        return root.eval(vars, noise);
    }

    // --- AST ---
    private interface Node { double eval(double[] v, NoiseField n); }

    private record Num(double c) implements Node { public double eval(double[] v, NoiseField n) { return c; } }

    private record Var(int idx) implements Node {
        public double eval(double[] v, NoiseField n) { return idx < 0 ? Math.PI : v[idx]; }
    }

    private record Bin(char op, Node a, Node b) implements Node {
        public double eval(double[] v, NoiseField n) {
            double x = a.eval(v, n), y = b.eval(v, n);
            return switch (op) {
                case '+' -> x + y; case '-' -> x - y; case '*' -> x * y;
                case '/' -> y == 0 ? 0 : x / y; case '%' -> y == 0 ? 0 : x % y;
                case '^' -> Math.pow(x, y);
                default -> 0;
            };
        }
    }

    private record Neg(Node a) implements Node { public double eval(double[] v, NoiseField n) { return -a.eval(v, n); } }

    private record Func(String name, Node[] args) implements Node {
        public double eval(double[] v, NoiseField n) {
            double a = args.length > 0 ? args[0].eval(v, n) : 0;
            double b = args.length > 1 ? args[1].eval(v, n) : 0;
            double c = args.length > 2 ? args[2].eval(v, n) : 0;
            return switch (name) {
                case "sqrt" -> Math.sqrt(Math.max(0, a));
                case "abs" -> Math.abs(a);
                case "sign" -> Math.signum(a);
                case "floor" -> Math.floor(a);
                case "ceil" -> Math.ceil(a);
                case "round" -> Math.rint(a);
                case "frac" -> a - Math.floor(a);
                case "sin" -> Math.sin(a);
                case "cos" -> Math.cos(a);
                case "tan" -> Math.tan(a);
                case "exp" -> Math.exp(a);
                case "log" -> a <= 0 ? 0 : Math.log(a);
                case "min" -> Math.min(a, b);
                case "max" -> Math.max(a, b);
                case "pow" -> Math.pow(a, b);
                case "hypot" -> Math.hypot(a, b);
                case "atan2" -> Math.atan2(a, b);
                case "noise" -> n.noise(a, b);
                case "ridge" -> 1.0 - Math.abs(n.noise(a, b));
                case "clamp" -> Math.max(b, Math.min(c, a));
                case "fbm" -> n.fbm(a, b, (int) Math.max(1, c), 2.0, 0.5);
                case "warped" -> n.warped(a, b, args[2].eval(v, n), (int) Math.max(1, args[3].eval(v, n)), args[4].eval(v, n));
                default -> 0;
            };
        }
    }

    // --- recursive-descent parser ---
    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s == null ? "" : s; }

        Node parseAll() {
            Node n = expr();
            skip();
            if (i < s.length()) throw new IllegalArgumentException("Unexpected '" + s.charAt(i) + "' at " + i + " in: " + s);
            return n;
        }

        private Node expr() {
            Node n = term();
            while (true) {
                skip();
                if (peek('+')) { i++; n = new Bin('+', n, term()); }
                else if (peek('-')) { i++; n = new Bin('-', n, term()); }
                else return n;
            }
        }

        private Node term() {
            Node n = factor();
            while (true) {
                skip();
                if (peek('*')) { i++; n = new Bin('*', n, factor()); }
                else if (peek('/')) { i++; n = new Bin('/', n, factor()); }
                else if (peek('%')) { i++; n = new Bin('%', n, factor()); }
                else return n;
            }
        }

        private Node factor() {
            skip();
            if (peek('-')) { i++; return new Neg(factor()); }
            if (peek('+')) { i++; return factor(); }
            return power();
        }

        private Node power() {
            Node base = primary();
            skip();
            if (peek('^')) { i++; return new Bin('^', base, factor()); }
            return base;
        }

        private Node primary() {
            skip();
            if (peek('(')) { i++; Node n = expr(); skip(); expect(')'); return n; }
            char c = i < s.length() ? s.charAt(i) : '\0';
            if (Character.isDigit(c) || c == '.') return number();
            if (Character.isLetter(c) || c == '_') return identifier();
            throw new IllegalArgumentException("Unexpected '" + c + "' at " + i + " in: " + s);
        }

        private Node number() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                    || s.charAt(i) == 'e' || s.charAt(i) == 'E'
                    || ((s.charAt(i) == '+' || s.charAt(i) == '-') && i > start && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E')))) i++;
            return new Num(Double.parseDouble(s.substring(start, i)));
        }

        private Node identifier() {
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            String name = s.substring(start, i);
            skip();
            if (peek('(')) {                     // function call
                i++;
                java.util.List<Node> args = new java.util.ArrayList<>();
                skip();
                if (!peek(')')) {
                    args.add(expr());
                    skip();
                    while (peek(',')) { i++; args.add(expr()); skip(); }
                }
                expect(')');
                return new Func(name, args.toArray(new Node[0]));
            }
            Integer idx = VARS.get(name);
            if (idx == null) throw new IllegalArgumentException("Unknown variable/function '" + name + "' in: " + s);
            return new Var(idx);
        }

        private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        private void expect(char c) {
            if (!peek(c)) throw new IllegalArgumentException("Expected '" + c + "' at " + i + " in: " + s);
            i++;
        }
    }
}
