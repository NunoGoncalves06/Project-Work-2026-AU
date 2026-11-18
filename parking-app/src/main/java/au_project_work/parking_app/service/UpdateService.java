package au_project_work.parking_app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Server update state, read from the folder shared with the host (docker-compose.prod.yml mounts it read-only,
 * except inbox/). The only thing this class ever writes is the empty request file; the host's systemd units do
 * the work (deploy/au-parking-update.sh). Nothing is executed from Java.
 */
@Service
public class UpdateService {

    public record Status(String state, String localSha, String remoteSha, String finishedAt, String message) {
    }

    public enum Result { CREATED, BUSY, UNAVAILABLE }

    private static final int MAX_JSON_BYTES = 4096;
    private static final Duration SUCCESS_SHOWN = Duration.ofMinutes(10);
    // a run that never wrote its end (power loss): after this it no longer blocks a new request
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(30);
    // the host consumes the request within seconds; an older one was never picked up and must not block for ever
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final JsonMapper json = JsonMapper.builder().build();
    private final Path dir;
    private final Path inbox;

    public UpdateService(@Value("${admin.update.dir:/var/lib/au-parking-update}") String dir) {
        this.dir = Path.of(dir);
        this.inbox = this.dir.resolve("inbox");
    }

    public Status status() {
        if (!Files.isDirectory(inbox)) {
            return new Status("Unavailable", null, null, null, null);
        }
        JsonNode st = read("status.json");
        JsonNode remote = read("remote.json");
        String run = text(st, "state");
        Instant started = time(st, "startedAt");
        Instant finished = time(st, "finishedAt");
        boolean stale = started != null && started.isBefore(Instant.now().minus(RUN_TIMEOUT));
        if (requestPending() || ("updating".equals(run) && !stale)) {
            return new Status("Updating", null, null, null, null);
        }
        String message = clean(text(st, "message"));
        if ("error".equals(run) || "updating".equals(run)) { // an old "updating" is a run that died
            return new Status("ErrorUpdating", sha(text(st, "sha")), null, iso(finished), message);
        }
        String local = sha(text(remote, "localSha"));
        String target = sha(text(remote, "remoteSha"));
        Instant checked = time(remote, "checkedAt");
        boolean success = "success".equals(run) && finished != null;
        // the check runs every 5 minutes; a finished update is newer than its last answer
        if (success && (checked == null || finished.isAfter(checked)) && sha(text(st, "sha")) != null) {
            local = sha(text(st, "sha"));
        }
        if (success && finished.isAfter(Instant.now().minus(SUCCESS_SHOWN))) {
            return new Status("UpdateSuccessful", local, target, iso(finished), message);
        }
        if (local == null || target == null) {
            return new Status("Unavailable", local, target, null, "Update status unknown");
        }
        return new Status(local.equals(target) ? "Updated" : "Outdated", local, target, success ? iso(finished) : null, null);
    }

    /** Creates the request file; create-new, so two clicks cannot both succeed. */
    public Result request() {
        if (!Files.isDirectory(inbox)) {
            return Result.UNAVAILABLE;
        }
        if ("Updating".equals(status().state())) {
            return Result.BUSY;
        }
        try {
            Files.deleteIfExists(inbox.resolve("request")); // only a stale one gets here: a fresh one made status() say Updating
            Files.createFile(inbox.resolve("request"));
            return Result.CREATED;
        } catch (FileAlreadyExistsException e) {
            return Result.BUSY;
        } catch (IOException | SecurityException e) {
            return Result.UNAVAILABLE;
        }
    }

    private boolean requestPending() {
        try {
            return Files.getLastModifiedTime(inbox.resolve("request")).toInstant().isAfter(Instant.now().minus(REQUEST_TIMEOUT));
        } catch (IOException e) {
            return false;
        }
    }

    // missing, oversized, garbled or not an object: all read as "no data"
    private JsonNode read(String name) {
        try (InputStream in = Files.newInputStream(dir.resolve(name))) {
            byte[] bytes = in.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) {
                return null;
            }
            JsonNode node = json.readTree(bytes);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static Instant time(JsonNode node, String field) {
        try {
            String s = text(node, field);
            return s == null ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String iso(Instant t) {
        return t == null ? null : t.toString();
    }

    private static String sha(String s) {
        return s != null && s.matches("[0-9a-f]{40}") ? s : null;
    }

    // shown in the dashboard: short, printable, nothing else
    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String printable = s.replaceAll("[^\\p{Print}]", "_");
        return printable.substring(0, Math.min(printable.length(), 100));
    }
}
