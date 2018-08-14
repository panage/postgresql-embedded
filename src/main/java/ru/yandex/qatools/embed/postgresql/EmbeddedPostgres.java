package ru.yandex.qatools.embed.postgresql;

import de.flapdoodle.embed.process.config.IRuntimeConfig;
import de.flapdoodle.embed.process.distribution.IVersion;
import de.flapdoodle.embed.process.distribution.Platform;
import de.flapdoodle.embed.process.io.directories.FixedPath;
import de.flapdoodle.embed.process.runtime.ICommandLinePostProcessor;
import de.flapdoodle.embed.process.store.PostgresArtifactStoreBuilder;
import ru.yandex.qatools.embed.postgresql.config.AbstractPostgresConfig;
import ru.yandex.qatools.embed.postgresql.config.PostgresConfig;
import ru.yandex.qatools.embed.postgresql.config.PostgresDownloadConfigBuilder;
import ru.yandex.qatools.embed.postgresql.config.RuntimeConfigBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static ru.yandex.qatools.embed.postgresql.config.AbstractPostgresConfig.*;
import static ru.yandex.qatools.embed.postgresql.distribution.Version.Main.PRODUCTION;
import static ru.yandex.qatools.embed.postgresql.util.SocketUtil.findFreePort;

/**
 * Helper class simplifying the start up configuration for embedded postgres
 */
public class EmbeddedPostgres implements AutoCloseable {
    public static final String DEFAULT_USER = "postgres";//NOSONAR
    public static final String DEFAULT_PASSWORD = "postgres";//NOSONAR
    public static final String DEFAULT_DB_NAME = "postgres";//NOSONAR
    public static final String DEFAULT_HOST = "localhost";
    private static final List<String> DEFAULT_ADD_PARAMS = asList(
            "-E", "SQL_ASCII",
            "--locale=C",
            "--lc-collate=C",
            "--lc-ctype=C");
    private final String dataDir;
    private final IVersion version;
    private PostgresProcess process;
    private PostgresConfig config;

    public EmbeddedPostgres() {
        this(PRODUCTION);
    }

    public EmbeddedPostgres(IVersion version) {
        this(version, null);
    }

    public EmbeddedPostgres(String dataDir){
        this(PRODUCTION, dataDir);
    }

    public EmbeddedPostgres(IVersion version, String dataDir){
        this.version = version;
        this.dataDir = dataDir;
    }

    /**
     * Initializes the default runtime configuration using the temporary directory.
     *
     * @return runtime configuration required for postgres to start.
     */
    public static IRuntimeConfig defaultRuntimeConfig() {
        return new RuntimeConfigBuilder()
                .defaults(Command.Postgres)
                .artifactStore(new PostgresArtifactStoreBuilder()
                        .defaults(Command.Postgres)
                        .download(new PostgresDownloadConfigBuilder()
                                .defaultsForCommand(Command.Postgres)
                                .build()))
                .commandLinePostProcessor(privilegedWindowsRunasPostprocessor())
                .build();
    }

    private static ICommandLinePostProcessor privilegedWindowsRunasPostprocessor() {
        if (Platform.detect().equals(Platform.Windows)) {
            try {
                // Based on https://stackoverflow.com/a/11995662
                final int adminCommandResult = Runtime.getRuntime().exec("net session").waitFor();
                if (adminCommandResult == 0) {
                    return runWithoutPrivileges();
                }
            } catch (Exception e) {
                // Log maybe?
            }
        }
        return doNothing();
    }

    private static ICommandLinePostProcessor runWithoutPrivileges() {
        return (distribution, args) -> {
            if (args.size() > 0 && args.get(0).endsWith("postgres.exe")) {
                return Arrays.asList("runas", "/trustlevel:0x20000", String.format("\"%s\"", String.join(" ", args)));
            }
            return args;
        };
    }

    private static ICommandLinePostProcessor doNothing() {
        return (distribution, args) -> args;
    }

