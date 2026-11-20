package au_project_work.parking_app;

import au_project_work.parking_app.service.UpdateService;
import au_project_work.parking_app.service.UpdateService.Result;
import au_project_work.parking_app.service.UpdateService.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTests {

    private static final String A = "a".repeat(40);
    private static final String B = "b".repeat(40);

    @TempDir
    Path dir;
    UpdateService service;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectory(dir.resolve("inbox"));
        service = new UpdateService(dir.toString());
    }

    private void file(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    private void remote(String local, String remote) throws IOException {
        file("remote.json", "{\"localSha\":\"" + local + "\",\"remoteSha\":\"" + remote + "\",\"checkedAt\":\"" + Instant.now() + "\"}");
    }

    private void status(String state, String sha, Instant started, Instant finished, String message) throws IOException {
        file("status.json", "{\"state\":\"" + state + "\",\"sha\":\"" + sha + "\",\"previousSha\":\"\",\"startedAt\":\"" + started
                + "\",\"finishedAt\":\"" + (finished == null ? "" : finished) + "\",\"message\":\"" + message + "\"}");
    }

    @Test
    void updatedWhenShasMatchAndOutdatedWhenNot() throws IOException {
        remote(A, A);
        assertEquals("Updated", service.status().state());
        remote(A, B);
        Status s = service.status();
        assertEquals("Outdated", s.state());
        assertEquals(A, s.localSha());
        assertEquals(B, s.remoteSha());
    }

    @Test
    void updatingWhileTheRunIsActiveOrARequestWaits() throws IOException {
        remote(A, B);
        status("updating", "", Instant.now(), null, "Waiting for GitHub to finish building the new version");
        Status running = service.status();
        assertEquals("Updating", running.state());
        assertEquals(A, running.localSha()); // the versions stay known while it runs
        assertEquals(B, running.remoteSha());
        assertEquals("Waiting for GitHub to finish building the new version", running.message());
        file("status.json", "");
        Files.createFile(dir.resolve("inbox/request"));
        Status pending = service.status();
        assertEquals("Updating", pending.state());
        assertEquals(A, pending.localSha());
        assertEquals(null, pending.message()); // no run yet: nothing to report
    }

    @Test
    void recentSuccessIsUpdateSuccessfulThenFallsBackToTheShaComparison() throws IOException {
        remote(A, B);
        file("remote.json", Files.readString(dir.resolve("remote.json")).replaceAll("\"checkedAt\":\"[^\"]*\"", "\"checkedAt\":\"" + Instant.now().minusSeconds(120) + "\"")); // the check ran before the update finished
        status("success", B, Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), "Update successful");
        Status s = service.status();
        assertEquals("UpdateSuccessful", s.state());
        assertEquals(B, s.localSha());
        Instant old = Instant.now().minus(20, ChronoUnit.MINUTES);
        status("success", B, old.minusSeconds(60), old, "Update successful");
        remote(B, B);
        assertEquals("Updated", service.status().state());
    }

    @Test
    void failedRunIsErrorUpdatingWithItsMessage() throws IOException {
        remote(A, B);
        status("error", B, Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), "Update failed, rolled back");
        Status s = service.status();
        assertEquals("ErrorUpdating", s.state());
        assertEquals("Update failed, rolled back", s.message());
    }

    @Test
    void aRunThatNeverFinishedStopsBlockingAfterHalfAnHour() throws IOException {
        status("updating", "", Instant.now().minus(2, ChronoUnit.HOURS), null, "Update started");
        assertEquals("ErrorUpdating", service.status().state());
        assertEquals(Result.CREATED, service.request());
    }

    @Test
    void missingOrGarbledFilesAreTolerated() throws IOException {
        assertEquals("Unavailable", service.status().state()); // inbox exists, no files
        file("status.json", "{not json");
        file("remote.json", "[1,2");
        assertEquals("Unavailable", service.status().state());
        file("remote.json", "{\"localSha\":\"<script>\",\"remoteSha\":5}");
        Status s = service.status();
        assertEquals("Unavailable", s.state());
        assertNull(s.localSha());
        file("status.json", "x".repeat(10_000)); // over the size limit
        assertEquals("Unavailable", service.status().state());
    }

    @Test
    void aRequestNobodyPickedUpIsIgnoredAfterFiveMinutes() throws IOException {
        remote(A, A);
        Path req = Files.createFile(dir.resolve("inbox/request"));
        Files.setLastModifiedTime(req, java.nio.file.attribute.FileTime.from(Instant.now().minus(10, ChronoUnit.MINUTES)));
        assertEquals("Updated", service.status().state());
        assertEquals(Result.CREATED, service.request());
        assertEquals(Result.BUSY, service.request()); // the new one is fresh
    }

    @Test
    void requestCreatesTheFileOnceThenIsBusy() {
        assertEquals(Result.CREATED, service.request());
        assertTrue(Files.exists(dir.resolve("inbox/request")));
        assertEquals(Result.BUSY, service.request());
    }


    @Test
    void failedRunShowsWhatRunsAndWhatIsOnMainNotTheTarget() throws IOException {
        remote(A, B); // server runs A, main is B; the failed run targeted B
        status("error", B, Instant.now().minusSeconds(60), Instant.now().minusSeconds(30), "New version not built yet");
        Status s = service.status();
        assertEquals(A, s.localSha());
        assertEquals(B, s.remoteSha());
    }

    @Test
    void requestInsideTheCooldownIsRefusedWithTheEndTime() throws IOException {
        Instant finished = Instant.now().minusSeconds(60);
        status("error", B, finished.minusSeconds(60), finished, "x");
        assertEquals(Result.COOLDOWN, service.request());
        assertTrue(service.cooldownEnd().isAfter(Instant.now()));
        assertFalse(Files.exists(dir.resolve("inbox/request")));
        status("error", B, finished.minusSeconds(600), Instant.now().minusSeconds(400), "x");
        assertNull(service.cooldownEnd());
        assertEquals(Result.CREATED, service.request());
    }

    @Test
    void logIsEmptyWhenMissingTailedToTwoHundredLinesAndPrintable() throws IOException {
        assertEquals("", service.log());
        StringBuilder sb = new StringBuilder("old\n");
        for (int i = 1; i <= 250; i++) sb.append("line ").append(i).append('\n');
        file("last-run.log", sb.toString());
        String[] lines = service.log().split("\n", -1);
        assertEquals(200, lines.length);
        assertEquals("line 51", lines[0]);
        file("last-run.log", "a\u001b[31mb\tc\r\nd\u0000");
        assertEquals("a[31mb\tc\nd", service.log());
    }

    @Test
    void logReadIsLimitedToTheLastBytes() throws IOException {
        file("last-run.log", "first-line-gone\n" + "x".repeat(300 * 1024) + "\nlast-line");
        String log = service.log();
        assertTrue(log.length() <= 256 * 1024);
        assertTrue(log.endsWith("last-line"));
        assertFalse(log.contains("first-line-gone"));
    }
    @Test
    void missingFolderIsUnavailable() {
        UpdateService none = new UpdateService(dir.resolve("nope").toString());
        assertEquals("Unavailable", none.status().state());
        assertEquals(Result.UNAVAILABLE, none.request());
    }

    @Test
    void twoSimultaneousRequestsCreateOnlyOne() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        var a = pool.submit(() -> { go.await(); return service.request(); });
        var b = pool.submit(() -> { go.await(); return service.request(); });
        go.countDown();
        var results = java.util.List.of(a.get(), b.get());
        pool.shutdown();
        assertEquals(1, results.stream().filter(r -> r == Result.CREATED).count());
        assertEquals(1, results.stream().filter(r -> r == Result.BUSY).count());
    }
}
