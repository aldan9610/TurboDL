package com.turbodl.util;

/**
 * Analizador minimo de cajas MP4 para extraer duracion y resolucion de un
 * fragmento del archivo (cabecera o cola). No carga el archivo completo:
 * el sondeo descarga solo ~256 KB del inicio y, si no encuentra 'moov',
 * ~512 KB del final. Todo best-effort con validaciones para evitar falsos
 * positivos dentro de 'mdat'.
 */
public final class Mp4 {

    public static class Info {
        public long durationSec = -1;
        public int width = 0;
        public int height = 0;
        public boolean hasMoov = false;
        public boolean ok() { return durationSec > 0 || (width > 0 && height > 0); }
    }

    private Mp4() {}

    public static Info parse(byte[] b) {
        Info out = new Info();
        if (b == null || b.length < 16) return out;
        // 1) recorrido por cajas de primer nivel (buffer de cabecera)
        walkTop(b, out);
        // 2) si no aparecio moov, busqueda por firma (buffer de cola)
        if (!out.hasMoov) scanSignatures(b, out);
        if (out.durationSec > 86400L * 30) out.durationSec = -1; // disparo: dato corrupto
        return out;
    }

    private static void walkTop(byte[] b, Info out) {
        long off = 0;
        long len = b.length;
        while (off + 8 <= len) {
            long size = u32(b, off);
            String type = str(b, off + 4);
            long hdr = 8;
            if (size == 1) {
                if (off + 16 > len) break;
                size = u64(b, off + 8);
                hdr = 16;
            } else if (size == 0) {
                size = len - off;
            }
            if (size < hdr || off + size > len) break;
            if ("moov".equals(type)) {
                out.hasMoov = true;
                scanChildren(b, off + hdr, off + size, out);
                return;
            }
            off += size;
        }
    }

    /** Analiza las cajas hijas de un rango [from,to) buscando mvhd y tkhd. */
    private static void scanChildren(byte[] b, long from, long to, Info out) {
        long off = from;
        while (off + 8 <= to) {
            long size = u32(b, off);
            String type = str(b, off + 4);
            long hdr = 8;
            if (size == 1) {
                if (off + 16 > to) break;
                size = u64(b, off + 8);
                hdr = 16;
            } else if (size == 0) {
                size = to - off;
            }
            if (size < hdr || off + size > to) break;
            if ("mvhd".equals(type)) {
                long p = off + hdr; // inicio del payload
                int ver = b[(int) p] & 0xFF;
                long timescale = -1, duration = -1;
                if (ver == 1 && p + 28 + 8 <= off + size) {
                    timescale = u32(b, p + 20);
                    duration = u64(b, p + 24);
                } else if (p + 20 <= off + size) {
                    timescale = u32(b, p + 12);
                    duration = u32(b, p + 16);
                }
                if (timescale > 0 && timescale <= 1000000 && duration > 0) {
                    long sec = duration / timescale;
                    if (sec > 0 && sec < 86400L * 30) out.durationSec = sec;
                }
            } else if ("tkhd".equals(type)) {
                long size2 = size;
                // caja tkhd v0 = 92 bytes (payload 84); width/height (16.16 fijo)
                // son los ultimos 8 bytes de la caja
                if (size2 >= 92 && size2 < 4096) {
                    int w = (int) ((u32(b, off + size2 - 8)) >>> 16);
                    int h = (int) ((u32(b, off + size2 - 4)) >>> 16);
                    if (w > 16 && h > 16 && w <= 8192 && h <= 8192) {
                        out.width = w;
                        out.height = h;
                    }
                }
            } else if ("trak".equals(type)) {
                scanChildren(b, off + hdr, off + size, out);
            }
            off += size;
        }
    }

    /** Busca firmas mvhd/tkhd/moov en un fragmento arbitrario (cola del archivo). */
    private static void scanSignatures(byte[] b, Info out) {
        for (int i = 0; i + 4 < b.length - 4; i++) {
            if (b[i] == 'm' && b[i + 1] == 'o' && b[i + 2] == 'o' && b[i + 3] == 'v') {
                out.hasMoov = true;
                scanChildren(b, i + 4, b.length, out);
                return;
            }
        }
        // mvhd sin moov visible (moov truncado en el fragmento)
        for (int i = 0; i + 28 < b.length; i++) {
            if (b[i] == 'm' && b[i + 1] == 'v' && b[i + 2] == 'h' && b[i + 3] == 'd') {
                int ver = b[i + 4] & 0xFF;
                long timescale = -1, duration = -1;
                if (ver == 1 && i + 36 <= b.length) {
                    timescale = u32(b, i + 24);
                    duration = u64(b, i + 28);
                } else if (i + 24 <= b.length) {
                    timescale = u32(b, i + 16);
                    duration = u32(b, i + 20);
                }
                if (timescale > 0 && timescale <= 1000000 && duration > 0) {
                    long sec = duration / timescale;
                    if (sec > 0 && sec < 86400L * 30) out.durationSec = sec;
                }
                return;
            }
        }
    }

    private static long u32(byte[] b, long off) {
        int i = (int) off;
        if (i < 0 || i + 4 > b.length) return -1;
        return ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16)
                | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL);
    }

    private static long u64(byte[] b, long off) {
        int i = (int) off;
        if (i < 0 || i + 8 > b.length) return -1;
        long v = 0;
        for (int k = 0; k < 8; k++) v = (v << 8) | (b[i + k] & 0xFFL);
        return v;
    }

    private static String str(byte[] b, long off) {
        int i = (int) off;
        if (i < 0 || i + 4 > b.length) return "";
        StringBuilder sb = new StringBuilder(4);
        for (int k = 0; k < 4; k++) {
            char c = (char) (b[i + k] & 0xFF);
            sb.append(c);
        }
        return sb.toString();
    }
}
