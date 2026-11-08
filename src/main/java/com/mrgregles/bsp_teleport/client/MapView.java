package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.map.MapData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D201: where everything on the band's MAP tab goes, in panel units, as a list of drawing steps that
 * {@link BandScreen} carries out. Kept apart from the screen and free of Minecraft's classes so the unit tests can lay
 * out any network with the game's own font widths and check that nothing overlaps or spills out of its frame.
 *
 * <p>All text is the band's small text (0.75 of the font). A label carries the frame it must stay inside.
 */
public final class MapView {
    /** Width in pixels of a string in the game font at full size. */
    public interface Font {
        int width(String text);
    }

    /** A translated line: the language file's text for the key, with its arguments. */
    public interface Words {
        String get(String key, Object... args);
    }

    public record Rect(int x, int y, int w, int h) {
        public boolean overlaps(Rect o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        public boolean contains(Rect o) {
            return o.x >= x && o.y >= y && o.x + o.w <= x + w && o.y + o.h <= y + h;
        }
    }

    /** One drawing step. */
    public sealed interface Op permits Box, Fill, Fade, Label, Dash, Hit {
    }

    /** A framed box: a 1 unit edge round a fill. {@code solid} boxes are things that must not overlap each other. */
    public record Box(Rect r, int edge, int fill, boolean solid) implements Op {
    }

    public record Fill(Rect r, int argb) implements Op {
    }

    /** A half tile that fades out away from its world; {@code side} is the side of the world it hangs on (0 north, 1 east, 2 south, 3 west). */
    public record Fade(Rect r, int side, int rgb) implements Op {
    }

    /**
     * Small text at (x, y), or reading upwards from (x, y) when {@code up}. {@code bounds} is the space it takes, in panel
     * units rounded outwards; {@code frame} is what it must stay inside.
     */
    public record Label(int x, int y, String text, int colour, boolean up, Rect bounds, Rect frame) implements Op {
    }

    public record Dash(int x1, int y1, int x2, int y2, int colour) implements Op {
    }

    /** A click on this spot selects {@code server}. */
    public record Hit(Rect r, String server) implements Op {
    }

    static final int TQ = BandClient.TQ, SLOT = BandClient.SLOT, DIM = BandClient.DIM, MUTED = BandClient.MUTED, TEXT = BandClient.TEXT,
            VIO = BandClient.VIO, GOLD = BandClient.GOLD, BAD = BandClient.BAD, OK = BandClient.OK;
    static final int DIM_OVER = 0xB010151C;
    /** Small text is 0.75 of the font: 8 px tall cells make 6 units. */
    public static final float SMALL = 0.75f;
    public static final int LINE = 6;
    private static final int MAX_TILE = 60, SIDE_MIN = 58, SIDE_MAX = 70, SIDE_GAP = 6, OFF_TALL = 24, OFF_SHORT = 14, CHIP = 12, CHIP_STEP = 14;
    private static final String[] SIDES = {"north", "east", "south", "west"};
    private static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private final MapData m;
    @Nullable
    private final String sel;
    private final Map<String, String> tokens;
    private final Font font;
    private final Words words;
    private final String prefix;
    private final List<Op> ops = new ArrayList<>();
    private final Map<String, Rect> placed = new HashMap<>();

    private MapView(MapData m, @Nullable String sel, Map<String, String> tokens, Font font, Words words) {
        this.m = m;
        this.sel = sel;
        this.tokens = tokens;
        this.font = font;
        this.words = words;
        this.prefix = sharedPrefix(m);
    }

    /**
     * The map's drawing steps inside {@code area}.
     *
     * @param tokens the servers the player has no access to, with the token it takes ("Token VII")
     */
    public static List<Op> map(MapData m, @Nullable String selected, Map<String, String> tokens, Rect area, Font font, Words words) {
        MapView v = new MapView(m, selected, tokens, font, words);
        v.layout(area);
        return v.ops;
    }

    /** The footer's steps inside {@code area} (two lines): the clicked server, or a hint. */
    public static List<Op> footer(@Nullable MapData m, @Nullable String selected, Map<String, String> tokens, Rect area, Font font, Words words) {
        MapView v = new MapView(m == null ? new MapData("", 0, 0, List.of(), List.of(), List.of(), List.of()) : m, selected, tokens, font, words);
        v.footer(m == null, area);
        return v.ops;
    }

    // ------------------------------------------------------------------ the map

