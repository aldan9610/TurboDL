import java.io.File;
import java.io.FileOutputStream;

/** Prueba JVM de la extraccion de audio (Merge.extractAudio core). */
public class AudioTest {
    public static void main(String[] args) throws Exception {
        String in = args[0], out = args[1];
        org.mp4parser.muxer.Movie mv =
            org.mp4parser.muxer.container.mp4.MovieCreator.build(in);
        org.mp4parser.muxer.Movie audio = new org.mp4parser.muxer.Movie();
        int n = 0;
        for (org.mp4parser.muxer.Track t : mv.getTracks()) {
            if ("soun".equals(t.getHandler())) { audio.addTrack(t); n++; }
        }
        if (n == 0) { System.out.println("SIN_AUDIO"); return; }
        org.mp4parser.Container c =
            new org.mp4parser.muxer.builder.DefaultMp4Builder().build(audio);
        FileOutputStream fos = new FileOutputStream(out);
        c.writeContainer(fos.getChannel());
        fos.close();
        System.out.println("pistas_audio=" + n + " bytes=" + new File(out).length());
    }
}
