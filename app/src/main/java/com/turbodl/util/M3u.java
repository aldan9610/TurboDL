package com.turbodl.util;

import java.util.ArrayList;

/**
 * Analizador de listas M3U/HLS compartido por el descargador HLS y el
 * sondeo de medios (duracion, tamano, calidades y pista de audio separada).
 */
public final class M3u {

    public static class Variant {
        public String url;
        public long bandwidth;
        public int width, height;
        public String codecs = "";
        public String label() {
            if (height > 0) return height + "p";
            if (bandwidth > 0) {
                long kbps = bandwidth / 1000;
                if (kbps >= 1000) return (kbps / 1000) + " Mbps";
                return kbps + " kbps";
            }
            return "variante";
        }
    }

    public static class Entry {
        public long gidx;
        public String uri;
        public String byterange; // "len@start"
        public String keyUri;    // null si sin cifrar
        public String iv;
        public double dur = 6.0;
    }

    public static class Parsed {
        public boolean master;
        public ArrayList<Variant> variants = new ArrayList<Variant>();
        public ArrayList<Entry> entries = new ArrayList<Entry>();
        public long mediaSeq;
        public int target = 6;
        public boolean endList;
        public String mapUri, mapRange;
        public String audioUri;  // grupo EXT-X-MEDIA TYPE=AUDIO con URI (resuelto)
        public String audioName = "";
        public double durationSec;   // suma de EXTINF (listas de medios)
        public long byterangeTotal = -1; // suma de BYTERANGE si esta completa
        public int width, height;
        public String codecs = "";
    }

    private M3u() {}

    public static Parsed parse(String base, String text) {
        Parsed p = new Parsed();
        String[] lines = text.split("\r?\n");
        String keyUri = null, iv = null;
        String pendingRange = null;
        double pendingDur = 6.0;
        long bw = 0;
        long uriIdx = 0;
        double total = 0;
        long brTotal = 0;
        boolean allRanged = true;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXTM3U")) continue;
            if (l.startsWith("#EXT-X-STREAM-INF:")) {
                p.master = true;
                bw = attrLong(l, "BANDWIDTH");
                int[] wh = attrRes(l, "RESOLUTION");
                if (wh != null) { p.width = wh[0]; p.height = wh[1]; }
                p.codecs = attr(l, "CODECS");
            } else if (l.startsWith("#EXT-X-TARGETDURATION:")) {
                p.target = (int) parse2(substringAfterColon(l), 6);
            } else if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                p.mediaSeq = parse2(substringAfterColon(l), 0);
            } else if (l.startsWith("#EXT-X-KEY:")) {
                if ("NONE".equals(attr(l, "METHOD"))) { keyUri = null; iv = null; }
                else if ("AES-128".equals(attr(l, "METHOD"))) {
                    String u = attr(l, "URI");
                    keyUri = (u != null) ? resolve(base, u) : null;
                    iv = attr(l, "IV");
                }
            } else if (l.startsWith("#EXT-X-MEDIA:")) {
                String t = attr(l, "TYPE");
                String u = attr(l, "URI");
                if ("AUDIO".equals(t) && u != null && !u.isEmpty()) {
                    String cand = resolve(base, u);
                    // elegir el primer grupo; algunos MPD tienen varios
                    if (p.audioUri == null) {
                        p.audioUri = cand;
                        String nm = attr(l, "NAME");
                        p.audioName = (nm != null) ? nm : "";
                    }
                }
            } else if (l.startsWith("#EXT-X-MAP:")) {
                String u = attr(l, "URI");
                if (u != null) p.mapUri = resolve(base, u);
                p.mapRange = attr(l, "BYTERANGE");
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = substringAfterColon(l).trim();
            } else if (l.startsWith("#EXT-X-ENDLIST")) {
                p.endList = true;
            } else if (l.startsWith("#")) {
                // #EXTINF:6.006,
                if (l.startsWith("#EXTINF:")) {
                    String d = substringBeforeComma(l.substring(8)).trim();
                    try { pendingDur = Double.parseDouble(d); } catch (Exception e) { pendingDur = 6.0; }
                }
                continue;
            } else {
                String u = resolve(base, l);
                if (p.master) {
                    Variant v = new Variant();
                    v.url = u;
                    v.bandwidth = bw;
                    v.width = p.width;
                    v.height = p.height;
                    v.codecs = p.codecs;
                    p.variants.add(v);
                } else {
                    Entry e = new Entry();
                    e.gidx = p.mediaSeq + uriIdx;
                    e.uri = u;
                    e.byterange = pendingRange;
                    e.keyUri = keyUri;
                    e.iv = iv;
                    e.dur = pendingDur;
                    p.entries.add(e);
                    total += e.dur;
                    if (pendingRange != null) {
                        try {
                            brTotal += Long.parseLong(substringBeforeAt(pendingRange));
                        } catch (Exception ex) { allRanged = false; }
                    } else allRanged = false;
                }
                pendingRange = null;
                pendingDur = 6.0;
                if (!p.master) uriIdx++;
            }
        }
        if (!p.master) {
            p.durationSec = total;
            p.byterangeTotal = allRanged ? brTotal : -1;
        }
        return p;
    }

    private static String substringAfterColon(String s) {
        int i = s.indexOf(':');
        return (i >= 0 && i + 1 <= s.length()) ? s.substring(i + 1) : "";
    }

    private static String substringBeforeComma(String s) {
        int i = s.indexOf(',');
        return (i >= 0) ? s.substring(0, i) : s;
    }

    private static String substringBeforeAt(String s) {
        int i = s.indexOf('@');
        return (i >= 0) ? s.substring(0, i) : s;
    }

    private static long parse2(String s, int def) {
        try { return (long) Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    public static long attrLong(String line, String name) {
        String v = attr(line, name);
        if (v == null) return 0;
        try { return (long) Double.parseDouble(v.trim()); } catch (Exception e) { return 0; }
    }

    /** Atributo TAG="valor" o TAG=valor (sin comillas) de una linea #EXT */
    public static String attr(String line, String name) {
        int i = line.indexOf(name + "=");
        if (i < 0) return null;
        int s = i + name.length() + 1;
        if (s >= line.length()) return null;
        StringBuilder sb = new StringBuilder();
        if (line.charAt(s) == '"') {
            s++;
            while (s < line.length() && line.charAt(s) != '"') { sb.append(line.charAt(s)); s++; }
        } else {
            while (s < line.length() && line.charAt(s) != ',' && line.charAt(s) != '"') {
                sb.append(line.charAt(s));
                s++;
            }
        }
        return sb.toString().replace("&amp;", "&").replace("&quot;", "\"").trim();
    }

    /** RESOLUTION=1920x1080 -> [1920,1080] */
    public static int[] attrRes(String line, String name) {
        String v = attr(line, name);
        if (v == null) return null;
        int x = v.indexOf('x');
        if (x <= 0) return null;
        try {
            int w = Integer.parseInt(v.substring(0, x).trim());
            int h = Integer.parseInt(v.substring(x + 1).trim());
            return new int[]{w, h};
        } catch (Exception e) { return null; }
    }

    public static String resolve(String base, String rel) {
        try {
            java.net.URL b = new java.net.URL(base);
            return new java.net.URL(b, rel).toString();
        } catch (Exception e) {
            return rel;
        }
    }
}