    private void layout(Rect area) {
        if (m.servers().isEmpty()) {
            label(area.x(), area.y() + 2, words.get("screen.bsp_teleport.band.map_empty"), MUTED, area);
            return;
        }
        List<MapData.Server> offs = new ArrayList<>(), nones = new ArrayList<>();
        for (MapData.Server s : m.servers()) {
            if (s.kind() == MapData.Kind.OFF) {
                offs.add(s);
            } else if (s.kind() == MapData.Kind.NONE) {
                nones.add(s);
            }
        }
        boolean grid = m.cols() > 0 && m.rows() > 0;
        boolean side = !offs.isEmpty() || !nones.isEmpty();
        if (!grid) {
            side(offs, nones, area, false, null);
            return;
        }
        if (!side) {
            grid(area);
            return;
        }
        // The column beside the grid is as wide as its longest name needs; below the grid when that gives bigger tiles.
        int sideW = SIDE_MIN;
        for (MapData.Server s : m.servers()) {
            if (s.kind() != MapData.Kind.GRID) {
                sideW = Math.max(sideW, small(shortName(s.name())) + 8);
            }
        }
        sideW = Math.min(SIDE_MAX, sideW);
        Rect rightGrid = new Rect(area.x(), area.y(), area.w() - sideW - SIDE_GAP, area.h());
        int below = belowHeight(offs, nones, area.w());
        Rect belowGrid = new Rect(area.x(), area.y(), area.w(), area.h() - below - SIDE_GAP);
        boolean right = fit(rightGrid)[0] >= fit(belowGrid)[0] || belowGrid.h() < 30;
        Grid g = grid(right ? rightGrid : belowGrid);
        Rect sideArea = right ? new Rect(area.x() + area.w() - sideW, area.y(), sideW, area.h())
                : new Rect(area.x(), area.y() + area.h() - below, area.w(), below);
        side(offs, nones, sideArea, right, g);
    }

    /**
     * The grid's outer sides that have a far-side half hanging off them (north, east, south, west): only those need room.
     * A half facing a tile beside it is a sliver in the gap and needs none.
     */
    private boolean[] outer() {
        boolean[] out = new boolean[4];
        Set<Long> taken = taken();
        for (MapData.Stub st : m.stubs()) {
            MapData.Server s = m.server(st.server());
            if (s == null || s.kind() != MapData.Kind.GRID || st.side() < 0 || st.side() > 3 || crowded(s, st.side(), taken)) {
                continue;
            }
            switch (st.side()) {
                case 0 -> out[0] |= s.y() == 0;
                case 1 -> out[1] |= s.x() == m.cols() - 1;
                case 2 -> out[2] |= s.y() == m.rows() - 1;
                default -> out[3] |= s.x() == 0;
            }
        }
        return out;
    }

    private Set<Long> taken() {
        Set<Long> taken = new HashSet<>();
        for (MapData.Server s : m.servers()) {
            if (s.kind() == MapData.Kind.GRID) {
                taken.add(key(s.x(), s.y()));
            }
        }
        return taken;
    }

    private static boolean crowded(MapData.Server s, int side, Set<Long> taken) {
        return taken.contains(key(s.x() + STEP[side][0], s.y() + STEP[side][1]));
    }

    /** Tile size, stub depth and gap that fit the grid in {@code r}: deep halves (with names) while the tiles stay readable. */
    private int[] fit(Rect r) {
        boolean[] o = outer();
        int stub = 14, gap = 8, t = tile(r, o, stub, gap);
        if (t < 34 && (o[0] || o[1] || o[2] || o[3])) {
            stub = 8;
            gap = 5;
            t = tile(r, o, stub, gap);
        }
        return new int[] {t, stub, gap};
    }

    private int tile(Rect r, boolean[] o, int stub, int gap) {
        int mx = (o[1] ? stub : 0) + (o[3] ? stub : 0), my = (o[0] ? stub : 0) + (o[2] ? stub : 0);
        int byW = (r.w() - mx - (m.cols() - 1) * gap) / m.cols(), byH = (r.h() - my - (m.rows() - 1) * gap) / m.rows();
        return Math.max(8, Math.min(MAX_TILE, Math.min(byW, byH)));
    }

    /**
     * What was laid out of the grid, for the lines from the servers off it: the open edge past the halves on its east
     * and south, and the rows (columns) whose last tile has a half hanging east (south).
     */
    private record Grid(int ox, int oy, int t, int stub, int gap, int eastEdge, int southEdge, Set<Integer> eastStubRows, Set<Integer> southStubCols,
                        Map<Long, Rect> cells, Set<Long> eastStubs, Set<Long> southStubs) {
    }

