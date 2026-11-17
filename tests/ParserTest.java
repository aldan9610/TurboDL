import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/** Prueba JVM de Mp4.java, M3u.java y Mpd.java. */
public class ParserTest {

    static int fails = 0;

    static void check(String name, boolean cond, String detail) {
        System.out.println((cond ? "PASS  " : "FAIL  ") + name + (cond ? "" : "  -> " + detail));
        if (!cond) fails++;
    }

    static byte[] read(String p) throws Exception {
        FileInputStream in = new FileInputStream(p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    static byte[] head(byte[] b, int n) {
        byte[] o = new byte[Math.min(n, b.length)];
        System.arraycopy(b, 0, o, 0, o.length);
        return o;
    }

    static byte[] tail(byte[] b, int n) {
        n = Math.min(n, b.length);
        byte[] o = new byte[n];
        System.arraycopy(b, b.length - n, o, 0, n);
        return o;
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "/tmp/mp4tests";

        // ---- Mp4: moov en cabecera (buffer completo truncado a 256 KB) ----
        byte[] h = read(dir + "/head.mp4");
        com.turbodl.util.Mp4.Info i1 = com.turbodl.util.Mp4.parse(head(h, 262144));
        check("mp4 head duracion=3723s", i1.durationSec == 3723, "" + i1.durationSec);
        check("mp4 head res=426x240", i1.width == 426 && i1.height == 240, i1.width + "x" + i1.height);
        check("mp4 head moov", i1.hasMoov, "no moov");

        // ---- Mp4: moov al final (cola 512 KB) ----
        byte[] t = read(dir + "/tail.mp4");
        com.turbodl.util.Mp4.Info i2 = com.turbodl.util.Mp4.parse(tail(t, 524288));
        check("mp4 tail duracion=7322s", i2.durationSec == 7322, "" + i2.durationSec);
        check("mp4 tail res=1280x720", i2.width == 1280 && i2.height == 720, i2.width + "x" + i2.height);

        // ---- Mp4: basura sin moov ----
        com.turbodl.util.Mp4.Info i3 = com.turbodl.util.Mp4.parse(new byte[4096]);
        check("mp4 vacio -> sin datos", i3.durationSec == -1 && i3.width == 0, "invento");

        // ---- M3u: master con variantes, audio separado ----
        String master = String.join("\n",
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360,CODECS=\"avc1.640029,mp4a.40.2\"",
            "v360.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=4128000,RESOLUTION=1920x1080",
            "v1080.m3u8",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Spanish\",URI=\"audio/es.m3u8\"");
        com.turbodl.util.M3u.Parsed pm = com.turbodl.util.M3u.parse("https://cdn.ej.com/x/master.m3u8", master);
        check("m3u master variantes=2", pm.master && pm.variants.size() == 2, "" + pm.variants.size());
        check("m3u master audio", pm.audioUri != null && pm.audioUri.equals("https://cdn.ej.com/x/audio/es.m3u8"), "" + pm.audioUri);
        check("m3u v0 label=360p", pm.variants.get(0).label().equals("360p"), pm.variants.get(0).label());
        check("m3u v1 res", pm.variants.get(1).width == 1920 && pm.variants.get(1).height == 1080, "");

        // ---- M3u: lista de medios con EXTINF + BYTERANGE + KEY + MAP ----
        String media = String.join("\n",
            "#EXTM3U",
            "#EXT-X-VERSION:4",
            "#EXT-X-TARGETDURATION:6",
            "#EXT-X-MEDIA-SEQUENCE:0",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x9c7db8778570d05c3177c349fd9236aa",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXTINF:5.005,",
            "#EXT-X-BYTERANGE:250000@500",
            "seg0.m4s",
            "#EXTINF:4.995,",
            "#EXT-X-BYTERANGE:220000@250500",
            "seg1.m4s",
            "#EXT-X-ENDLIST");
        com.turbodl.util.M3u.Parsed pe = com.turbodl.util.M3u.parse("https://cdn.ej.com/x/v1080.m3u8", media);
        check("m3u media entradas=2", !pe.master && pe.entries.size() == 2, "" + pe.entries.size());
        check("m3u media duracion=10.0", Math.abs(pe.durationSec - 10.0) < 0.01, "" + pe.durationSec);
        check("m3u media key", pe.entries.get(0).keyUri != null && pe.entries.get(0).keyUri.endsWith("key.bin"), "" + pe.entries.get(0).keyUri);
        check("m3u media map", pe.mapUri != null && pe.mapUri.endsWith("init.mp4"), "" + pe.mapUri);
        check("m3u media byterange", pe.byterangeTotal == 470000, "" + pe.byterangeTotal);
        check("m3u media gidx", pe.entries.get(1).gidx == 1, "" + pe.entries.get(1).gidx);
        check("m3u media iv", pe.entries.get(0).iv != null && pe.entries.get(0).iv.contains("9c7db877"), "" + pe.entries.get(0).iv);
        check("m3u media endlist", pe.endList, "");

        // ---- Mpd: video 2 calidades + audio, SegmentTemplate con timeline ----
        String mpd = String.join("\n",
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" mediaPresentationDuration=\"PT1H2M3S\" type=\"static\">",
            "  <Period>",
            "    <AdaptationSet mimeType=\"video/mp4\">",
            "      <SegmentTemplate timescale=\"90000\" initialization=\"$RepresentationID$/init.mp4\" media=\"$RepresentationID$/seg-$Number$.m4s\" startNumber=\"1\">",
            "        <SegmentTimeline><S d=\"5400000\" r=\"99\"/></SegmentTimeline>",
            "      </SegmentTemplate>",
            "      <Representation id=\"v240\" bandwidth=\"300000\" width=\"426\" height=\"240\" codecs=\"avc1.64000d\"/>",
            "      <Representation id=\"v1080\" bandwidth=\"4128000\" width=\"1920\" height=\"1080\" codecs=\"avc1.640029\"/>",
            "    </AdaptationSet>",
            "    <AdaptationSet mimeType=\"audio/mp4\">",
            "      <Representation id=\"a128\" bandwidth=\"128000\" codecs=\"mp4a.40.2\"/>",
            "    </AdaptationSet>",
            "  </Period>",
            "</MPD>");
        com.turbodl.util.Mpd.Info md = com.turbodl.util.Mpd.parse(mpd);
        check("mpd duracion=3723s", Math.abs(md.durationSec - 3723) < 1, "" + md.durationSec);
        check("mpd video reps=2", md.video.size() == 2, "" + md.video.size());
        check("mpd audio reps=1", md.audio.size() == 1, "" + md.audio.size());
        check("mpd rep label=240p", md.video.get(0).label().equals("240p"), md.video.get(0).label());
        check("mpd rep timeline dur", Math.abs(md.video.get(0).durationSec() - 5400000.0 * 100 / 90000) < 1, "" + md.video.get(0).durationSec());
        check("mpd sin drm", !md.drm, "");

        // ---- Mpd: DRM detectado ----
        String mpdDrm = mpd.replace("<AdaptationSet mimeType=\"video/mp4\">",
            "<AdaptationSet mimeType=\"video/mp4\"><ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\"/>");
        com.turbodl.util.Mpd.Info md2 = com.turbodl.util.Mpd.parse(mpdDrm);
        check("mpd drm detectado", md2.drm, "");

        System.out.println(fails == 0 ? "\nTODO OK" : "\n" + fails + " FALLOS");
        System.exit(fails == 0 ? 0 : 1);
    }
}
