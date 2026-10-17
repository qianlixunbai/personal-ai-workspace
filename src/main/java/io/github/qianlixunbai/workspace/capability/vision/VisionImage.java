package io.github.qianlixunbai.workspace.capability.vision;

import io.github.qianlixunbai.workspace.common.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.*;
import javax.imageio.spi.*;
import javax.imageio.stream.*;

/** Fixed JDK codecs, memory streams and metadata-free, pixel-oriented RGB input. */
public final class VisionImage {
    public static final int MAX_BYTES = 4 * 1024 * 1024, SIZE = 1024;
    public record Normalized(byte[] png, int width, int height) {
        @Override public String toString() { return "VisionImage[redacted]"; }
    }
    private record Structure(byte[] filtered, int width, int height, String format) {}
    private VisionImage() {}
    public static byte[] synthetic(int rgb) {
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<32;y++) for(int x=0;x<32;x++) image.setRGB(x,y,rgb);
        var bytes=new BoundedOutput(MAX_BYTES);ImageWriter writer=writer();
        try(var output=new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);writer.write(null,new IIOImage(image,null,null),writer.getDefaultWriteParam());output.flush();
        } catch(IOException error) {throw rejected();}
        finally {writer.dispose();image.flush();}
        return normalize(bytes.toByteArray(),"image/png").png();
    }

    public static Normalized normalize(byte[] bytes, String mediaType) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) invalid();
        Structure source = switch (mediaType) {
            case "image/png" -> png(bytes);
            case "image/jpeg" -> jpeg(bytes);
            default -> throw rejected();
        };
        check(deadline);
        ImageReader reader = reader(source.format);
        BufferedImage original = null, target = null;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source.filtered))) {
            reader.addIIOReadWarningListener((ignored, warning) -> { throw rejected(); });
            reader.addIIOReadProgressListener(new javax.imageio.event.IIOReadProgressListener() {
                private void progress() { if (expired(deadline)) { reader.abort(); check(deadline); } }
                public void sequenceStarted(ImageReader r, int n) { progress(); }
                public void sequenceComplete(ImageReader r) { progress(); }
                public void imageStarted(ImageReader r, int n) { progress(); }
                public void imageProgress(ImageReader r, float n) { progress(); }
                public void imageComplete(ImageReader r) { progress(); }
                public void thumbnailStarted(ImageReader r, int n, int t) { throw rejected(); }
                public void thumbnailProgress(ImageReader r, float n) { throw rejected(); }
                public void thumbnailComplete(ImageReader r) { throw rejected(); }
                public void readAborted(ImageReader r) { throw rejected(); }
            });
            reader.setInput(input, false, true);
            if (reader.getWidth(0) != source.width || reader.getHeight(0) != source.height || reader.getNumImages(true) != 1) invalid();
            original = reader.read(0);
            check(deadline);
            if (original == null || original.getWidth() != source.width || original.getHeight() != source.height) invalid();
            target = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
            int w = Math.max(1, (int) ((long) source.width * SIZE / Math.max(source.width, source.height)));
            int h = Math.max(1, (int) ((long) source.height * SIZE / Math.max(source.width, source.height)));
            int left = (SIZE - w) / 2, top = (SIZE - h) / 2;
            // No second full-size RGB raster. Sample into the only destination, including 16-bit input.
            int[] row = new int[SIZE];
            int[] sourceRow = new int[source.width];
            for (int y = 0; y < SIZE; y++) {
                check(deadline); Arrays.fill(row, 0xffffff);
                if (y >= top && y < top + h) {
                    int sy = (int) ((long) (y - top) * source.height / h);
                    original.getRGB(0,sy,source.width,1,sourceRow,0,source.width);
                    for (int x = 0; x < w; x++) {
                        int pixel = sourceRow[(int) ((long) x * source.width / w)];
                        int a = pixel >>> 24, result = 0;
                        for (int shift = 16; shift >= 0; shift -= 8) {
                            int c = (pixel >>> shift) & 255;
                            result |= ((c * a + 255 * (255 - a) + 127) / 255) << shift;
                        }
                        row[left + x] = result;
                    }
                }
                target.setRGB(0, y, SIZE, 1, row, 0, SIZE);
            }
            original.flush(); original = null;
            var output = new BoundedOutput(MAX_BYTES);
            ImageWriter writer = writer();
            try (var stream = new MemoryCacheImageOutputStream(output)) {
                writer.addIIOWriteWarningListener((ignored, n, warning) -> { throw rejected(); });
                writer.setOutput(stream);
                writer.write(null, new IIOImage(target, null, null), writer.getDefaultWriteParam());
                stream.flush(); check(deadline);
                return new Normalized(output.toByteArray(), source.width, source.height);
            } finally { writer.dispose(); }
        } catch (IOException | IllegalArgumentException error) { throw rejected(); }
        finally { reader.dispose(); if (original != null) original.flush(); if (target != null) target.flush(); }
    }

    private static ImageReader reader(String format) {
        String prefix = "com.sun.imageio.plugins." + format + "." + (format.equals("png") ? "PNG" : "JPEG");
        Iterator<ImageReaderSpi> entries = IIORegistry.getDefaultInstance().getServiceProviders(ImageReaderSpi.class, false);
        while (entries.hasNext()) {
            ImageReaderSpi spi = entries.next();
            if (!spi.getClass().getName().equals(prefix + "ImageReaderSpi")) continue;
            if (!"java.desktop".equals(spi.getClass().getModule().getName())) invalid();
            try {
                ImageReader r = spi.createReaderInstance();
                if (!r.getClass().getName().equals(prefix + "ImageReader") || !"java.desktop".equals(r.getClass().getModule().getName()) || r.getOriginatingProvider() != spi) {
                    r.dispose(); invalid();
                }
                return r;
            } catch (IOException error) { throw rejected(); }
        }
        throw rejected();
    }
    private static ImageWriter writer() {
        Iterator<ImageWriterSpi> entries = IIORegistry.getDefaultInstance().getServiceProviders(ImageWriterSpi.class, false);
        while (entries.hasNext()) {
            ImageWriterSpi spi = entries.next();
            if (!spi.getClass().getName().equals("com.sun.imageio.plugins.png.PNGImageWriterSpi")) continue;
            if (!"java.desktop".equals(spi.getClass().getModule().getName())) invalid();
            try {
                ImageWriter w = spi.createWriterInstance();
                if (!w.getClass().getName().equals("com.sun.imageio.plugins.png.PNGImageWriter") || !"java.desktop".equals(w.getClass().getModule().getName()) || w.getOriginatingProvider() != spi) {
                    w.dispose(); invalid();
                }
                return w;
            } catch (IOException error) { throw rejected(); }
        }
        throw rejected();
    }
    private static Structure png(byte[] b) {
        byte[] signature = {(byte)137,80,78,71,13,10,26,10};
        if (b.length < 45 || !Arrays.equals(signature, Arrays.copyOf(b, 8))) invalid();
        var out = new BoundedOutput(b.length); out.writeBytes(signature);
        int p = 8, width = 0, height = 0, color = -1; boolean idat = false, idatEnded = false, palette = false, alpha = false, end = false;
        while (p < b.length) {
            if (b.length - p < 12) invalid();
            long length = unsigned(b, p); if (length > b.length - p - 12) invalid();
            int n = (int) length; String type = new String(b, p + 4, 4, StandardCharsets.US_ASCII);
            if (!type.matches("[A-Za-z]{4}") || Character.isLowerCase(type.charAt(2))) invalid();
            CRC32 crc = new CRC32(); crc.update(b, p + 4, n + 4);
            if (crc.getValue() != unsigned(b, p + n + 8)) invalid();
            if (width == 0 && !type.equals("IHDR") || end || Set.of("acTL", "fcTL", "fdAT").contains(type)) invalid();
            boolean keep = true;
            switch (type) {
                case "IHDR" -> {
                    if (p != 8 || n != 13) invalid();
                    width = dimension(unsigned(b, p + 8)); height = dimension(unsigned(b, p + 12)); dimensions(width, height);
                    int depth = b[p + 16] & 255; color = b[p + 17] & 255;
                    boolean valid = switch (color) { case 0 -> Set.of(1,2,4,8,16).contains(depth); case 2,4,6 -> depth == 8 || depth == 16; case 3 -> Set.of(1,2,4,8).contains(depth); default -> false; };
                    if (!valid || b[p + 18] != 0 || b[p + 19] != 0 || (b[p + 20] != 0 && b[p + 20] != 1)) invalid();
                }
                case "PLTE" -> { if (palette || idat || alpha || color == 0 || color == 4 || n == 0 || n > 768 || n % 3 != 0) invalid(); palette = true; }
                case "tRNS" -> { if (alpha || idat || color == 4 || color == 6 || (color == 3 && (!palette || n > 256 || n == 0)) || color == 0 && n != 2 || color == 2 && n != 6) invalid(); alpha = true; }
                case "IDAT" -> { if (idatEnded || color == 3 && !palette) invalid(); idat = true; }
                case "IEND" -> { if (!idat || n != 0 || p + 12 != b.length) invalid(); end = true; }
                default -> { if (Character.isUpperCase(type.charAt(0))) invalid(); keep = false; }
            }
            if (idat && !type.equals("IDAT")) idatEnded = true;
            if (keep) out.write(b, p, n + 12);
            p += n + 12;
        }
        if (!end) invalid();
        return new Structure(out.toByteArray(), width, height, "png");
    }
    private static Structure jpeg(byte[] b) {
        if (b.length < 4 || (b[0]&255) != 255 || (b[1]&255) != 216) invalid();
        var out = new BoundedOutput(b.length); out.write(b, 0, 2);
        int p = 2, width = 0, height = 0, components = 0; boolean scan = false, end = false;
        while (p < b.length) {
            int start = p;
            if ((b[p++]&255) != 255) invalid();
            while (p < b.length && (b[p]&255) == 255) p++;
            if (p == b.length) invalid();
            int marker = b[p++]&255;
            if (marker == 217) { if (!scan || p != b.length) invalid(); out.write(b, start, p-start); end = true; break; }
            if (marker == 0 || marker == 216 || marker == 220 || marker == 1 || marker >= 208 && marker <= 215 || p + 2 > b.length) invalid();
            int n = ushort(b,p); if (n < 2 || n > b.length - p) invalid();
            if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204) {
                if (marker != 192 && marker != 194 || width != 0 || n < 8 || (b[p+2]&255) != 8) invalid();
                height = ushort(b,p+3); width = ushort(b,p+5); dimensions(width,height); components = b[p+7]&255;
                if (components != 1 && components != 3 || n != 8 + 3 * components) invalid();
                // Retaining only this RGB/grayscale subset avoids CMYK/Adobe profile semantics.
                for (int i=0; i<components; i++) if ((b[p+8+3*i]&255) != i+1) invalid();
            }
            boolean app = marker >= 224 && marker <= 239 || marker == 254;
            if (!app && !Set.of(192,194,196,219,221,218).contains(marker)) invalid();
            if (marker == 218 && (width == 0 || n < 6 || n != 6 + 2 * (b[p+2]&255))) invalid();
            if (!app) out.write(b,start,p+n-start);
            p += n;
            if (marker == 218) {
                scan = true; int entropy = p;
                while (p < b.length) {
                    if ((b[p]&255) != 255) { p++; continue; }
                    if (p+1 == b.length) invalid();
                    int next = b[p+1]&255;
                    if (next == 0 || next >= 208 && next <= 215) { p+=2; continue; }
                    break;
                }
                out.write(b,entropy,p-entropy);
            }
        }
        if (!end || width == 0) invalid();
        return new Structure(out.toByteArray(),width,height,"jpeg");
    }
    private static int ushort(byte[] b,int p) { return (b[p]&255)*256+(b[p+1]&255); }
    private static long unsigned(byte[] b,int p) { return ((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((long)(b[p+2]&255)<<8)|(b[p+3]&255); }
    private static int dimension(long n) { if (n < 1 || n > 4096) invalid(); return (int)n; }
    private static void dimensions(int w,int h) { dimension(w); dimension(h); if ((long)w*h > 8_000_000) invalid(); }
    private static boolean expired(long deadline) { return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline; }
    private static void check(long deadline) { if (expired(deadline)) throw new WorkspaceException(ErrorCode.TASK_TIMEOUT,"VISION_DECODE"); }
    private static WorkspaceException rejected() { return new WorkspaceException(ErrorCode.INVALID_REQUEST,"VISION_IMAGE"); }
    private static void invalid() { throw rejected(); }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int maximum;
        BoundedOutput(int maximum) { super(Math.min(maximum,65536)); this.maximum=maximum; }
        @Override public synchronized void write(int b) { if (count >= maximum) invalid(); super.write(b); }
        @Override public synchronized void write(byte[] b,int p,int n) { if (n > maximum-count) invalid(); super.write(b,p,n); }
        @Override public void writeBytes(byte[] b) { write(b,0,b.length); }
    }
}
