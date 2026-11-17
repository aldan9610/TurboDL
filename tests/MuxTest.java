import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Prueba JVM de la logica de normalizacion usada por Merge.normalize():
 * MovieCreator.build + DefaultMp4Builder sobre un MP4 fragmentado
 * (init + segmentos DASH concatenados) -> MP4 clasico indexado.
 */
public class MuxTest {
    public static void main(String[] args) throws Exception {
        String in = args[0];   // mp4 fragmentado (estilo salida antigua de TurboDL)
        String out = args[1];  // salida normalizada

        // 1) leer pistas (igual que la app)
        org.mp4parser.muxer.Movie movie =
            org.mp4parser.muxer.container.mp4.MovieCreator.build(in);
        System.out.println("pistas leidas: " + movie.getTracks().size());
        for (org.mp4parser.muxer.Track t : movie.getTracks()) {
            System.out.println("  track handler=" + t.getHandler()
                + " muestras=" + t.getSamples().size()
                + " dur=" + t.getDuration());
        }

        // 2) reconstruir (igual que la app)
        org.mp4parser.Container container =
            new org.mp4parser.muxer.builder.DefaultMp4Builder().build(movie);
        FileOutputStream fos = new FileOutputStream(out);
        container.writeContainer(fos.getChannel());
        fos.close();

        System.out.println("salida escrita: " + out + " (" + new File(out).length() + " bytes)");

        // 3) comprobar estructura: moov al inicio, sin moof/sidx
        byte[] head = new byte[4096];
        FileInputStream is = new FileInputStream(out);
        int n = is.read(head);
        is.close();
        String headStr = new String(head, 0, n, "ISO-8859-1");
        System.out.println("moov en cabecera: " + headStr.contains("moov"));
        byte[] all = new byte[(int) new File(out).length()];
        FileInputStream is2 = new FileInputStream(out);
        int got = 0;
        while (got < all.length) {
            int r = is2.read(all, got, all.length - got);
            if (r < 0) break;
            got += r;
        }
        is2.close();
        String allStr = new String(all, 0, got, "ISO-8859-1");
        System.out.println("moof restantes: " + count(allStr, "moof"));
        System.out.println("sidx restantes: " + count(allStr, "sidx"));
        System.out.println("stco (indices): " + count(allStr, "stco"));
    }

    static int count(String s, String p) {
        int c = 0, i = 0;
        while ((i = s.indexOf(p, i)) >= 0) { c++; i += p.length(); }
        return c;
    }
}