    private Grid grid(Rect r) {
        int[] f = fit(r);
        boolean[] o = outer();
        int t = f[0], stub = f[1], gap = f[2];
        int totalW = m.cols() * t + (m.cols() - 1) * gap;
        int west = o[3] ? stub : 0, east = o[1] ? stub : 0;
        int ox = r.x() + west + Math.max(0, (r.w() - west - east - totalW) / 2), oy = r.y() + (o[0] ? stub : 0);
        Set<Long> taken = taken();
        for (MapData.Server s : m.servers()) {
            if (s.kind() == MapData.Kind.GRID) {
                placed.put(s.name(), new Rect(ox + s.x() * (t + gap), oy + s.y() * (t + gap), t, t));
            }
        }
        Set<Integer> eastRows = new HashSet<>(), southCols = new HashSet<>();
        Map<Long, Rect> cells = new HashMap<>();
        Set<Long> eastStubs = new HashSet<>(), southStubs = new HashSet<>();
        for (MapData.Server s : m.servers()) {
            if (s.kind() == MapData.Kind.GRID) {
                cells.put(key(s.x(), s.y()), placed.get(s.name()));
            }
        }
        for (MapData.Stub st : m.stubs()) {
            MapData.Server s = m.server(st.server());
            if (s != null && placed.containsKey(s.name()) && st.side() >= 0 && st.side() < 4) {
                stub(st, s, taken, stub, gap);
                if (!crowded(s, st.side(), taken)) {
                    if (st.side() == 1) {
                        eastStubs.add(key(s.x(), s.y()));
                    } else if (st.side() == 2) {
                        southStubs.add(key(s.x(), s.y()));
                    }
                }
                if (st.side() == 1 && s.x() == m.cols() - 1 && !crowded(s, 1, taken)) {
                    eastRows.add(s.y());
                } else if (st.side() == 2 && s.y() == m.rows() - 1 && !crowded(s, 2, taken)) {
                    southCols.add(s.x());
                }
            }
        }
        int thick = Math.max(4, Math.min(14, t / 4));
        for (MapData.Bridge b : m.bridges()) {
            Rect a = placed.get(b.a()), c = placed.get(b.b());
            if (a == null || c == null) {
                continue;
            }
            int colour = related(b.a()) && related(b.b()) ? TQ : DIM;
            Rect l = a.x() <= c.x() && a.y() <= c.y() ? a : c, rr = l == a ? c : a;
            if (l.y() == rr.y() && rr.x() > l.x() + t) {
                ops.add(new Fill(new Rect(l.x() + t, l.y() + (t - thick) / 2, rr.x() - l.x() - t, thick), colour));
            } else if (l.x() == rr.x() && rr.y() > l.y() + t) {
                ops.add(new Fill(new Rect(l.x() + (t - thick) / 2, l.y() + t, thick, rr.y() - l.y() - t), colour));
            }
        }
        for (MapData.Server s : m.servers()) {
            Rect p = placed.get(s.name());
            if (s.kind() == MapData.Kind.GRID && p != null) {
                gridTile(s, p);
            }
        }
        int right = ox + totalW, bottom = oy + m.rows() * t + (m.rows() - 1) * gap;
        return new Grid(ox, oy, t, stub, gap, right + east + 2, bottom + (o[2] ? stub : 0) + 2, eastRows, southCols, cells, eastStubs, southStubs);
    }

    private void gridTile(MapData.Server s, Rect p) {
        String token = tokens.get(s.name());
        tileBox(s, p, token);
        int room = p.w() - 6, y = p.y() + 3;
        String name = shortName(s.name());
        int bottomY = p.y() + p.h() - 3 - LINE;
        // A name too long for one line takes two when the tile is tall enough for both, its size and its last line.
        String[] two = small(name) > room && p.h() >= 34 ? wrap(name, room) : null;
        if (two != null) {
            label(p.x() + 3, y, two[0], s.up() ? TEXT : MUTED, p);
            label(p.x() + 3, y + LINE + 1, two[1], s.up() ? TEXT : MUTED, p);
            y += LINE + 1;
        } else if (p.h() >= 10) {
            label(p.x() + 3, y, clip(name, room), s.up() ? TEXT : MUTED, p);
        }
        y += LINE + 2;
        if (s.size() > 0 && y + LINE <= (p.h() >= 30 ? bottomY - 1 : p.y() + p.h() - 2)) {
            String size = sizeText(s.size(), room);
            if (size != null) {
                label(p.x() + 3, y, size, MUTED, p);
            }
        }
        if (p.h() >= 30) {
            // Never a cut word here: the token's number alone, or no online count, when the full line does not fit.
            String last = null;
            if (token != null) {
                last = small(token.toUpperCase()) <= room ? token.toUpperCase() : small(token.replaceAll("^.*\\s", "")) <= room ? token.replaceAll("^.*\\s", "") : null;
            } else {
                String full = !s.up() ? words.get("screen.bsp_teleport.band.map_offline") : words.get("screen.bsp_teleport.band.map_online", s.online());
                last = small(full) <= room ? full : null;
            }
            if (last != null) {
                label(p.x() + 3, bottomY, last, token != null ? BAD : s.up() ? OK : MUTED, p);
            }
        }
        dimAndHit(s.name(), p);
    }

