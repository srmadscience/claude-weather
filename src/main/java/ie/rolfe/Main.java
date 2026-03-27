package ie.rolfe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Downloads weather radar images focused on Dublin / Leinster every 15 minutes.
 *
 * Data source: RainViewer API (free, no API key required)
 *   https://www.rainviewer.com/api.html
 *
 * Base map:  OpenStreetMap tiles (© OpenStreetMap contributors, ODbL)
 *
 * Strategy:
 *   - Fetch a 3×3 grid of tiles at zoom 7 (the highest zoom RainViewer's
 *     free radar supports) centred on Dublin.
 *   - Crop the composite to the Leinster bounding box with a margin, then
 *     scale up to OUTPUT_PX × OUTPUT_PX for a cleaner final image.
 *
 * Output: radar_images/dublin_radar_YYYY-MM-DD_HH-mm-ss.png
 *
 * Build:  mvn package
 * Run:    java -jar target/Claude001-1.0-SNAPSHOT-jar-with-dependencies.jar
 */
public class
Main {

    // Centre of the tile download grid – Dublin city centre
    private static final double DUBLIN_LAT = 53.3498;
    private static final double DUBLIN_LON = -6.2603;

    // Zoom 7 is the highest zoom level RainViewer's free radar tier supports.
    private static final int ZOOM      = 7;
    private static final int GRID_SIZE = 3;   // 3×3 tile grid
    private static final int TILE_PX   = 256; // pixels per tile

    // Leinster / Dublin crop bounds (degrees) – add a margin around the province
    private static final double CROP_NORTH =  54.2;
    private static final double CROP_SOUTH =  51.9;
    private static final double CROP_WEST  =  -8.8;
    private static final double CROP_EAST  =  -5.4;

    // Final image dimensions (16:9)
    private static final int OUTPUT_W = 1280;
    private static final int OUTPUT_H =  720;

    private static final String RAINVIEWER_API =
            "https://api.rainviewer.com/public/weather-maps.json";
    private static final String RADAR_TILE_BASE =
            "https://tilecache.rainviewer.com";
    private static final String OSM_TILE_BASE =
            "https://tile.openstreetmap.org";

    private static final Path OUTPUT_DIR = Path.of("radar_images");
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                             .withZone(ZoneId.of("Europe/Dublin"));
    private static final DateTimeFormatter LABEL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                             .withZone(ZoneId.of("Europe/Dublin"));

    private final HttpClient http;
    private final ScheduledExecutorService scheduler;

    public Main() {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.scheduler = Executors.newScheduledThreadPool(1);
    }

    // ── Tile / pixel coordinate helpers ─────────────────────────────────────

    /** Longitude → integer tile X at the given zoom level. */
    private static int lonToTileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << zoom));
    }

    /** Latitude → integer tile Y (Web Mercator) at the given zoom level. */
    private static int latToTileY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        double log = Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad));
        return (int) Math.floor((1.0 - log / Math.PI) / 2.0 * (1 << zoom));
    }

    /**
     * Longitude → pixel X within the composite image.
     * {@code startTileX} is the tile column of the image's left edge.
     */
    private static int lonToPixel(double lon, int startTileX) {
        double tileX = (lon + 180.0) / 360.0 * (1 << ZOOM);
        return (int) Math.round((tileX - startTileX) * TILE_PX);
    }

    /**
     * Latitude → pixel Y within the composite image (Web Mercator).
     * {@code startTileY} is the tile row of the image's top edge.
     */
    private static int latToPixel(double lat, int startTileY) {
        double latRad = Math.toRadians(lat);
        double tileY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)
                       / 2.0 * (1 << ZOOM);
        return (int) Math.round((tileY - startTileY) * TILE_PX);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "DublinRadarDownloader/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " – " + url);
        }
        return resp.body();
    }

    private BufferedImage fetchImage(String url) throws IOException, InterruptedException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(get(url)));
        if (img == null) throw new IOException("Could not decode image from " + url);
        return img;
    }

    // ── RainViewer API ───────────────────────────────────────────────────────

    private String latestRadarPath() throws IOException, InterruptedException {
        String json = new String(get(RAINVIEWER_API));
        JsonObject root  = JsonParser.parseString(json).getAsJsonObject();
        JsonArray  past  = root.getAsJsonObject("radar").getAsJsonArray("past");
        if (past.isEmpty()) throw new IOException("No past radar frames in API response");
        return past.get(past.size() - 1).getAsJsonObject().get("path").getAsString();
    }

    // ── Main download task ───────────────────────────────────────────────────

    private void downloadAndSave() {
        System.out.printf("[%s] Fetching radar data…%n", Instant.now());
        try {
            String radarPath = latestRadarPath();
            System.out.println("  Radar frame: " + radarPath);

            int cx   = lonToTileX(DUBLIN_LON, ZOOM);
            int cy   = latToTileY(DUBLIN_LAT, ZOOM);
            int half = GRID_SIZE / 2;          // = 1 for GRID_SIZE=3

            int startTileX = cx - half;         // leftmost tile column
            int startTileY = cy - half;         // topmost tile row

            int compositeW = GRID_SIZE * TILE_PX;
            int compositeH = GRID_SIZE * TILE_PX;
            BufferedImage composite = new BufferedImage(compositeW, compositeH,
                                                        BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = composite.createGraphics();

            // 1. Base map (OpenStreetMap)
            for (int dy = -half; dy <= half; dy++) {
                for (int dx = -half; dx <= half; dx++) {
                    int tx  = cx + dx;
                    int ty  = cy + dy;
                    int px  = (dx + half) * TILE_PX;
                    int py  = (dy + half) * TILE_PX;
                    String url = "%s/%d/%d/%d.png".formatted(OSM_TILE_BASE, ZOOM, tx, ty);
                    try {
                        g.drawImage(fetchImage(url), px, py, null);
                    } catch (Exception e) {
                        System.err.println("  OSM tile failed [" + tx + "," + ty + "]: " + e.getMessage());
                    }
                }
            }

            // 2. Radar overlay at 75 % opacity (colour scheme 2 = Meteorological)
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
            for (int dy = -half; dy <= half; dy++) {
                for (int dx = -half; dx <= half; dx++) {
                    int tx  = cx + dx;
                    int ty  = cy + dy;
                    int px  = (dx + half) * TILE_PX;
                    int py  = (dy + half) * TILE_PX;
                    // Format: {host}{path}/256/{zoom}/{x}/{y}/{colorScheme}/{smooth}_{snow}.png
                    String url = "%s%s/256/%d/%d/%d/2/1_1.png"
                            .formatted(RADAR_TILE_BASE, radarPath, ZOOM, tx, ty);
                    try {
                        g.drawImage(fetchImage(url), px, py, null);
                    } catch (Exception e) {
                        System.err.println("  Radar tile failed [" + tx + "," + ty + "]: " + e.getMessage());
                    }
                }
            }
            g.dispose();

            // 3. Crop to Leinster bounding box, forcing a 16:9 aspect ratio.
            //    Drive the height from the latitude bounds, then derive the width
            //    for 16:9 and centre it horizontally — no stretching.
            int cropY1 = clamp(latToPixel(CROP_NORTH, startTileY), 0, compositeH);
            int cropY2 = clamp(latToPixel(CROP_SOUTH, startTileY), 0, compositeH);
            int cropH  = cropY2 - cropY1;
            int cropW  = cropH * OUTPUT_W / OUTPUT_H;   // 16:9 width from height

            // Centre the 16:9 width on the geographic east-west centre
            int lonCentreX = (lonToPixel(CROP_WEST, startTileX) + lonToPixel(CROP_EAST, startTileX)) / 2;
            int cropX1 = clamp(lonCentreX - cropW / 2, 0, compositeW);
            int cropX2 = clamp(cropX1 + cropW,         0, compositeW);
            cropW = cropX2 - cropX1;

            // Scale the crop up to OUTPUT_W × OUTPUT_H (16:9)
            BufferedImage output = new BufferedImage(OUTPUT_W, OUTPUT_H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D og = output.createGraphics();
            og.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            og.setRenderingHint(RenderingHints.KEY_RENDERING,
                                RenderingHints.VALUE_RENDER_QUALITY);
            og.drawImage(composite, 0, 0, OUTPUT_W, OUTPUT_H,
                         cropX1, cropY1, cropX1 + cropW, cropY1 + cropH, null);
            og.dispose();

            // 4. Timestamp label
            stampImage(output, radarPath);

            // 5. Save
            Files.createDirectories(OUTPUT_DIR);
            String ts  = FILE_FMT.format(Instant.now());
            Path   out = OUTPUT_DIR.resolve("dublin_radar_" + ts + ".png");
            ImageIO.write(output, "PNG", out.toFile());
            System.out.println("  Saved: " + out.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("  ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void stampImage(BufferedImage img, String radarPath) {
        String[] parts = radarPath.split("/");
        String label;
        try {
            long epoch = Long.parseLong(parts[parts.length - 1]);
            label = "Dublin / Leinster Radar  |  " + LABEL_FMT.format(Instant.ofEpochSecond(epoch));
        } catch (NumberFormatException e) {
            label = "Dublin / Leinster Radar  |  " + LABEL_FMT.format(Instant.now());
        }
        label += "  |  © RainViewer · © OpenStreetMap contributors";

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();

        int pad = 6, bx = 8, by = img.getHeight() - fm.getHeight() - pad * 2 - 8;
        int bw  = fm.stringWidth(label) + pad * 2;
        int bh  = fm.getHeight() + pad * 2;

        g.setColor(new Color(0, 0, 0, 170));
        g.fillRoundRect(bx, by, bw, bh, 6, 6);
        g.setColor(Color.WHITE);
        g.drawString(label, bx + pad, by + pad + fm.getAscent());
        g.dispose();
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    public void start() {
        System.out.println("=== Dublin / Leinster Weather Radar Downloader ===");
        System.out.println("Output directory : " + OUTPUT_DIR.toAbsolutePath());
        System.out.println("Interval         : every 15 minutes");
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        scheduler.scheduleAtFixedRate(this::downloadAndSave, 0, 15, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down scheduler…");
            scheduler.shutdownNow();
        }));
    }

    public static void main(String[] args) {
        new Main().start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
