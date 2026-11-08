package com.turbodl.util;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Analizador de manifiestos DASH (MPD) compartido por el descargador y el
 * sondeo de medios. Extrae duracion, representaciones de video/audio con
 * resolucion y bitrate, plantillas de segmentos y deteccion de DRM.
 */
public final class Mpd {

    public static class Rep {
        public String id = "";
        public String mimeType = "";
        public String codecs = "";
        public long bandwidth;
        public int width, height;
        public long timescale;
        public long startNumber = 1;
        public String initTpl = null;   // $RepresentationID$ etc. sin rellenar
        public String mediaTpl = null;
        public ArrayList<Long> times = new ArrayList<Long>(); // duraciones por segmento (unidades timescale)
        public String baseUrl = null;
        public ArrayList<String> segUrls = new ArrayList<String>();

        public double durationSec() {
            if (times.isEmpty() || timescale <= 0) return -1;
            long t = 0;
            for (Long d : times) t += d;
            return t / (double) timescale;
        }
        public String label() {
            if (height > 0) return height + "p";
            return (bandwidth / 1000) + " kbps";
        }
    }

    public static class Info {
        public double durationSec = -1;
        public boolean drm;
        public ArrayList<Rep> video = new ArrayList<Rep>();
        public ArrayList<Rep> audio = new ArrayList<Rep>();
        public ArrayList<Rep> other = new ArrayList<Rep>();
    }

    private Mpd() {}