    /** A name in two lines of at most {@code room}, broken after a dash or space (the second line cut if still long); null when it has neither. */
    @Nullable
    private String[] wrap(String name, int room) {
        int cut = 0;
        for (int i = 1; i < name.length(); i++) {
            char ch = name.charAt(i - 1);
            if ((ch == '-' || ch == ' ') && small(name.substring(0, i).trim()) <= room) {
                cut = i;
            }
        }
        return cut == 0 ? null : new String[] {name.substring(0, cut).trim(), clip(name.substring(cut).trim(), room)};
    }

    private void tileBox(MapData.Server s, Rect p, @Nullable String token) {
        boolean here = s.name().equals(m.here()), picked = s.name().equals(sel);
        int edge = here ? GOLD : picked ? TEXT : !s.up() ? DIM : token != null ? BAD : TQ;
        ops.add(new Box(p, edge, SLOT, true));
        if (picked && !here) {
            ops.add(new Fill(new Rect(p.x() + 1, p.y() + 1, p.w() - 2, 1), edge));
        }
    }

    private void dimAndHit(String server, Rect p) {
        if (!related(server)) {
            ops.add(new Fill(p, DIM_OVER));
        }
        ops.add(new Hit(p, server));
    }

    /** A crossing to a world that is not beside this one: a half tile that fades out, named like "Frostwarf south". */
    private void stub(MapData.Stub st, MapData.Server s, Set<Long> taken, int depth, int gap) {
        Rect p = placed.get(s.name());
        boolean crowded = crowded(s, st.side(), taken);
        int d = crowded ? Math.max(2, gap / 2 - 1) : depth, t = p.w();
        Rect box = switch (st.side()) {
            case 0 -> new Rect(p.x(), p.y() - d, t, d);
            case 1 -> new Rect(p.x() + t, p.y(), d, t);
            case 2 -> new Rect(p.x(), p.y() + t, t, d);
            default -> new Rect(p.x() - d, p.y(), d, t);
        };
        boolean on = related(st.server()) && related(st.to());
        ops.add(new Fade(box, st.side(), (on ? TQ : DIM) & 0xFFFFFF));
        if (!crowded && d >= 2 * LINE + 2) {
            String name = clip(shortName(st.to()), t - 4);
            String word = clip(words.get("screen.bsp_teleport.band.map_side." + SIDES[st.side()]), t - 4);
            int colour = on ? TEXT : MUTED;
            if (st.side() == 0 || st.side() == 2) {
                label(box.x() + 2, box.y() + 1, name, colour, box);
                label(box.x() + 2, box.y() + 1 + LINE, word, colour, box);
            } else {
                up(box.x() + 1, box.y() + t - 2, name, colour, box);
                up(box.x() + 1 + LINE, box.y() + t - 2, word, colour, box);
            }
        }
        ops.add(new Hit(box, st.to()));
    }

    // ------------------------------------------------------------------ off the grid

    /** Height of the servers off the grid when they sit under it, in rows across the full width. */
    private int belowHeight(List<MapData.Server> offs, List<MapData.Server> nones, int w) {
        int perRow = Math.max(1, (w + SIDE_GAP) / (SIDE_MIN + 16 + SIDE_GAP));
        int h = ((offs.size() + perRow - 1) / perRow) * (OFF_TALL + SIDE_GAP);
        if (!nones.isEmpty()) {
            h += LINE + 2 + ((nones.size() + perRow - 1) / perRow) * CHIP_STEP;
        }
        return h;
    }