    /**
     * Initializes runtime configuration for cached directory.
     * If a provided directory is empty, postgres will be extracted into it.
     *
     * @param cachedPath path where postgres is supposed to be extracted
     * @return runtime configuration required for postgres to start
     */
    public static IRuntimeConfig cachedRuntimeConfig(Path cachedPath) {
        final Command cmd = Command.Postgres;
        final FixedPath cachedDir = new FixedPath(cachedPath.toString());
        return new RuntimeConfigBuilder()
                .defaults(cmd)
                .artifactStore(new PostgresArtifactStoreBuilder()
                        .defaults(cmd)
                        .tempDir(cachedDir)
                        .download(new PostgresDownloadConfigBuilder()
                                .defaultsForCommand(cmd)
                                .packageResolver(new PackagePaths(cmd, cachedDir))
                                .build()))
                .commandLinePostProcessor(privilegedWindowsRunasPostprocessor())
                .build();
    }

    public String start() throws IOException {
        return start(DEFAULT_HOST, findFreePort(), DEFAULT_DB_NAME);
    }

    public String start(String host, int port, String dbName) throws IOException {
        return start(host, port, dbName, DEFAULT_USER, DEFAULT_PASSWORD, DEFAULT_ADD_PARAMS);
    }

    public String start(String host, int port, String dbName, String user, String password) throws IOException {
        return start(defaultRuntimeConfig(), host, port, dbName, user, password, DEFAULT_ADD_PARAMS);
    }

    public String start(String host, int port, String dbName, String user, String password, List<String> additionalParams) throws IOException {
        return start(defaultRuntimeConfig(), host, port, dbName, user, password, additionalParams);
    }

    public String start(IRuntimeConfig runtimeConfig) throws IOException {
        return start(runtimeConfig, DEFAULT_HOST, findFreePort(), DEFAULT_DB_NAME, DEFAULT_USER, DEFAULT_PASSWORD, DEFAULT_ADD_PARAMS);
    }

    /**
     * Starts up the embedded postgres
     *
     * @param runtimeConfig    required runtime configuration
     * @param host             host to bind to
     * @param port             port to bind to
     * @param dbName           name of the database to initialize
     * @param user             username to connect
     * @param password         password for the provided username
     * @param additionalParams additional database init params (if required)
     * @return connection url for the initialized postgres instance
     * @throws IOException if an I/O error occurs during the process startup
     */
    public String start(IRuntimeConfig runtimeConfig, String host, int port, String dbName, String user, String password,
                        List<String> additionalParams) throws IOException {
        final PostgresStarter<PostgresExecutable, PostgresProcess> runtime = PostgresStarter.getInstance(runtimeConfig);
        config = new PostgresConfig(version,
                new Net(host, port),
                new Storage(dbName, dataDir),
                new Timeout(),
                new Credentials(user, password)
        );
        config.getAdditionalInitDbParams().addAll(additionalParams);
        PostgresExecutable exec = runtime.prepare(config);
        this.process = exec.start();
        return formatConnUrl(config);
    }

    /**
     * Returns the configuration of started process
     *
     * @return empty if process has not been started yet
     */
    public Optional<PostgresConfig> getConfig() {
        return ofNullable(config);
    }


    /**
     * Returns the process if started
     *
     * @return empty if process has not been started yet
     */
    public Optional<PostgresProcess> getProcess() {
        return ofNullable(process);
    }

    /**
     * Returns the connection url for the running postgres instance
     *
     * @return empty if process has not been started yet
     */
    public Optional<String> getConnectionUrl() {
        return getConfig().map(this::formatConnUrl);
    }

    private String formatConnUrl(PostgresConfig config) {
        return format("jdbc:postgresql://%s:%s/%s?user=%s&password=%s",//NOSONAR
                config.net().host(),
                config.net().port(),
                config.storage().dbName(),
                config.credentials().username(),
                config.credentials().password()
        );
    }

    public void stop() {
        PostgresProcess postgresProcess = getProcess()
                .orElseThrow(() -> new IllegalStateException("Cannot stop not started instance!"));
        postgresProcess.stop();

        // If we want to persist, we will specify permanent directory
        Storage storage = postgresProcess.getConfig().storage();
        if (!storage.isTmpDir()) {
            return;
        }

        File dbDir = storage.dbDir();
        if (!dbDir.exists()) {
            return;
        }

        try (Stream<Path> stream = Files.walk(dbDir.toPath())) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .filter(File::exists)
                    .peek(System.out::println)
                    .forEach(File::delete);
        } catch (IOException e) {
            e.printStackTrace(); // TODO
        }
    }

    @Override
    public void close() {
        this.stop();
    }
}
