// A transport/skeleton check only. This is NOT business or database readiness.
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

class HealthProbe {
    public static void main(String[] args) {
        try {
            var connection = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:8080/api/v1/health").toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            try {
                if (connection.getResponseCode() != 200) {
                    System.exit(1);
                }
                String body;
                try (var input = connection.getInputStream()) {
                    byte[] bytes = input.readNBytes(4097);
                    if (bytes.length > 4096) {
                        System.exit(1);
                    }
                    body = new String(bytes, StandardCharsets.UTF_8);
                }
                if (!isSkeleton(body)) {
                    System.exit(1);
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // Do not emit response bodies, environment values or stack traces.
            System.exit(1);
        }
    }

    private static boolean isSkeleton(String body) {
        String json = body.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            return false;
        }
        String[] fields = json.substring(1, json.length() - 1).split(",", -1);
        if (fields.length != 3) {
            return false;
        }
        var found = new java.util.HashSet<String>();
        for (String field : fields) {
            if (Pattern.matches("\\s*\\\"status\\\"\\s*:\\s*\\\"UP\\\"\\s*", field)) {
                found.add("status");
            } else if (Pattern.matches("\\s*\\\"stage\\\"\\s*:\\s*\\\"SKELETON\\\"\\s*", field)) {
                found.add("stage");
            } else if (Pattern.matches("\\s*\\\"businessReady\\\"\\s*:\\s*false\\s*", field)) {
                found.add("businessReady");
            } else {
                return false;
            }
        }
        return found.size() == 3;
    }
}
