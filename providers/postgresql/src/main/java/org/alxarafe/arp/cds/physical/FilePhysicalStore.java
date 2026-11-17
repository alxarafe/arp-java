package org.alxarafe.arp.cds.physical;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Host-owned local filesystem store. Atomic YAML replacement; durable invalidation state under an OS lock. */
public final class FilePhysicalStore
{
    @FunctionalInterface public interface Observe { Map<String, Object> get() throws ObservationException; }
    private record State(String generation, String key, String digest, boolean valid) { }
    private final Path root;
    private final List<Path> sources;
    private final List<Path> cacheRoots;
    private final ObjectMapper json = new ObjectMapper();

    public FilePhysicalStore(Path directory, List<Path> versionedSourceRoots) throws ObservationException
    {
        this(directory, versionedSourceRoots, List.of());
    }
    public FilePhysicalStore(Path directory, List<Path> versionedSourceRoots, List<Path> authorizedCacheRoots)
            throws ObservationException
    {
        try {
            if (!directory.isAbsolute() || versionedSourceRoots.isEmpty()) throw new ObservationException();
            root = directory.toRealPath();
            if (!directory.normalize().equals(root)) throw new ObservationException();
            var caches = new java.util.ArrayList<Path>();
            for (Path p : authorizedCacheRoots) {
                Path real = p.toRealPath();
                if (!p.isAbsolute() || !p.normalize().equals(real)) throw new ObservationException();
                caches.add(real);
            }
            cacheRoots = List.copyOf(caches);
            sources = versionedSourceRoots.stream().map(p -> {
                try { return p.toRealPath(); } catch (IOException e) { throw new IllegalArgumentException(e); }
            }).toList();
            checkRoot();
        } catch (IOException e) { throw new ObservationException(); }
    }
    public Path fileFor(PhysicalStoreKey key) { return root.resolve(key.hash() + ".physical.yaml"); }

    public Map<String, Object> get(PhysicalStoreKey key, Observe observe) throws ObservationException
    {
        String generation;
        try {
            synchronized (FilePhysicalStore.class) {
                try (var file = channel(key); var lock = file.lock()) {
                    State state = readState(file, key);
                    if (state.valid() && Files.exists(fileFor(key))) {
                        try {
                            checkFile(fileFor(key));
                            if (Files.size(fileFor(key)) > 1048576) throw new ObservationException();
                            byte[] data = Files.readAllBytes(fileFor(key));
                            if (!PhysicalStoreKey.digest(data).equals(state.digest())) throw new ObservationException();
                            return PhysicalYaml.decode(data, key);
                        } catch (ObservationException e) { save(file, invalid(key)); throw e; }
                    }
                    if (state.valid()) { state = invalid(key); save(file, state); }
                    generation = state.generation();
                }
            }
            var value = observe.get(); byte[] yaml = PhysicalYaml.encode(value, key);
            synchronized (FilePhysicalStore.class) {
                try (var file = channel(key); var lock = file.lock()) {
                    if (!readState(file, key).generation().equals(generation)) throw new ObservationException();
                    atomicWrite(fileFor(key), yaml);
                    save(file, new State(generation, key.hash(), PhysicalStoreKey.digest(yaml), true));
                }
            }
            return PhysicalYaml.decode(yaml, key);
        } catch (IOException e) { throw new ObservationException(); }
    }
    public void invalidate(PhysicalStoreKey key) throws ObservationException
    {
        try {
            synchronized (FilePhysicalStore.class) {
                try (var file = channel(key); var lock = file.lock()) {
                    save(file, invalid(key)); checkFile(fileFor(key)); Files.deleteIfExists(fileFor(key));
                }
            }
        } catch (IOException e) { throw new ObservationException(); }
    }
    private FileChannel channel(PhysicalStoreKey key) throws IOException, ObservationException
    {
        checkRoot(); Path path = root.resolve(key.hash() + ".lock"); checkFile(path);
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
    private State readState(FileChannel file, PhysicalStoreKey key) throws IOException, ObservationException
    {
        if (file.size() == 0) { State initial = invalid(key); save(file, initial); return initial; }
        try {
            if (file.size() > 4096) throw new IOException();
            file.position(0); var data = ByteBuffer.allocate((int) file.size());
            while (data.hasRemaining()) if (file.read(data) < 0) throw new IOException();
            State state = json.readValue(data.array(), State.class);
            if (!key.hash().equals(state.key()) || !state.generation().matches("[0-9a-f]{32}")
                    || state.valid() && (state.digest() == null || !state.digest().matches("[0-9a-f]{64}"))) {
                throw new IOException();
            }
            return state;
        } catch (IOException | NullPointerException e) { save(file, invalid(key)); throw new ObservationException(); }
    }
    private static State invalid(PhysicalStoreKey key)
    {
        return new State(UUID.randomUUID().toString().replace("-", ""), key.hash(), null, false);
    }
    private void save(FileChannel file, State state) throws IOException
    {
        file.position(0); file.truncate(0); var bytes = ByteBuffer.wrap(json.writeValueAsBytes(state));
        while (bytes.hasRemaining()) file.write(bytes); file.force(true);
    }
    private static void atomicWrite(Path path, byte[] data) throws IOException, ObservationException
    {
        checkFile(path); Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temp,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
            try (var file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    private static void checkFile(Path path) throws ObservationException
    {
        if (Files.isSymbolicLink(path) || Files.isDirectory(path)) throw new ObservationException();
    }
    private static int git(Path repository, String... arguments) throws IOException, ObservationException
    {
        var command = new java.util.ArrayList<String>(List.of("git", "-C",
                repository.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new ObservationException();
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw new ObservationException();
        }
    }
    private void checkRoot() throws IOException, ObservationException
    {
        if (!root.toRealPath().equals(root) || sources.contains(root)) throw new ObservationException();
        Path repository = null;
        for (Path p = root; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("HEAD")) && Files.isDirectory(p.resolve("objects"))) {
                throw new ObservationException();
            }
            if (Files.exists(p.resolve(".git"))) { repository = p; break; }
        }
        var authorized = cacheRoots.isEmpty() && repository != null
                ? List.of(repository.resolve("cache/arp/physical")) : cacheRoots;
        if (sources.stream().anyMatch(root::startsWith) || repository != null || !cacheRoots.isEmpty()) {
            if (authorized.stream().noneMatch(root::startsWith)) throw new ObservationException();
        }
        if (repository != null && (git(repository, "check-ignore", "--quiet", "--no-index", "--", root + "/") != 0
                || git(repository, "--literal-pathspecs", "ls-files", "--error-unmatch", "--", root + "/") != 1)) {
            throw new ObservationException();
        }
    }
}