package com.mrgregles.bsp_teleport.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_teleport.map.MapData;
import com.mrgregles.bsp_teleport.map.MapLayout;
import com.mrgregles.bsp_teleport.map.MapLayout.Link;
import com.mrgregles.bsp_teleport.map.MapLayout.Side;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D201: the MAP tab laid out for many networks with the game font's own widths and the English language file: nothing
 * leaves the map, no tiles overlap, every label stays inside its frame and clear of the others, and the footer and the
 * tab row fit. Each layout is also drawn to {@code build/map-previews/} for a look (with the game's glyphs when the
 * Minecraft client jar is in the Gradle cache, else as boxes).
 */
class MapViewTest {
    /** Advances in pixels of ASCII 32 to 126 in 1.20.1's default font (ascii.png glyph width + 1; space 4). */
    private static final String ADVANCES = "42466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247";
    private static final MapView.Font FONT = text -> {
        int w = 0;
        for (char ch : text.toCharArray()) {
            w += ch >= 32 && ch < 127 ? ADVANCES.charAt(ch - 32) - '0' : 6;
        }
        return w;
    };
    private static final Map<String, String> LANG = lang();
    private static final MapView.Words WORDS = (key, args) -> {
        String t = LANG.get(key);
        if (t == null) {
            fail("no English text for " + key);
        }
        return args.length == 0 ? t : String.format(t, args);
    };
    /** The band: 248 by 224, the list from 30, the footer 22 tall. As BandScreen draws them. */
    private static final int W = 248, H = 224, LIST_TOP = 30, FOOT_H = 22;
    private static final MapView.Rect AREA = new MapView.Rect(10, LIST_TOP, W - 20, H - FOOT_H - 4 - LIST_TOP);
    private static final MapView.Rect FOOT = new MapView.Rect(10, H - FOOT_H + 4, W - 20, 16);

