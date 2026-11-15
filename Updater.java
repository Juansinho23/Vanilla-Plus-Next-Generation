import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sincroniza la carpeta "mods" local con la carpeta "mods" de un repositorio de GitHub.
 *
 * Se usa de dos formas:
 *  1) Como javaagent: Minecraft lo ejecuta solo, justo antes de arrancar el juego (premain).
 *  2) Desde el instalador:  java -cp updater.jar Updater <carpeta-del-modpack>   (main)
 *
 * Lee la configuración de "sync.properties" (repo y branch), que está junto al .jar.
 */
public class Updater {

    // ---------- Punto de entrada como javaagent (lo llama Java automáticamente) ----------
    public static void premain(String args, Instrumentation inst) {
        try {
            Path jar = Paths.get(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            sync(jar.getParent());
        } catch (Throwable t) {
            // Nunca dejamos que un error aquí impida abrir Minecraft.
            System.out.println("[ModSync] Error inesperado: " + t);
        }
    }

    // ---------- Punto de entrada manual (lo usa el instalador) ----------
    public static void main(String[] args) throws Exception {
        sync(Paths.get(args[0]));
    }

    // ---------- Lógica principal ----------
    static void sync(Path packDir) {
        Path logFile = packDir.resolve("sync.log");
        try {
            Properties cfg = new Properties();
            try (InputStream in = Files.newInputStream(packDir.resolve("sync.properties"))) {
                cfg.load(in);
            }
            String repo = cfg.getProperty("repo").trim();
            String branch = cfg.getProperty("branch", "main").trim();

            Path modsDir = packDir.resolve("mods");
            Files.createDirectories(modsDir);

            log(logFile, "Buscando actualizaciones en github.com/" + repo + " (" + branch + ")...");

            Map<String, String> remote = listRemoteMods(repo, branch);
            if (remote.isEmpty()) {
                log(logFile, "No se pudo leer la lista de mods (o está vacía). No se toca nada.");
                return;
            }

            // 1) Borrar mods que ya no están en GitHub o que cambiaron de versión
            int deleted = 0;
            try (Stream<Path> files = Files.list(modsDir)) {
                List<Path> local = new ArrayList<>();
                files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).forEach(local::add);
                for (Path p : local) {
                    String name = p.getFileName().toString();
                    String expected = remote.get(name);
                    if (expected == null || !expected.equals(gitSha(p))) {
                        Files.delete(p);
                        deleted++;
                        log(logFile, "Eliminado: " + name);
                    }
                }
            }

            // 2) Descargar los que faltan
            int downloaded = 0;
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            for (Map.Entry<String, String> e : remote.entrySet()) {
                String name = e.getKey();
                Path target = modsDir.resolve(name);
                if (Files.exists(target)) continue;

                String url = "https://raw.githubusercontent.com/" + repo + "/" + branch + "/mods/" + encode(name);
                Path tmp = modsDir.resolve(name + ".part");
                log(logFile, "Descargando: " + name);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "ModSync")
                        .timeout(Duration.ofMinutes(5))
                        .build();
                HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(
                        tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
                if (resp.statusCode() != 200 || !e.getValue().equals(gitSha(tmp))) {
                    Files.deleteIfExists(tmp);
                    log(logFile, "  ERROR descargando " + name + " (codigo " + resp.statusCode() + ")");
                    continue;
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                downloaded++;
            }

            log(logFile, "Listo. Descargados: " + downloaded + " | Eliminados: " + deleted);
        } catch (Throwable t) {
            log(logFile, "No se pudo actualizar (sin internet?). Se usan los mods que ya hay. Detalle: " + t);
        }
    }

    // ---------- Pide a GitHub la lista de archivos de la carpeta "mods" ----------
    static Map<String, String> listRemoteMods(String repo, String branch) throws Exception {
        String api = "https://api.github.com/repos/" + repo + "/contents/mods?ref=" + encode(branch);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(api))
                .header("User-Agent", "ModSync")
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, String> result = new LinkedHashMap<>();
        if (resp.statusCode() != 200) return result;

        Pattern p = Pattern.compile(
                "\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"path\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"");
        Matcher m = p.matcher(resp.body());
        while (m.find()) {
            String name = m.group(1);
            if (name.toLowerCase().endsWith(".jar")) result.put(name, m.group(2));
        }
        return result;
    }

    // ---------- SHA-1 "estilo git" (es el mismo valor que GitHub guarda de cada archivo) ----------
    static String gitSha(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(("blob " + Files.size(file) + "\0").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static void log(Path logFile, String msg) {
        System.out.println("[ModSync] " + msg);
        try {
            Files.writeString(logFile, msg + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
