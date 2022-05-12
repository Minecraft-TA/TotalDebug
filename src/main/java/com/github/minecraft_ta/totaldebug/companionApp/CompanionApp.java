package com.github.minecraft_ta.totaldebug.companionApp;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.CompanionAppReadyMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppChunkGridDataMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppChunkGridRequestInfoUpdateMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppReceiveDataStateMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppUpdateFollowPlayerStateMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.codeView.DecompileOrOpenMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.*;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ScriptStatusMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.script.StopScriptMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.search.OpenSearchResultsMessage;
import com.github.minecraft_ta.totaldebug.util.mappings.ClassUtil;
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.IDisconnectedListener;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CompanionApp {

    public static final String COMPANION_APP_FOLDER = "companion-app";
    public static final String DATA_FOLDER = "data";

    private static final Gson GSON = new GsonBuilder().create();

    private final Path appDir;
    private final Metafile metafile;

    private Process companionAppProcess;
    private CompletableFuture<Void> awaitCompanionAppUIReadyFuture = new CompletableFuture<>();
    private final Client companionAppClient = new Client();
    {
        int id = 1;
        companionAppClient.getMessageProcessor().registerMessage((short) id++, CompanionAppReadyMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, DecompileOrOpenMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, OpenSearchResultsMessage.class);

        companionAppClient.getMessageProcessor().registerMessage((short) id++, CompanionAppReceiveDataStateMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, CompanionAppChunkGridDataMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, CompanionAppChunkGridRequestInfoUpdateMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, CompanionAppUpdateFollowPlayerStateMessage.class);

        companionAppClient.getMessageProcessor().registerMessage((short) id++, RunScriptMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, ScriptStatusMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, StopScriptMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, FocusWindowMessage.class);

        companionAppClient.getMessageProcessor().registerMessage((short) id++, PacketLoggerStateChangeMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, IncomingPacketsMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, OutgoingPacketsMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, ClearPacketsMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, ChannelListMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, SetChannelMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, PacketContentMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, CapturePacketMessage.class);
        companionAppClient.getMessageProcessor().registerMessage((short) id++, BlockPacketMessage.class);

        companionAppClient.getMessageBus().listenAlways(DecompileOrOpenMessage.class, DecompileOrOpenMessage::handle);

        companionAppClient.getMessageBus().listenAlways(CompanionAppReceiveDataStateMessage.class, CompanionAppReceiveDataStateMessage::handle);
        companionAppClient.getMessageBus().listenAlways(CompanionAppChunkGridRequestInfoUpdateMessage.class, CompanionAppChunkGridRequestInfoUpdateMessage::handle);
        companionAppClient.getMessageBus().listenAlways(CompanionAppUpdateFollowPlayerStateMessage.class, CompanionAppUpdateFollowPlayerStateMessage::handle);

        companionAppClient.getMessageBus().listenAlways(RunScriptMessage.class, RunScriptMessage::handle);
        companionAppClient.getMessageBus().listenAlways(StopScriptMessage.class, StopScriptMessage::handle);

        companionAppClient.getMessageBus().listenAlways(CompanionAppReadyMessage.class, (m) -> awaitCompanionAppUIReadyFuture.complete(null));

        companionAppClient.getMessageBus().listenAlways(PacketLoggerStateChangeMessage.class, PacketLoggerStateChangeMessage::handle);
        companionAppClient.getMessageBus().listenAlways(ClearPacketsMessage.class, ClearPacketsMessage::handle);
        companionAppClient.getMessageBus().listenAlways(ChannelListMessage.class, m -> companionAppClient.getMessageProcessor().enqueueMessage(new ChannelListMessage()));
        companionAppClient.getMessageBus().listenAlways(SetChannelMessage.class, m -> TotalDebug.PROXY.getPackerLogger().setChannel(m.getChannel()));
        companionAppClient.getMessageBus().listenAlways(CapturePacketMessage.class, m -> TotalDebug.PROXY.getPackerLogger().setPacketsToCapture(m.getPacket(), m.isRemove()));

        companionAppClient.addConnectionListener((IDisconnectedListener) () -> {
            // Stop scripts
            StopScriptMessage.handle(new StopScriptMessage(-1));
            // Disable chunk grid
            CompanionAppReceiveDataStateMessage.handle(new CompanionAppReceiveDataStateMessage(false));
            // Disable packet logger
            PacketLoggerStateChangeMessage.handle(new PacketLoggerStateChangeMessage(false, false));
            ClearPacketsMessage.handle(new ClearPacketsMessage());
        });
    }

    public CompanionApp(Path appDir) {
        this.appDir = appDir;

        if (!Files.exists(this.appDir)) {
            try {
                Files.createDirectory(this.appDir);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to create companion app directory", e);
            }
        }

        this.metafile = new Metafile(this.appDir.resolve(".meta"));
        this.metafile.read();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            //let's not keep this running
            if (this.companionAppProcess != null)
                this.companionAppProcess.destroyForcibly();
        }));
    }

    /**
     * Downloads, starts and connects to the companion app and sends progress updates to the player
     */
    public void startAndConnect() {
        ICommandSender sender = Minecraft.getMinecraft().player;

        if (!isRunning()) {
            this.metafile.loadNewestCompanionAppVersion();

            Path exePath = this.appDir.resolve("TotalDebugCompanion.jar");

            if (!Files.exists(exePath) ||
                !this.metafile.currentCompanionAppVersion.equals(this.metafile.newestCompatibleCompanionAppVersion)) {
                downloadCompanionApp(this.metafile.newestCompatibleCompanionAppVersion);
                this.metafile.currentCompanionAppVersion = this.metafile.newestCompatibleCompanionAppVersion;
                this.metafile.write();
            }

            if (!Files.exists(TotalDebug.PROXY.getMinecraftClassDumpPath())) {
                sender.sendMessage(new TextComponentTranslation("companion_app.dumping_minecraft_classes").setStyle(new Style().setColor(TextFormatting.GRAY)));
                ClassUtil.dumpMinecraftClasses();
            }

            Path indexPath = TotalDebug.PROXY.getDecompilationManager().getDataDir().resolve(DATA_FOLDER).resolve("index");
            if (!Files.exists(indexPath)) {
                sender.sendMessage(new TextComponentTranslation("companion_app.start_indexing").setStyle(new Style().setColor(TextFormatting.GRAY)));
                ClassUtil.createClassIndex(indexPath);
            }

            sender.sendMessage(new TextComponentTranslation("companion_app.starting").setStyle(new Style().setColor(TextFormatting.GRAY)));
            startApp();
        }

        if (!isConnected()) {
            sender.sendMessage(
                    new TextComponentTranslation("companion_app.connecting")
                            .setStyle(new Style().setColor(TextFormatting.GRAY))
            );

            this.awaitCompanionAppUIReadyFuture.cancel(true);
            this.awaitCompanionAppUIReadyFuture = new CompletableFuture<>();

            if (connect(5, 1000)) {
                sender.sendMessage(
                        new TextComponentTranslation("companion_app.connection_success")
                                .setStyle(new Style().setColor(TextFormatting.GREEN))
                );
            } else {
                sender.sendMessage(
                        new TextComponentTranslation("companion_app.connection_fail")
                                .setStyle(new Style().setColor(TextFormatting.RED))
                );
            }
        }
    }

    /**
     * @return {@code true} if the companion's UI is ready
     */
    public boolean waitForUI() {
        if (this.awaitCompanionAppUIReadyFuture.isDone())
            return true;

        try {
            this.awaitCompanionAppUIReadyFuture.get(60, TimeUnit.SECONDS);
            this.awaitCompanionAppUIReadyFuture.cancel(true);
            return true;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            TotalDebug.LOGGER.error("Timed out while waiting for companion app ready message", e);
            if (this.companionAppClient.isConnected())
                this.companionAppClient.close();

            return false;
        }
    }

    /**
     * @return {@code true} if we're still connected to the companion app; {@code false} otherwise
     */
    public boolean isConnected() {
        return this.companionAppClient.isConnected();
    }

    /**
     * @return {@code true} if the companion app is running; {@code false} otherwise
     */
    public boolean isRunning() {
        return this.companionAppProcess != null && this.companionAppProcess.isAlive();
        //return true;
    }

    public Client getClient() {
        return this.companionAppClient;
    }

    /**
     * Connects to the companion app
     *
     * @param retries the number of connection tries to do before giving up
     * @param delay   the delay between each try
     * @return {@code true} if the connection was successful; {@code false} otherwise
     */
    private boolean connect(int retries, int delay) {
        if (isConnected())
            return true;

        return this.companionAppClient.connect(new InetSocketAddress(25570), delay, retries);
    }

    /**
     * Starts the companion app
     */
    private void startApp() {
        if (isRunning())
            return;

        //TODO: linux
        Path exePath = this.appDir.resolve("TotalDebugCompanion.jar");
        Path logFile = createLogFile();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    this.appDir.resolve("bin").resolve("java.exe").toAbsolutePath().toString(),
                    "--enable-preview",
                    "-jar",
                    exePath.toAbsolutePath().toString(),
                    "\"" + this.appDir.getParent().resolve(DATA_FOLDER).toAbsolutePath() + "\""
            );

            if (logFile != null) {
                processBuilder.redirectError(logFile.toFile());
                processBuilder.redirectOutput(logFile.toFile());
            }

            this.companionAppProcess = processBuilder.start();
        } catch (IOException e) {
            this.companionAppProcess = null;
            TotalDebug.LOGGER.error("Unable to start companion app!", e);
        }
    }

    /**
     * Downloads the companion app release with the given {@code version} and unzips it into
     * the {@link #COMPANION_APP_FOLDER}.
     * Download progress updates are frequently sent to the player.
     *
     * @param version the version to download
     */
    private void downloadCompanionApp(String version) {
        Minecraft.getMinecraft().player.sendMessage(
                new TextComponentTranslation("companion_app.download_start", version)
                        .setStyle(new Style().setColor(TextFormatting.GRAY))
        );

        HttpClient client = HttpClients.createDefault();
        HttpResponse response;
        try {
            response = client.execute(new HttpGet("https://github.com/Minecraft-TA/TotalDebugCompanion/releases/download/" + version + "/TotalDebugCompanion.zip"));
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to reach github. Does this release exist? " + version, e);
            return;
        }

        HttpEntity entity = response.getEntity();

        long writtenBytes = 0;

        //download and unzip on the fly
        try (ZipInputStream zipInputStream = new ZipInputStream(Channels.newInputStream(Channels.newChannel(entity.getContent())))) {
            for (ZipEntry entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                Path toPath = this.appDir.resolve(entry.getName());
                if (entry.isDirectory()) { //create directory
                    if (!Files.exists(toPath))
                        Files.createDirectory(toPath);
                } else { //transfer file to file system
                    try (FileChannel fileChannel = FileChannel.open(toPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                        fileChannel.transferFrom(Channels.newChannel(zipInputStream), 0, Long.MAX_VALUE);
                        writtenBytes += entry.getCompressedSize();
                    }

                    //send progress message
                    Minecraft.getMinecraft().player.sendStatusMessage(
                            new TextComponentString((writtenBytes * 100 / this.metafile.newestCompanionAppVersionSize) + "%")
                                    .setStyle(new Style().setColor(TextFormatting.GOLD))
                            , true);
                }
            }

            //fake 100% message because we won't exactly reach that
            Minecraft.getMinecraft().player.sendStatusMessage(
                    new TextComponentString(100 + "%")
                            .setStyle(new Style().setColor(TextFormatting.GOLD))
                    , true);

            TotalDebug.LOGGER.info("Successfully downloaded companion app version {}", version);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to download and unzip file", e);
        }
    }

    @Nullable
    private Path createLogFile() {
        Path logDir = this.appDir.resolve("logs");

        if (!Files.exists(logDir)) {
            try {
                Files.createDirectory(logDir);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to create log directory for companion app", e);
                return null;
            }
        }

        Path newLogFile = logDir.resolve(LocalDateTime.now().format(
                new DateTimeFormatterBuilder()
                        .appendValue(ChronoField.DAY_OF_MONTH)
                        .appendLiteral('-')
                        .appendValue(ChronoField.MONTH_OF_YEAR)
                        .appendLiteral('-')
                        .appendValue(ChronoField.YEAR)
                        .appendLiteral('-')
                        .appendValue(ChronoField.HOUR_OF_DAY)
                        .appendLiteral('.')
                        .appendValue(ChronoField.MINUTE_OF_HOUR)
                        .appendLiteral('.')
                        .appendValue(ChronoField.SECOND_OF_MINUTE)
                        .toFormatter()
        ) + ".log");
        try {
            Files.createFile(newLogFile);
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to create log file for companion app", e);
            return null;
        }

        return newLogFile;
    }

    private static final class Metafile {

        /**
         * The newest version of the companion app that is compatible with the current TotalDebug version
         */
        private String newestCompatibleCompanionAppVersion;
        /**
         * The currently installed companion app version
         */
        private String currentCompanionAppVersion;
        /**
         * The download file size of the {@link #newestCompatibleCompanionAppVersion} release
         */
        private long newestCompanionAppVersionSize;

        /**
         * The path to the metadata file
         */
        private final Path path;

        private Metafile(Path path) {
            this.path = path;
        }

        /**
         * Reads the metadata file from the disk. If it doesn't exist, then {@link #initDefaultData()} is called.
         * Like {@link #initDefaultData()} the {@link #newestCompatibleCompanionAppVersion} is determined using
         * {@link #loadNewestCompanionAppVersion()}
         */
        public void read() {
            if (!Files.exists(path)) {
                initDefaultData();
                return;
            }

            try {
                List<String> lines = Files.readAllLines(path);

                if (lines.size() != 1) {
                    TotalDebug.LOGGER.error("Meta file does not contain 1 line");
                    Files.deleteIfExists(path);
                    initDefaultData();
                    return;
                }

                this.currentCompanionAppVersion = lines.get(0);
                loadNewestCompanionAppVersion();

                TotalDebug.LOGGER.info("Successfully loaded meta file. TotalDebug: {}, Companion: {}->{}",
                        TotalDebug.VERSION, this.currentCompanionAppVersion, this.newestCompatibleCompanionAppVersion);
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to read meta file", e);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ioException) {
                }

                initDefaultData();
            }
        }

        /**
         * Saves this instance to the disk
         */
        public void write() {
            try {
                Files.write(this.path, Lists.newArrayList(this.currentCompanionAppVersion));
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Unable to write meta file", e);
            }
        }

        /**
         * Initializes this instance with default data and then writes it to the disk:
         * <ul>
         * <li>{@link #newestCompatibleCompanionAppVersion} = {@link #loadNewestCompanionAppVersion()}</li>
         * <li>{@link #currentCompanionAppVersion} = {@link #newestCompatibleCompanionAppVersion}</li>
         * </ul>
         */
        private void initDefaultData() {
            loadNewestCompanionAppVersion();
            this.currentCompanionAppVersion = this.newestCompatibleCompanionAppVersion;
            write();
        }

        /**
         * Performs a github API request to find the newest compatible version with the
         * current {@link TotalDebug#VERSION} and sets {@link #newestCompatibleCompanionAppVersion}. Compatible means,
         * that major and minor versions match. This also gets the release file size for the found version and sets
         * {@link #newestCompanionAppVersionSize}
         * <br><br>
         * Example for TotalDebug: v1.2.5
         * <br>
         * CompanionApp:
         * <ul>
         *     <li>v1.3.0 - Compatible, but only used if no v1.2.X versions exist</li>
         *     <li>v1.2.8 - Newest compatible</li>
         *     <li>v1.2.0 - Compatible</li>
         *     <li>v1.1.5 - Not compatible</li>
         *     <li>v1.1.0 - Not compatible</li>
         * </ul>
         */
        private void loadNewestCompanionAppVersion() {
            try {
                HttpClient client = HttpClients.createDefault();
                HttpResponse response = client.execute(new HttpGet("https://api.github.com/repos/Minecraft-TA/TotalDebugCompanion/releases"));
                HttpEntity entity = response.getEntity();

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                entity.writeTo(outputStream);

                byte[] responseData = outputStream.toByteArray();

                JsonArray jsonArray = GSON.fromJson(new String(responseData, StandardCharsets.UTF_8), JsonArray.class);
                String totalDebugVersion = "v" + TotalDebug.VERSION;

                //find newest matching version
                for (int i = 0; i < jsonArray.size(); i++) {
                    JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
                    String version = jsonObject.get("tag_name").getAsString();

                    //don't compare build number
                    if (version.substring(0, version.lastIndexOf('.'))
                            .equals(totalDebugVersion.substring(0, totalDebugVersion.lastIndexOf('.')))) {
                        TotalDebug.LOGGER.info("Found matching companion app version {}", version);
                        this.newestCompatibleCompanionAppVersion = version;
                        this.newestCompanionAppVersionSize = jsonObject.getAsJsonArray("assets").get(0)
                                .getAsJsonObject().getAsJsonPrimitive("size").getAsLong();
                        return;
                    }
                }

                //return newest version if no matching version was found
                String newestVersion = jsonArray.get(0).getAsJsonObject().get("tag_name").getAsString();
                TotalDebug.LOGGER.info("No matching companion app version found. Falling back to newest available {}", newestVersion);
                this.newestCompatibleCompanionAppVersion = newestVersion;
                this.newestCompanionAppVersionSize = jsonArray.get(0).getAsJsonObject()
                        .getAsJsonArray("assets").get(0)
                        .getAsJsonObject().getAsJsonPrimitive("size").getAsLong();
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Could not determine newest companion app version. Auto-update or downloading might not work", e);
            }
        }
    }
}
