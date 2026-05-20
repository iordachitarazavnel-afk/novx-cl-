import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ReadTiny {
    public static void main(String[] args) throws Exception {
        String path = "C:\\Users\\dd_au\\.gradle\\caches\\fabric-loom\\1.21.11\\loom.mappings.1_21_11.layered+hash.2198-v2\\mappings.tiny";
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        for(String line : lines) {
            if(line.contains("class_11905")) {
                System.out.println(line);
            }
        }
    }
}