    /**
     * The servers off the grid in {@code r}: tiles in rows, then NO ROUTE and its chips. With {@code grid} given, dashed
     * lines join them to it: gold for warps, violet for a portal.
     */
    private void side(List<MapData.Server> offs, List<MapData.Server> nones, Rect r, boolean right, @Nullable Grid grid) {
        int colW = right ? r.w() : Math.max(SIDE_MIN, Math.min(SIDE_MAX, widest(offs, nones) + 8));
        int perRow = right ? 1 : Math.max(1, (r.w() + SIDE_GAP) / (colW + SIDE_GAP));
        int noneRows = (nones.size() + perRow - 1) / perRow;
        int offRows = (offs.size() + perRow - 1) / perRow;
        // NO ROUTE takes what it needs, but leaves the servers above it their room (at most half when both run long);
        // the rest of its list is counted in "+N more".
        int maxNoneH = r.h() - (offs.isEmpty() ? 0 : Math.min(offRows * (OFF_TALL + SIDE_GAP), r.h() / 2));
        int noneH = nones.isEmpty() ? 0 : LINE + 2 + noneRows * CHIP_STEP;
        if (noneH > maxNoneH) {
            noneH = LINE + 2 + Math.max(1, (maxNoneH - LINE - 2) / CHIP_STEP) * CHIP_STEP;
        }
        int offH = offRows * (OFF_TALL + SIDE_GAP) <= r.h() - noneH ? OFF_TALL : OFF_SHORT;
        int y = r.y() + (grid != null && right ? Math.max(0, Math.min(grid.stub(), (r.h() - noneH - offRows * (offH + SIDE_GAP)) / 2)) : 0);
        int offBottom = r.y() + r.h() - noneH;
        int fitRows = Math.max(0, (offBottom - y + SIDE_GAP) / (offH + SIDE_GAP)), fit = fitRows * perRow;
        // When not all fit, the last place that does says how many more there are.
        int shown = offs.size() <= fit ? offs.size() : Math.max(0, fit - 1);
        for (int i = 0; i < shown; i++) {
            placed.put(offs.get(i).name(), new Rect(r.x() + (i % perRow) * (colW + SIDE_GAP), y + (i / perRow) * (offH + SIDE_GAP), colW, offH));
        }
        if (grid != null) {
            lines(offs, grid, right);
        }
        for (MapData.Server s : offs) {
            Rect p = placed.get(s.name());
            if (p != null) {
                offTile(s, p);
            }
        }
        if (shown < offs.size() && fit > 0) {
            Rect slot = new Rect(r.x() + (shown % perRow) * (colW + SIDE_GAP), y + (shown / perRow) * (offH + SIDE_GAP), colW, offH);
            label(slot.x() + 3, slot.y() + (offH - LINE) / 2, clip(words.get("screen.bsp_teleport.band.map_more", offs.size() - shown), slot.w() - 6), MUTED, slot);
        }
        if (!nones.isEmpty()) {
            // Under the grid's column it sits at the bottom; with no grid, straight after the servers above it.
            int afterOffs = y + ((Math.min(offs.size(), Math.max(shown + (shown < offs.size() ? 1 : 0), 0)) + perRow - 1) / perRow) * (offH + SIDE_GAP) + 2;
            int ny = grid == null && !offs.isEmpty() ? Math.min(afterOffs, r.y() + r.h() - noneH) : r.y() + r.h() - noneH;
            Rect head = new Rect(r.x(), ny, r.w(), LINE + 1);
            label(r.x(), ny, clip(words.get("screen.bsp_teleport.band.map_no_route").toUpperCase(), r.w()), MUTED, head);
            int cy = ny + LINE + 2;
            int chipFit = Math.max(0, (r.y() + r.h() - cy + (CHIP_STEP - CHIP)) / CHIP_STEP) * perRow;
            for (int i = 0; i < nones.size(); i++) {
                boolean lastSlot = i == chipFit - 1 && nones.size() > chipFit;
                if (i >= chipFit) {
                    break;
                }
                Rect c = new Rect(r.x() + (i % perRow) * (colW + SIDE_GAP), cy + (i / perRow) * CHIP_STEP, colW, CHIP);
                if (lastSlot) {
                    // The last place says how many more there are instead.
                    label(c.x(), c.y() + 3, clip(words.get("screen.bsp_teleport.band.map_more", nones.size() - i), c.w()), MUTED, c);
                    break;
                }
                MapData.Server s = nones.get(i);
                boolean picked = s.name().equals(sel), here = s.name().equals(m.here());
                ops.add(new Box(c, here ? GOLD : picked ? TEXT : DIM, SLOT, true));
                label(c.x() + 3, c.y() + 3, clip(shortName(s.name()), c.w() - 6), s.up() ? MUTED : DIM, c);
                dimAndHit(s.name(), c);
            }
        }
    }

    private int widest(List<MapData.Server> offs, List<MapData.Server> nones) {
        int w = 0;
        for (MapData.Server s : offs) {
            w = Math.max(w, small(shortName(s.name())));
        }
        for (MapData.Server s : nones) {
            w = Math.max(w, small(shortName(s.name())));
        }
        return w;
    }

    private void offTile(MapData.Server s, Rect p) {
        String token = tokens.get(s.name());
        tileBox(s, p, token);
        int room = p.w() - 6;
        label(p.x() + 3, p.y() + (p.h() >= OFF_TALL ? 3 : (p.h() - LINE) / 2), clip(shortName(s.name()), room), s.up() ? TEXT : MUTED, p);
        if (p.h() >= OFF_TALL) {
            boolean warps = !s.warps().isEmpty(), portal = reachedByPortal(s.name());
            String route = words.get(warps && portal ? "screen.bsp_teleport.band.map_both" : warps ? "screen.bsp_teleport.band.map_warps"
                    : "screen.bsp_teleport.band.map_portal").toUpperCase();
            label(p.x() + 3, p.y() + 3 + LINE + 4, clip(route, room), warps ? GOLD : VIO, p);
        }
        dimAndHit(s.name(), p);
    }