    private static Map<String, String> lang() {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/bsp_teleport/lang/en_us.json"))).getAsJsonObject();
            Map<String, String> out = new TreeMap<>();
            o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ networks

    /** A network: linked worlds, servers off the grid with their warps, portals, servers with no route. */
    private static final class Net {
        final Map<String, Map<Side, Link>> links = new LinkedHashMap<>();
        final Map<String, Integer> sizes = new LinkedHashMap<>();
        final Map<String, List<String>> off = new LinkedHashMap<>();
        final List<MapData.Portal> portals = new ArrayList<>();
        final List<String> none = new ArrayList<>();
        final List<String> offline = new ArrayList<>();
        String here = "", first = "";

        Net link(String a, Side as, String b, Side bs) {
            links.computeIfAbsent(a, k -> new EnumMap<>(Side.class)).put(as, new Link(b, bs));
            return this;
        }

        Net size(String s, int size) {
            sizes.put(s, size);
            return this;
        }

        MapData data() {
            List<MapLayout.World> worlds = new ArrayList<>();
            sizes.keySet().forEach(s -> worlds.add(new MapLayout.World(s, sizes.get(s), links.getOrDefault(s, Map.of()))));
            links.keySet().stream().filter(s -> !sizes.containsKey(s)).forEach(s -> worlds.add(new MapLayout.World(s, 0, links.get(s))));
            MapLayout.Result r = MapLayout.layout(worlds, first);
            List<MapData.Server> servers = new ArrayList<>();
            for (MapLayout.Cell c : r.cells()) {
                List<MapData.Crossing> cr = new ArrayList<>();
                r.crossings().getOrDefault(c.server(), List.of()).forEach(x -> cr.add(new MapData.Crossing(x.side().ordinal(), x.to())));
                servers.add(new MapData.Server(c.server(), MapData.Kind.GRID, c.x(), c.y(), c.size(), !offline.contains(c.server()), 3, cr, List.of()));
            }
            off.forEach((s, w) -> servers.add(new MapData.Server(s, MapData.Kind.OFF, 0, 0, 0, !offline.contains(s), 6, List.of(), w)));
            none.forEach(s -> servers.add(new MapData.Server(s, MapData.Kind.NONE, 0, 0, 0, !offline.contains(s), 0, List.of(), List.of())));
            List<MapData.Bridge> bridges = new ArrayList<>();
            r.bridges().forEach(b -> bridges.add(new MapData.Bridge(b.a(), b.b())));
            List<MapData.Stub> stubs = new ArrayList<>();
            r.stubs().forEach(s -> stubs.add(new MapData.Stub(s.server(), s.side().ordinal(), s.to())));
            return new MapData(here, r.cols(), r.rows(), servers, bridges, stubs, portals);
        }
    }

    /** The owner's network: four worlds wrapping round on every side, Spawn by warps, Events by a portal, two with no route. */
    private static Net owners() {
        Net n = new Net();
        n.size("BSP-Frostwarf", 8000).size("BSP-Boulderbrook", 8000).size("BSP-Moongrove", 6000).size("BSP-Stormfall", 10000);
        n.link("BSP-Frostwarf", Side.EAST, "BSP-Boulderbrook", Side.WEST).link("BSP-Moongrove", Side.EAST, "BSP-Stormfall", Side.WEST)
                .link("BSP-Frostwarf", Side.SOUTH, "BSP-Moongrove", Side.NORTH).link("BSP-Boulderbrook", Side.SOUTH, "BSP-Stormfall", Side.NORTH)
                .link("BSP-Frostwarf", Side.NORTH, "BSP-Moongrove", Side.SOUTH).link("BSP-Boulderbrook", Side.NORTH, "BSP-Stormfall", Side.SOUTH)
                .link("BSP-Frostwarf", Side.WEST, "BSP-Boulderbrook", Side.EAST).link("BSP-Moongrove", Side.WEST, "BSP-Stormfall", Side.EAST);
        n.off.put("BSP-Spawn", List.of("bank", "leaderboard", "market", "mines", "portals", "shops", "spawn"));
        n.off.put("BSP-Events", List.of());
        n.portals.add(new MapData.Portal("BSP-Stormfall", "BSP-Events"));
        n.none.addAll(List.of("BSP-SafeRoom", "BSP-Creative"));
        n.here = "BSP-Spawn";
        n.first = "BSP-Frostwarf";
        return n;
    }

    private static Map<String, String> ownersTokens() {
        return Map.of("BSP-Boulderbrook", "Token VII", "BSP-Frostwarf", "Token XII");
    }

    private static Net grid(int cols, int rows, String prefix, String... names) {
        Net n = new Net();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                String s = prefix + names[y * cols + x];
                n.size(s, 1000 * (4 + (x + y) % 9) + (x == 1 ? 12_500 : 0));
                if (x + 1 < cols) {
                    n.link(s, Side.EAST, prefix + names[y * cols + x + 1], Side.WEST);
                }
                if (y + 1 < rows) {
                    n.link(s, Side.SOUTH, prefix + names[(y + 1) * cols + x], Side.NORTH);
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the checks

    private static void check(String name, List<MapView.Op> ops, MapView.Rect area) {
        List<String> problems = new ArrayList<>();
        List<MapView.Rect> solids = new ArrayList<>(), fades = new ArrayList<>(), boxes = new ArrayList<>();
        List<MapView.Label> labels = new ArrayList<>();
        for (MapView.Op op : ops) {
            if (op instanceof MapView.Box b) {
                boxes.add(b.r());
                if (b.solid()) {
                    solids.add(b.r());
                }
                inside(problems, "box", b.r(), area);
            } else if (op instanceof MapView.Fade f) {
                fades.add(f.r());
                inside(problems, "far-side half", f.r(), area);
            } else if (op instanceof MapView.Fill f) {
                inside(problems, "fill", f.r(), area);
            } else if (op instanceof MapView.Hit h) {
                inside(problems, "click area", h.r(), area);
            } else if (op instanceof MapView.Dash d) {
                inside(problems, "line end", new MapView.Rect(d.x1(), d.y1(), 1, 1), area);
                inside(problems, "line end", new MapView.Rect(d.x2(), d.y2(), 1, 1), area);
            } else if (op instanceof MapView.Label l) {
                labels.add(l);
                inside(problems, "label '" + l.text() + "'", l.bounds(), area);
            }
        }
        List<MapView.Rect> things = new ArrayList<>(solids);
        things.addAll(fades);
        for (int i = 0; i < things.size(); i++) {
            for (int j = i + 1; j < things.size(); j++) {
                if (things.get(i).overlaps(things.get(j))) {
                    problems.add("overlap: " + things.get(i) + " and " + things.get(j));
                }
            }
        }
        for (MapView.Label l : labels) {
            // Inside its frame, and clear of a framed box's 1 unit edge.
            MapView.Rect f = l.frame();
            MapView.Rect room = boxes.contains(f) ? new MapView.Rect(f.x() + 1, f.y() + 1, f.w() - 2, f.h() - 2) : f;
            if (!room.contains(l.bounds())) {
                problems.add("label '" + l.text() + "' " + l.bounds() + " spills out of " + room);
            }
            for (MapView.Rect s : solids) {
                if (!s.equals(f) && s.overlaps(l.bounds())) {
                    problems.add("label '" + l.text() + "' runs onto the box " + s);
                }
            }
        }
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                if (labels.get(i).bounds().overlaps(labels.get(j).bounds())) {
                    problems.add("labels overlap: '" + labels.get(i).text() + "' and '" + labels.get(j).text() + "'");
                }
            }
        }
        if (!problems.isEmpty()) {
            fail(name + ":\n  " + String.join("\n  ", problems));
        }
    }

    private static void inside(List<String> problems, String what, MapView.Rect r, MapView.Rect area) {
        if (!area.contains(r)) {
            problems.add(what + " " + r + " leaves the map area " + area);
        }
    }

    /** Lays out the map and every footer (nothing picked, then each server picked), checks them all, draws the first. */
    private static void all(String name, Net net, Map<String, String> tokens, MapView.Rect area) throws Exception {
        MapData m = net.data();
        List<MapView.Op> ops = MapView.map(m, null, tokens, area, FONT, WORDS);
        check(name, ops, area);
        List<MapView.Op> foot = MapView.footer(m, null, tokens, FOOT, FONT, WORDS);
        check(name + " footer", foot, FOOT);
        String sample = null;
        for (MapData.Server s : m.servers()) {
            check(name + " with " + s.name() + " picked", MapView.map(m, s.name(), tokens, area, FONT, WORDS), area);
            check(name + " footer for " + s.name(), MapView.footer(m, s.name(), tokens, FOOT, FONT, WORDS), FOOT);
            if (sample == null && s.kind() == MapData.Kind.GRID) {
                sample = s.name();
            }
        }
        draw(name, m, ops, MapView.footer(m, sample, tokens, FOOT, FONT, WORDS));
        if (sample != null) {
            draw(name + "-picked", m, MapView.map(m, sample, tokens, area, FONT, WORDS), MapView.footer(m, sample, tokens, FOOT, FONT, WORDS));
        }
    }

    // ------------------------------------------------------------------ the networks

    @Test
    void theOwnersNetworkFits() throws Exception {
        all("owners", owners(), ownersTokens(), AREA);
    }

    @Test
    void theOwnersNetworkFitsDuringARaid() throws Exception {
        // A raid puts a line above the list and starts it 8 lower.
        all("owners-raid", owners(), ownersTokens(), new MapView.Rect(AREA.x(), AREA.y() + 8, AREA.w(), AREA.h() - 8));
    }

    @Test
    void withoutRegionGuardEveryServerIsOffTheGrid() throws Exception {
        Net n = new Net();
        n.off.put("BSP-Spawn", List.of("bank", "market"));
        n.off.put("BSP-Events", List.of());
        n.portals.add(new MapData.Portal("BSP-Spawn", "BSP-Events"));
        n.none.addAll(List.of("BSP-Frostwarf", "BSP-Boulderbrook", "BSP-Moongrove", "BSP-Stormfall", "BSP-SafeRoom", "BSP-Creative"));
        n.here = "BSP-Spawn";
        all("no-grid", n, ownersTokens(), AREA);
    }

    @Test
    void aBiggerGridWithLongNamesFits() throws Exception {
        Net n = grid(3, 3, "BSP-", "Northreach", "Frostwarf-Highlands", "Boulderbrook", "Moongrove", "Stormfall", "Emberdeep-Canyons",
                "Westmarch", "Saltmarsh", "Thunderpeak-Ridge");
        n.off.put("BSP-Spawn", List.of("bank"));
        n.off.put("BSP-Event-Arena-Of-Champions", List.of());
        n.portals.add(new MapData.Portal("BSP-Moongrove", "BSP-Event-Arena-Of-Champions"));
        n.none.addAll(List.of("BSP-SafeRoom", "BSP-Creative-Build-World", "BSP-Testing"));
        n.here = "BSP-Moongrove";
        n.offline.add("BSP-Saltmarsh");
        all("grid-3x3", n, Map.of("BSP-Northreach", "Token XVIII"), AREA);
    }

    @Test
    void aRowOfFourAndAColumnOfThreeFit() throws Exception {
        Net row = grid(4, 1, "BSP-", "Frostwarf", "Boulderbrook", "Moongrove", "Stormfall");
        row.off.put("BSP-Spawn", List.of("bank"));
        row.here = "BSP-Frostwarf";
        all("row-4", row, Map.of(), AREA);
        Net col = grid(1, 3, "BSP-", "Frostwarf", "Boulderbrook", "Moongrove");
        col.off.put("BSP-Spawn", List.of("bank"));
        col.none.add("BSP-SafeRoom");
        all("column-3", col, Map.of(), AREA);
    }

    @Test
    void manyServersOffTheGridFit() throws Exception {
        Net n = owners();
        for (int i = 1; i <= 9; i++) {
            n.off.put("BSP-Minigame-" + i, List.of("game" + i));
        }
        for (int i = 1; i <= 12; i++) {
            n.none.add("BSP-Staff-" + i);
        }
        all("many-off", n, ownersTokens(), AREA);
    }

    @Test
    void aGridAloneAndASingleServerFit() throws Exception {
        Net n = owners();
        n.off.clear();
        n.portals.clear();
        n.none.clear();
        n.here = "BSP-Moongrove";
        all("grid-only", n, ownersTokens(), AREA);
        Net one = new Net();
        one.none.add("server-1");
        one.here = "server-1";
        all("single", one, Map.of(), AREA);
    }

    @Test
    void twoSeparateGroupsAndNamesWithoutAPrefixFit() throws Exception {
        Net n = new Net();
        n.size("Frostwarf", 8000).size("Boulderbrook-Overflow-East", 12000).size("Gamma", 4000).size("Delta", 4000);
        n.link("Frostwarf", Side.EAST, "Boulderbrook-Overflow-East", Side.WEST).link("Gamma", Side.SOUTH, "Delta", Side.NORTH);
        n.off.put("Spawn", List.of("bank"));
        n.none.add("Creative");
        n.here = "Gamma";
        all("two-groups", n, Map.of("Delta", "Token III"), AREA);
    }

    @Test
    void theTabRowFitsForPlayersAndAdmins() {
        String[] admin = {"homes", "warps", "players", "team", "map", "more", "admin"};
        String[] player = {"homes", "warps", "players", "team", "map", "more"};
        for (String[] tabs : List.of(admin, player)) {
            int[] px = new int[tabs.length];
            for (int i = 0; i < tabs.length; i++) {
                px[i] = FONT.width(WORDS.get("screen.bsp_teleport.band.tab." + tabs[i]).toUpperCase());
            }
            int[] w = MapView.tabWidths(px, W - 20, 2, 40);
            int total = (tabs.length - 1) * 2;
            for (int i = 0; i < tabs.length; i++) {
                total += w[i];
                // The label sits inside the tab's 1 unit edge with at least a unit of room each side.
                assertTrue(Math.ceil(px[i] * MapView.SMALL) <= w[i] - 4, tabs[i] + ": label " + px[i] * MapView.SMALL + " in a tab " + w[i] + " wide");
            }
            assertTrue(total <= W - 20, tabs.length + " tabs take " + total + " of " + (W - 20));
        }
    }

    // ------------------------------------------------------------------ pictures

    private static final int PX = 4;
    private static BufferedImage glyphs;
    private static boolean glyphsTried;

    private static BufferedImage glyphs() {
        if (!glyphsTried) {
            glyphsTried = true;
            File jar = new File(System.getProperty("user.home"), ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar");
            if (jar.isFile()) {
                try (ZipFile z = new ZipFile(jar); InputStream in = z.getInputStream(z.getEntry("assets/minecraft/textures/font/ascii.png"))) {
                    glyphs = ImageIO.read(in);
                } catch (Exception ignored) {
                    glyphs = null;
                }
            }
        }
        return glyphs;
    }

    /** The whole band as the game draws it at 4 pixels a unit: frame, tab row with MAP on, help line, map, footer. */
    private static void draw(String name, MapData m, List<MapView.Op> ops, List<MapView.Op> foot) throws Exception {
        BufferedImage img = new BufferedImage((W + 2) * PX, (H + 2) * PX, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.translate(PX, PX);
        fill(g, -1, -1, W + 2, H + 2, MapView.TQ);
        fill(g, 0, 0, W, H, BandClient.BG | 0xFF000000);
        String[] tabs = {"homes", "warps", "players", "team", "map", "more", "admin"};
        int[] px = new int[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            px[i] = FONT.width(WORDS.get("screen.bsp_teleport.band.tab." + tabs[i]).toUpperCase());
        }
        int[] w = MapView.tabWidths(px, W - 20, 2, 40);
        int x = W - 10;
        for (int i = tabs.length - 1; i >= 0; i--) {
            x -= w[i];
            int edge = tabs[i].equals("map") ? MapView.TQ : tabs[i].equals("admin") ? MapView.VIO : MapView.DIM;
            fill(g, x, 5, w[i], 13, edge);
            fill(g, x + 1, 6, w[i] - 2, 11, MapView.SLOT);
            String label = WORDS.get("screen.bsp_teleport.band.tab." + tabs[i]).toUpperCase();
            text(g, label, x + (w[i] - (int) Math.ceil(px[i] * MapView.SMALL)) / 2, 8, edge == MapView.DIM ? MapView.MUTED : edge, false);
            x -= 2;
        }
        text(g, WORDS.get("screen.bsp_teleport.band.map_help"), 10, 19, MapView.MUTED, false);
        fill(g, 10, H - FOOT_H + 1, W - 20, 1, MapView.DIM);
        List<MapView.Op> every = new ArrayList<>(ops);
        every.addAll(foot);
        for (MapView.Op op : every) {
            if (op instanceof MapView.Box b) {
                MapView.Rect r = b.r();
                fill(g, r.x(), r.y(), r.w(), r.h(), b.edge());
                fill(g, r.x() + 1, r.y() + 1, r.w() - 2, r.h() - 2, b.fill());
            } else if (op instanceof MapView.Fill f) {
                fill(g, f.r().x(), f.r().y(), f.r().w(), f.r().h(), f.argb());
            } else if (op instanceof MapView.Fade f) {
                MapView.Rect r = f.r();
                int d = f.side() == 0 || f.side() == 2 ? r.h() : r.w();
                for (int i = 0; i < d; i++) {
                    int a = 0x8C * (d - i) / d << 24 | f.rgb();
                    switch (f.side()) {
                        case 0 -> fill(g, r.x(), r.y() + r.h() - 1 - i, r.w(), 1, a);
                        case 1 -> fill(g, r.x() + i, r.y(), 1, r.h(), a);
                        case 2 -> fill(g, r.x(), r.y() + i, r.w(), 1, a);
                        default -> fill(g, r.x() + r.w() - 1 - i, r.y(), 1, r.h(), a);
                    }
                }
            } else if (op instanceof MapView.Dash d) {
                int n = Math.max(Math.abs(d.x2() - d.x1()), Math.abs(d.y2() - d.y1()));
                for (int i = 0; i <= n; i++) {
                    if (i % 5 < 3) {
                        fill(g, n == 0 ? d.x1() : d.x1() + (d.x2() - d.x1()) * i / n, n == 0 ? d.y1() : d.y1() + (d.y2() - d.y1()) * i / n, 1, 1, d.colour());
                    }
                }
            } else if (op instanceof MapView.Label l) {
                text(g, l.text(), l.x(), l.y(), l.colour(), l.up());
            }
        }
        g.dispose();
        File out = new File("build/map-previews/" + name + ".png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
    }

    private static void fill(Graphics2D g, int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x * PX, y * PX, w * PX, h * PX);
    }

    /** Small text: the game's glyphs at 0.75, or a box per glyph without the client jar. */
    private static void text(Graphics2D g, String s, int x, int y, int argb, boolean up) {
        AffineTransform keep = g.getTransform();
        g.translate(x * PX, y * PX);
        if (up) {
            g.rotate(-Math.PI / 2);
        }
        g.scale(MapView.SMALL * PX, MapView.SMALL * PX);
        BufferedImage font = glyphs();
        int cx = 0;
        for (char ch : s.toCharArray()) {
            int adv = ch >= 32 && ch < 127 ? ADVANCES.charAt(ch - 32) - '0' : 6;
            if (font != null && ch > 32 && ch < 127) {
                int cell = font.getWidth() / 16;
                for (int gy = 0; gy < 8; gy++) {
                    for (int gx = 0; gx < 8; gx++) {
                        int p = font.getRGB((ch % 16) * cell + gx * cell / 8, (ch / 16) * cell + gy * cell / 8);
                        if ((p >>> 24) != 0) {
                            g.setColor(new Color(argb, true));
                            g.fillRect(cx + gx, gy, 1, 1);
                        }
                    }
                }
            } else if (ch != ' ') {
                g.setColor(new Color(argb, true));
                g.fillRect(cx, 1, adv - 1, 6);
            }
            cx += adv;
        }
        g.setTransform(keep);
    }
}