    public static Info parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Element doc = f.newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes("UTF-8"))).getDocumentElement();

        Info out = new Info();
        out.durationSec = isoDuration(doc.getAttribute("mediaPresentationDuration"));
        if (doc.getElementsByTagNameNS("*", "ContentProtection").getLength() > 0) out.drm = true;

        NodeList periods = doc.getElementsByTagNameNS("*", "Period");
        if (periods.getLength() == 0) return out;

        double periodDur = 0;
        boolean havePeriodDur = false;
        for (int pi = 0; pi < periods.getLength(); pi++) {
            Element period = (Element) periods.item(pi);
            double pd = isoDuration(period.getAttribute("duration"));
            if (pd > 0) { periodDur += pd; havePeriodDur = true; }

            NodeList sets = period.getElementsByTagNameNS("*", "AdaptationSet");
            for (int i = 0; i < sets.getLength(); i++) {
                Element aset = (Element) sets.item(i);
                String asetMime = aset.getAttribute("mimeType");
                NodeList reps = aset.getElementsByTagNameNS("*", "Representation");
                for (int j = 0; j < reps.getLength(); j++) {
                    Element r = (Element) reps.item(j);
                    Rep rep = buildRep(aset, asetMime, r);
                    if (rep == null) continue;
                    if (rep.mimeType.startsWith("video")) out.video.add(rep);
                    else if (rep.mimeType.startsWith("audio")) out.audio.add(rep);
                    else out.other.add(rep);
                }
            }
        }
        if (out.durationSec <= 0 && havePeriodDur) out.durationSec = periodDur;
        return out;
    }

    private static Rep buildRep(Element aset, String asetMime, Element r) {
        Rep rep = new Rep();
        rep.id = r.getAttribute("id");
        if (rep.id.isEmpty()) return null;
        String mime = r.getAttribute("mimeType");
        if (mime.isEmpty()) mime = asetMime;
        if (mime.isEmpty()) {
            String ct = aset.getAttribute("contentType");
            if (!ct.isEmpty()) mime = ct;
        }
        if (mime.isEmpty()) mime = "video/mp4"; // suposicion conservadora
        rep.mimeType = mime;
        rep.codecs = r.getAttribute("codecs").isEmpty() ? aset.getAttribute("codecs") : r.getAttribute("codecs");
        rep.bandwidth = parseL(r.getAttribute("bandwidth"));
        rep.width = (int) parseL(r.getAttribute("width"));
        rep.height = (int) parseL(r.getAttribute("height"));
        if (rep.height == 0) rep.height = (int) parseL(aset.getAttribute("height"));
        if (rep.width == 0) rep.width = (int) parseL(aset.getAttribute("width"));
        rep.timescale = parseL(firstAttr(r, aset, "timescale"));
        rep.baseUrl = text(aset, "BaseURL");
        if (rep.baseUrl == null) rep.baseUrl = text(r, "BaseURL");

        Element stpl = first(r, "SegmentTemplate");
        if (stpl == null) stpl = first(aset, "SegmentTemplate");
        if (stpl != null) {
            if (rep.timescale == 0) rep.timescale = parseL(stpl.getAttribute("timescale"));
            String init = stpl.getAttribute("initialization");
            if (!init.isEmpty()) rep.initTpl = init;
            String media = stpl.getAttribute("media");
            if (!media.isEmpty()) rep.mediaTpl = media;
            rep.startNumber = parseL(stpl.getAttribute("startNumber"));
            if (rep.startNumber <= 0) rep.startNumber = 1;
            NodeList tl = stpl.getElementsByTagNameNS("*", "S");
            for (int k = 0; k < tl.getLength(); k++) {
                Element s = (Element) tl.item(k);
                long d = parseL(s.getAttribute("d"));
                if (d <= 0) continue;
                long repCnt = parseL(s.getAttribute("r"));
                for (long m = 0; m <= repCnt; m++) rep.times.add(d);
            }
        } else {
            Element slist = first(r, "SegmentList");
            if (slist == null) slist = first(aset, "SegmentList");
            if (slist != null) {
                Element initEl = first(slist, "Initialization");
                if (initEl != null) rep.initTpl = initEl.getAttribute("sourceURL");
                NodeList segs = slist.getElementsByTagNameNS("*", "SegmentURL");
                for (int k = 0; k < segs.getLength(); k++) {
                    String u = ((Element) segs.item(k)).getAttribute("media");
                    if (!u.isEmpty()) rep.segUrls.add(u);
                }
            }
        }
        return rep;
    }

    /** Duracion ISO8601 (PT1H2M3.5S, P1DT2H...) en segundos; -1 si no parsea. */
    public static double isoDuration(String s) {
        if (s == null || s.isEmpty() || !s.startsWith("P")) return -1;
        try {
            boolean time = false;
            double days = 0, hours = 0, mins = 0, secs = 0;
            StringBuilder num = new StringBuilder();
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == 'T') { time = true; continue; }
                if (Character.isDigit(c) || c == '.' || c == ',') {
                    num.append(c == ',' ? '.' : c);
                    continue;
                }
                double v = num.length() > 0 ? Double.parseDouble(num.toString()) : 0;
                num.setLength(0);
                if (c == 'D' && !time) days = v;
                else if (c == 'H' && time) hours = v;
                else if (c == 'M' && time) mins = v;
                else if (c == 'S' && time) secs = v;
                else if (c == 'M' && !time) { /* meses: ignorar */ }
                else if (c == 'Y') { /* anos: ignorar */ }
                else if (c == 'W' && !time) days += v * 7;
            }
            return days * 86400 + hours * 3600 + mins * 60 + secs;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long parseL(String s) {
        try { return (long) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String firstAttr(Element a, Element b, String name) {
        String v = a.getAttribute(name);
        if (v.isEmpty()) v = b.getAttribute(name);
        return v;
    }

    private static Element first(Element parent, String name) {
        NodeList nl = parent.getElementsByTagNameNS("*", name);
        return nl.getLength() > 0 ? (Element) nl.item(0) : null;
    }

    private static String text(Element parent, String name) {
        NodeList nl = parent.getElementsByTagNameNS("*", name);
        if (nl.getLength() == 0) return null;
        String t = nl.item(0).getTextContent();
        return (t == null || t.trim().isEmpty()) ? null : t.trim();
    }
}