    private boolean reachedByPortal(String server) {
        for (MapData.Portal p : m.portals()) {
            if (p.to().equals(server)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The dashed lines from the servers off the grid, drawn before the tiles so the tiles sit on them. A line runs
     * straight only in the open space beyond the far-side halves, then along a gap between rows (or columns) to its
     * world, so it never crosses a tile or a label.
     */
    private void lines(List<MapData.Server> offs, Grid g, boolean right) {
        // The open edge: past the far-side halves on the side facing the servers off the grid.
        int edge = right ? g.eastEdge() : g.southEdge();
        for (MapData.Server s : offs) {
            Rect p = placed.get(s.name());
            if (p == null || s.warps().isEmpty()) {
                continue;
            }
            int colour = related(s.name()) ? GOLD : DIM;
            int[] end = warpsEnd(g, right, right ? p.y() + p.h() / 2 : p.x() + p.w() / 2);
            if (end == null) {
                continue;
            }
            if (right) {
                ops.add(new Dash(p.x() - 1, p.y() + p.h() / 2, edge, end[1], colour));
                ops.add(new Dash(edge, end[1], end[0], end[1], colour));
            } else {
                ops.add(new Dash(p.x() + p.w() / 2, p.y() - 1, end[0], edge, colour));
                ops.add(new Dash(end[0], edge, end[0], end[1], colour));
            }
            ops.add(new Fill(new Rect(end[0] - 1, end[1] - 1, 2, 2), colour));
        }
        for (MapData.Portal pl : m.portals()) {
            Rect a = placed.get(pl.from()), b = placed.get(pl.to());
            MapData.Server fs = m.server(pl.from()), ts = m.server(pl.to());
            if (a == null || b == null || fs == null || ts == null) {
                continue;
            }
            int colour = related(pl.from()) && related(pl.to()) ? VIO : DIM;
            boolean aOff = fs.kind() != MapData.Kind.GRID, bOff = ts.kind() != MapData.Kind.GRID;
            if (aOff && bOff) {
                // Two servers off the grid: a bracket on the side facing the grid.
                if (right) {
                    int x = Math.min(a.x(), b.x()) - 3, y1 = a.y() + a.h() / 2, y2 = b.y() + b.h() / 2;
                    ops.add(new Dash(a.x() - 1, y1, x, y1, colour));
                    ops.add(new Dash(x, Math.min(y1, y2), x, Math.max(y1, y2), colour));
                    ops.add(new Dash(x, y2, b.x() - 1, y2, colour));
                } else {
                    int y = Math.min(a.y(), b.y()) - 3, x1 = a.x() + a.w() / 2, x2 = b.x() + b.w() / 2;
                    ops.add(new Dash(x1, a.y() - 1, x1, y, colour));
                    ops.add(new Dash(Math.min(x1, x2), y, Math.max(x1, x2), y, colour));
                    ops.add(new Dash(x2, y, x2, b.y() - 1, colour));
                }
                continue;
            }
            Rect off = aOff ? a : b, in = aOff ? b : a;
            MapData.Server t = aOff ? ts : fs;
            if (right) {
                int fy = off.y() + off.h() / 2;
                if (t.x() == m.cols() - 1) {
                    // The last column: straight across to it, stopping at its far-side half if it has one.
                    int ty = in.y() + in.h() / 2;
                    ops.add(new Dash(off.x() - 1, fy, edge, ty, colour));
                    if (!g.eastStubRows().contains(t.y())) {
                        ops.add(new Dash(edge, ty, in.x() + in.w(), ty, colour));
                    }
                } else {
                    int[] gaps = adjacentGaps(t.y(), m.rows(), g.oy(), g);
                    if (gaps.length == 0) {
                        ops.add(new Dash(off.x() - 1, fy, in.x() + in.w(), in.y() + in.h() / 2, colour));
                        continue;
                    }
                    int gy = nearest(gaps, fy), tx = in.x() + in.w() - 4;
                    ops.add(new Dash(off.x() - 1, fy, edge, gy, colour));
                    ops.add(new Dash(edge, gy, tx, gy, colour));
                    ops.add(new Dash(tx, gy, tx, gy < in.y() ? in.y() - 1 : in.y() + in.h(), colour));
                }
            } else {
                int fx = off.x() + off.w() / 2;
                if (t.y() == m.rows() - 1) {
                    int tx = in.x() + in.w() / 2;
                    ops.add(new Dash(fx, off.y() - 1, tx, edge, colour));
                    if (!g.southStubCols().contains(t.x())) {
                        ops.add(new Dash(tx, edge, tx, in.y() + in.h(), colour));
                    }
                } else {
                    int[] gaps = adjacentGaps(t.x(), m.cols(), g.ox(), g);
                    if (gaps.length == 0) {
                        ops.add(new Dash(fx, off.y() - 1, in.x() + in.w() / 2, in.y() + in.h(), colour));
                        continue;
                    }
                    int gx = nearest(gaps, fx), ty = in.y() + in.h() - 4;
                    ops.add(new Dash(fx, off.y() - 1, gx, edge, colour));
                    ops.add(new Dash(gx, edge, gx, ty, colour));
                    ops.add(new Dash(gx, ty, gx < in.x() ? in.x() - 1 : in.x() + in.w(), ty, colour));
                }
            }
        }
    }

    /**
     * Where a warps line meets the grid: the gap between two tiles of the last column (row) when there is one, else
     * the facing edge of the outermost tile in a row (column), or of its far-side half; the one nearest {@code near}.
     * As {x, y}; null with no tiles.
     */
    @Nullable
    private int[] warpsEnd(Grid g, boolean right, int near) {
        List<int[]> ends = new ArrayList<>();
        int lines = right ? m.rows() : m.cols(), last = right ? m.cols() - 1 : m.rows() - 1;
        for (int k = 1; k < lines; k++) {
            Rect a = g.cells().get(right ? key(last, k - 1) : key(k - 1, last)), b = g.cells().get(right ? key(last, k) : key(k, last));
            if (a != null && b != null) {
                ends.add(right ? new int[] {a.x() + a.w() + 1, (a.y() + a.h() + b.y()) / 2} : new int[] {(a.x() + a.w() + b.x()) / 2, a.y() + a.h() + 1});
            }
        }
        if (ends.isEmpty()) {
            for (int k = 0; k < lines; k++) {
                for (int j = last; j >= 0; j--) {
                    long at = right ? key(j, k) : key(k, j);
                    Rect c = g.cells().get(at);
                    if (c != null) {
                        boolean half = (right ? g.eastStubs() : g.southStubs()).contains(at);
                        ends.add(right ? new int[] {c.x() + c.w() + (half ? g.stub() : 0) + 1, c.y() + c.h() / 2}
                                : new int[] {c.x() + c.w() / 2, c.y() + c.h() + (half ? g.stub() : 0) + 1});
                        break;
                    }
                }
            }
        }
        int[] best = null;
        for (int[] e : ends) {
            if (best == null || Math.abs((right ? e[1] : e[0]) - near) < Math.abs((right ? best[1] : best[0]) - near)) {
                best = e;
            }
        }
        return best;
    }

    /** The gaps either side of row (or column) {@code i}. */
    private static int[] adjacentGaps(int i, int count, int origin, Grid g) {
        List<Integer> out = new ArrayList<>();
        if (i > 0) {
            out.add(origin + i * (g.t() + g.gap()) - (g.gap() + 1) / 2);
        }
        if (i < count - 1) {
            out.add(origin + (i + 1) * (g.t() + g.gap()) - (g.gap() + 1) / 2);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int nearest(int[] options, int to) {
        int best = options[0];
        for (int o : options) {
            if (Math.abs(o - to) < Math.abs(best - to)) {
                best = o;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the footer

    private void footer(boolean loading, Rect area) {
        MapData.Server s = loading || sel == null ? null : m.server(sel);
        Rect line1 = new Rect(area.x(), area.y(), area.w(), LINE + 1), line2 = new Rect(area.x(), area.y() + 9, area.w(), LINE + 1);
        if (s == null) {
            label(area.x(), area.y(), clip(words.get(loading ? "screen.bsp_teleport.band.map_loading" : "screen.bsp_teleport.band.map_pick"), area.w()), MUTED, line1);
            return;
        }
        boolean here = s.name().equals(m.here());
        String token = tokens.get(s.name());
        String state = here ? words.get("screen.bsp_teleport.band.map_here")
                : token != null ? words.get("screen.bsp_teleport.band.map_no_access", token)
                : !s.up() ? words.get("screen.bsp_teleport.band.map_offline")
                : words.get("screen.bsp_teleport.band.map_online", s.online());
        state = clip(state, area.w() / 2);
        int stateW = small(state);
        String title = s.name();
        if (s.size() > 0) {
            String size = sizeText(s.size(), Integer.MAX_VALUE);
            title += "  " + size;
        }
        label(area.x(), area.y(), clip(title, area.w() - stateW - 6), here ? GOLD : TEXT, line1);
        label(area.x() + area.w() - stateW, area.y(), state, here ? GOLD : token != null ? BAD : s.up() ? OK : MUTED, line1);
        StringBuilder line = new StringBuilder();
        // Two spaces between crossings, one when that is too long, before anything is cut.
        for (String sep : new String[] {"  ", " "}) {
            line.setLength(0);
            for (int side = 0; side < 4; side++) {
                for (MapData.Crossing c : s.crossings()) {
                    if (c.side() == side) {
                        line.append(line.isEmpty() ? "" : sep).append(words.get("screen.bsp_teleport.band.map_side_short." + SIDES[side]))
                                .append(' ').append(shortName(c.to()));
                    }
                }
            }
            if (small(line.toString()) <= area.w()) {
                break;
            }
        }
        if (line.isEmpty()) {
            if (!s.warps().isEmpty()) {
                line.append(words.get("screen.bsp_teleport.band.map_route_warps", String.join(", ", s.warps())));
            }
            List<String> froms = new ArrayList<>();
            for (MapData.Portal p : m.portals()) {
                if (p.to().equals(s.name()) && !froms.contains(p.from())) {
                    froms.add(p.from());
                }
            }
            for (String from : froms) {
                line.append(line.isEmpty() ? "" : "  ").append(words.get("screen.bsp_teleport.band.map_route_portal", shortName(from)));
            }
            if (line.isEmpty() && !here && s.kind() == MapData.Kind.NONE) {
                line.append(words.get("screen.bsp_teleport.band.map_route_none"));
            }
        }
        if (!line.isEmpty()) {
            label(area.x(), area.y() + 9, clip(line.toString(), area.w()), MUTED, line2);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Whether a server is the one clicked or joined to it (a border, a far side, a portal, warps); every one is with none clicked. */
    boolean related(String server) {
        if (sel == null || sel.equals(server)) {
            return true;
        }
        for (MapData.Bridge b : m.bridges()) {
            if ((b.a().equals(sel) && b.b().equals(server)) || (b.b().equals(sel) && b.a().equals(server))) {
                return true;
            }
        }
        for (MapData.Stub s : m.stubs()) {
            if ((s.server().equals(sel) && s.to().equals(server)) || (s.to().equals(sel) && s.server().equals(server))) {
                return true;
            }
        }
        for (MapData.Portal p : m.portals()) {
            if ((p.from().equals(sel) && p.to().equals(server)) || (p.to().equals(sel) && p.from().equals(server))) {
                return true;
            }
        }
        MapData.Server a = m.server(sel), b = m.server(server);
        if (a == null || b == null) {
            return false;
        }
        return (!a.warps().isEmpty() && a.kind() != MapData.Kind.GRID && b.kind() == MapData.Kind.GRID)
                || (!b.warps().isEmpty() && b.kind() != MapData.Kind.GRID && a.kind() == MapData.Kind.GRID);
    }

    /** "8000 x 8000" when it fits in {@code room}, else "8k x 8k", else null. */
    @Nullable
    private String sizeText(int size, int room) {
        String n = Integer.toString(size);
        String full = words.get("screen.bsp_teleport.band.map_size", n, n);
        if (small(full) <= room) {
            return full;
        }
        String k = size % 1000 == 0 ? size / 1000 + "k" : String.format(java.util.Locale.ROOT, "%.1fk", size / 1000.0);
        String shortText = words.get("screen.bsp_teleport.band.map_size", k, k);
        return small(shortText) <= room ? shortText : null;
    }

    /** Width of small text in panel units, rounded up. */
    private int small(String text) {
        return (int) Math.ceil(font.width(text) * SMALL);
    }

    /** Cut to {@code room} units of small text, ending in "..". */
    String clip(String text, int room) {
        if (small(text) <= room) {
            return text;
        }
        String t = text;
        while (t.length() > 1 && small(t + "..") > room) {
            t = t.substring(0, t.length() - 1);
        }
        return small(t + "..") <= room ? t + ".." : "";
    }

    private void label(int x, int y, String text, int colour, Rect frame) {
        if (!text.isEmpty()) {
            ops.add(new Label(x, y, text, colour, false, new Rect(x, y, small(text), LINE), frame));
        }
    }

    private void up(int x, int y, String text, int colour, Rect frame) {
        if (!text.isEmpty()) {
            int len = small(text);
            ops.add(new Label(x, y, text, colour, true, new Rect(x, y - len, LINE, len), frame));
        }
    }

    private String shortName(String server) {
        return !prefix.isEmpty() && server.startsWith(prefix) && server.length() > prefix.length() ? server.substring(prefix.length()) : server;
    }

    /** The start every server name on the map shares up to a dash ("BSP-"), left off the tiles; empty when there is none. */
    static String sharedPrefix(MapData m) {
        if (m.servers().size() < 2) {
            return "";
        }
        String first = m.servers().get(0).name();
        int dash = first.indexOf('-');
        if (dash <= 0) {
            return "";
        }
        String p = first.substring(0, dash + 1);
        for (MapData.Server s : m.servers()) {
            if (!s.name().startsWith(p) || s.name().length() == p.length()) {
                return "";
            }
        }
        return p;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    // ------------------------------------------------------------------ the tab row

    /**
     * D201: the band's tab widths from their labels' widths in pixels: each label plus padding, the spare room shared out,
     * no tab wider than {@code maxTab}; they end at the right of {@code rowW} with {@code gap} between them.
     */
    public static int[] tabWidths(int[] labelPx, int rowW, int gap, int maxTab) {
        int n = labelPx.length, need = 0;
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = (int) Math.ceil(labelPx[i] * SMALL) + 6;
            need += w[i];
        }
        int spare = rowW - need - (n - 1) * gap;
        for (int i = 0; i < n && spare > 0; i++) {
            int add = Math.min(Math.max(0, maxTab - w[i]), spare / (n - i));
            w[i] += add;
            spare -= add;
        }
        return w;
    }
}
