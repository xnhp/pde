package org.knime.targetinstaller.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.security.MessageDigest;
import java.net.URI;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Bootstrap {
  private static final String APP_ID = "org.knime.targetinstaller.target_installer_application_id";
  private static final String RUNTIME_RESOURCE = "/runtime.zip";
  private static final String MARKER_FILE = ".installed";
  private static final String RUNTIME_ZIP_PROP = "pde.eclipse.runtime.zip";
  private static final String RUNTIME_ZIP_ENV = "PDE_ECLIPSE_RUNTIME_ZIP";
  private static final String RUNTIME_ZIP_SHA_PROP = "pde.eclipse.runtime.zip.sha256";
  private static final String RUNTIME_ZIP_SHA_ENV = "PDE_ECLIPSE_RUNTIME_ZIP_SHA256";

  private Bootstrap() {}

  public static void main(String[] args) throws Exception {
    ParsedArgs parsed = ParsedArgs.parse(args);
    if (parsed.showHelp) {
      printUsage();
      return;
    }

    Path runtimeRoot = resolveRuntimeRoot(parsed);
    boolean ephemeral = parsed.cacheMode == CacheMode.EPHEMERAL;
    if (ephemeral) {
      addCleanupHook(runtimeRoot);
    }

    if (ephemeral || needsExtraction(runtimeRoot)) {
      extractRuntime(runtimeRoot);
    }

    Path launcherJar;
    try {
      ensureConfiguration(runtimeRoot);
      launcherJar = findLauncherJar(runtimeRoot.resolve("plugins"));
    } catch (IOException missingLauncher) {
      deleteRecursively(runtimeRoot);
      extractRuntime(runtimeRoot);
      ensureConfiguration(runtimeRoot);
      launcherJar = findLauncherJar(runtimeRoot.resolve("plugins"));
    }
    List<String> appArgs = applyAppDefaults(parsed.passThroughArgs);
    List<String> command = buildCommand(launcherJar, runtimeRoot, appArgs);

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(runtimeRoot.toFile());
    builder.redirectErrorStream(true);

    Path logFile = resolveLogFile(parsed, runtimeRoot);
    Process process = builder.start();
    try (InputStream in = process.getInputStream();
        OutputStream out = openLogStream(logFile)) {
      pipeOutput(in, out);
    }
    int exit = process.waitFor();
    System.exit(exit);
  }

  private static void printUsage() {
    System.out.println("Usage: java -jar launcher.jar [options] [-- <app args>]");
    System.out.println("Options:");
    System.out.println("  --cache=ephemeral|persistent  Cache mode (default: ephemeral)");
    System.out.println("  --cache-dir=PATH              Override cache directory");
    System.out.println("  --log-dir=PATH                Persist logs to PATH");
    System.out.println("  --help                        Show this help");
    System.out.println("Runtime fallback:");
    System.out.println("  If embedded runtime.zip is absent, set " + RUNTIME_ZIP_ENV + " (or -D" + RUNTIME_ZIP_PROP + ") to a runtime.zip URI.");
    System.out.println("Defaults:");
    System.out.println("  If -install-folder is provided, -bundle-pool and -p2Path default under it");
  }

  private static Path resolveRuntimeRoot(ParsedArgs parsed) throws IOException {
    if (parsed.cacheDir != null) {
      return parsed.cacheDir;
    }
    if (parsed.cacheMode == CacheMode.EPHEMERAL) {
      return Files.createTempDirectory("target-installer-runtime-");
    }
    String cacheHome = System.getenv("XDG_CACHE_HOME");
    Path base = cacheHome != null && !cacheHome.isBlank()
        ? Paths.get(cacheHome)
        : Paths.get(System.getProperty("user.home"), ".cache");
    return base.resolve("knime-target-installer").resolve("runtime");
  }

  private static boolean needsExtraction(Path runtimeRoot) throws IOException {
    Path marker = runtimeRoot.resolve(MARKER_FILE);
    if (!Files.exists(marker)) {
      return true;
    }
    String stored = Files.readString(marker, StandardCharsets.UTF_8).trim();
    String current = runtimeHash();
    return !stored.equals(current);
  }

  private static void ensureConfiguration(Path runtimeRoot) throws IOException {
    Path configDir = runtimeRoot.resolve("config");
    Path configIni = configDir.resolve("config.ini");
    Path simpleConfigDir = configDir.resolve("org.eclipse.equinox.simpleconfigurator");
    Path bundlesInfo = simpleConfigDir.resolve("bundles.info");
    if (Files.exists(configIni)
        && Files.exists(bundlesInfo)
        && configMatchesRuntime(configIni, runtimeRoot)
        && !bundlesInfoNeedsRefresh(bundlesInfo)) {
      return;
    }
    Files.createDirectories(configDir);
    Files.createDirectories(simpleConfigDir);
    writeBundlesInfo(bundlesInfo, runtimeRoot.resolve("plugins"));
    writeConfigIni(configIni, runtimeRoot, bundlesInfo);
  }

  private static boolean bundlesInfoNeedsRefresh(Path bundlesInfo) throws IOException {
    for (String line : Files.readAllLines(bundlesInfo, StandardCharsets.UTF_8)) {
      if (line.startsWith("#")) {
        continue;
      }
      if (line.contains("reference:")) {
        return true;
      }
    }
    return false;
  }

  private static boolean configMatchesRuntime(Path configIni, Path runtimeRoot) throws IOException {
    String installArea = "osgi.install.area=" + runtimeRoot.toUri();
    String frameworkPrefix = "osgi.framework=file:" + runtimeRoot.toUri().getPath() + "plugins/";
    boolean requiresScr = hasBundle(runtimeRoot.resolve("plugins"), "org.apache.felix.scr");
    boolean sawBundles = false;
    for (String line : Files.readAllLines(configIni, StandardCharsets.UTF_8)) {
      if (line.startsWith("osgi.install.area=")) {
        if (!line.equals(installArea)) {
          return false;
        }
      }
      if (line.startsWith("osgi.framework=")) {
        if (!line.startsWith(frameworkPrefix)) {
          return false;
        }
      }
      if (line.startsWith("osgi.bundles=")) {
        sawBundles = true;
        if (requiresScr && !line.contains("org.apache.felix.scr")) {
          return false;
        }
      }
    }
    if (requiresScr && !sawBundles) {
      return false;
    }
    return true;
  }

  private static void writeConfigIni(Path configIni, Path runtimeRoot, Path bundlesInfo) throws IOException {
    Path frameworkJar = findOsgiFrameworkJar(runtimeRoot.resolve("plugins"));
    String osgiBundles = "org.eclipse.equinox.simpleconfigurator@1:start";
    if (hasBundle(runtimeRoot.resolve("plugins"), "org.apache.felix.scr")) {
      osgiBundles = osgiBundles + ",org.apache.felix.scr@2:start";
    }
    try (BufferedWriter writer = Files.newBufferedWriter(configIni, StandardCharsets.UTF_8)) {
      writer.write("#Configuration File");
      writer.newLine();
      writer.write("eclipse.application=" + APP_ID);
      writer.newLine();
      writer.write("eclipse.p2.data.area=@config.dir/.p2");
      writer.newLine();
      writer.write("org.eclipse.equinox.simpleconfigurator.configUrl=" + bundlesInfo.toUri());
      writer.newLine();
      writer.write("org.eclipse.update.reconcile=false");
      writer.newLine();
      writer.write("osgi.bundles=" + osgiBundles);
      writer.newLine();
      writer.write("osgi.bundles.defaultStartLevel=4");
      writer.newLine();
      writer.write("osgi.configuration.cascaded=false");
      writer.newLine();
      writer.write("osgi.framework=" + frameworkJar.toUri());
      writer.newLine();
      writer.write("osgi.install.area=" + runtimeRoot.toUri());
      writer.newLine();
    }
  }

  private static boolean hasBundle(Path pluginsDir, String bundlePrefix) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, bundlePrefix + "_*.jar")) {
      return stream.iterator().hasNext();
    }
  }

  private static void writeBundlesInfo(Path bundlesInfo, Path pluginsDir) throws IOException {
    if (!Files.isDirectory(pluginsDir)) {
      throw new IOException("Missing plugins directory: " + pluginsDir);
    }
    List<BundleEntry> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
      for (Path plugin : stream) {
        Manifest manifest = readManifest(plugin);
        if (manifest == null) {
          continue;
        }
        Attributes attrs = manifest.getMainAttributes();
        String symbolicName = attrs.getValue("Bundle-SymbolicName");
        String version = attrs.getValue("Bundle-Version");
        if (symbolicName == null || version == null) {
          continue;
        }
        int semicolon = symbolicName.indexOf(';');
        if (semicolon > 0) {
          symbolicName = symbolicName.substring(0, semicolon);
        }
        String location = plugin.toUri().toString();
        if (Files.isDirectory(plugin) && !location.endsWith("/")) {
          location = location + "/";
        }
        entries.add(new BundleEntry(symbolicName, version, location));
      }
    }
    entries.sort(Comparator.comparing(entry -> entry.symbolicName));
    try (BufferedWriter writer = Files.newBufferedWriter(bundlesInfo, StandardCharsets.UTF_8)) {
      writer.write("#version=1");
      writer.newLine();
      for (BundleEntry entry : entries) {
        writer.write(entry.symbolicName + "," + entry.version + "," + entry.location + ",4,true");
        writer.newLine();
      }
    }
  }

  private static Path findOsgiFrameworkJar(Path pluginsDir) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "org.eclipse.osgi_*.jar")) {
      for (Path candidate : stream) {
        return candidate;
      }
    }
    throw new IOException("Unable to locate org.eclipse.osgi in " + pluginsDir);
  }

  private static Manifest readManifest(Path plugin) {
    try {
      if (Files.isDirectory(plugin)) {
        Path manifestPath = plugin.resolve("META-INF").resolve("MANIFEST.MF");
        if (Files.exists(manifestPath)) {
          try (InputStream in = Files.newInputStream(manifestPath)) {
            return new Manifest(in);
          }
        }
        return null;
      }
      try (JarFile jar = new JarFile(plugin.toFile())) {
        return jar.getManifest();
      }
    } catch (IOException ignored) {
      return null;
    }
  }

  private static void extractRuntime(Path runtimeRoot) throws IOException {
    deleteRecursively(runtimeRoot);
    Files.createDirectories(runtimeRoot);
    try (InputStream raw = openRuntimeZipStream()) {
      try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          Path target = runtimeRoot.resolve(entry.getName()).normalize();
          if (!target.startsWith(runtimeRoot)) {
            throw new IOException("Invalid entry: " + entry.getName());
          }
          if (entry.isDirectory()) {
            Files.createDirectories(target);
          } else {
            Files.createDirectories(target.getParent());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
              zip.transferTo(out);
            }
          }
        }
      }
    }
    Files.writeString(runtimeRoot.resolve(MARKER_FILE), runtimeHash(), StandardCharsets.UTF_8);
  }

  private static String runtimeHash() throws IOException {
    String descriptor = runtimeDescriptorIfExternal();
    if (descriptor != null) {
      MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (Exception ex) {
        throw new IOException("Missing SHA-256 support", ex);
      }
      digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
      byte[] bytes = digest.digest();
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    }

    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (Exception ex) {
      throw new IOException("Missing SHA-256 support", ex);
    }
    try (InputStream raw = Bootstrap.class.getResourceAsStream(RUNTIME_RESOURCE)) {
      if (raw == null) {
        throw new IOException("Missing runtime resource: " + RUNTIME_RESOURCE);
      }
      byte[] buffer = new byte[8192];
      int read;
      while ((read = raw.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    byte[] bytes = digest.digest();
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private static String runtimeDescriptorIfExternal() {
    if (Bootstrap.class.getResourceAsStream(RUNTIME_RESOURCE) != null) {
      return null;
    }
    String uri = runtimeZipUri();
    String sha = runtimeZipSha256();
    return uri + "|" + (sha == null ? "" : sha);
  }

  private static InputStream openRuntimeZipStream() throws IOException {
    InputStream embedded = Bootstrap.class.getResourceAsStream(RUNTIME_RESOURCE);
    if (embedded != null) {
      return embedded;
    }
    String configured = runtimeZipUri();
    if (configured == null || configured.isBlank()) {
      throw new IOException(
          "Missing runtime resource " + RUNTIME_RESOURCE + " and no " + RUNTIME_ZIP_ENV + " / -D" + RUNTIME_ZIP_PROP + " configured");
    }
    try {
      URI uri;
      try {
        uri = URI.create(configured);
      } catch (IllegalArgumentException invalidUri) {
        uri = Paths.get(configured).toUri();
      }
      InputStream in = uri.toURL().openStream();
      String expectedSha = runtimeZipSha256();
      if (expectedSha == null || expectedSha.isBlank()) {
        return in;
      }
      Path temp = Files.createTempFile("pde-runtime-", ".zip");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream source = in) {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = source.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
            out.write(buffer, 0, read);
          }
        }
      }
      byte[] bytes = digest.digest();
      StringBuilder actual = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        actual.append(String.format("%02x", b));
      }
      if (!actual.toString().equalsIgnoreCase(expectedSha.trim())) {
        Files.deleteIfExists(temp);
        throw new IOException("Runtime zip checksum mismatch for " + configured);
      }
      return new java.io.FilterInputStream(new BufferedInputStream(Files.newInputStream(temp))) {
        @Override
        public void close() throws IOException {
          try {
            super.close();
          } finally {
            Files.deleteIfExists(temp);
          }
        }
      };
    } catch (IOException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IOException("Failed to open runtime zip from " + configured, ex);
    }
  }

  private static String runtimeZipUri() {
    String prop = System.getProperty(RUNTIME_ZIP_PROP);
    if (prop != null && !prop.isBlank()) {
      return prop;
    }
    String env = System.getenv(RUNTIME_ZIP_ENV);
    if (env != null && !env.isBlank()) {
      return env;
    }
    return null;
  }

  private static String runtimeZipSha256() {
    String prop = System.getProperty(RUNTIME_ZIP_SHA_PROP);
    if (prop != null && !prop.isBlank()) {
      return prop;
    }
    String env = System.getenv(RUNTIME_ZIP_SHA_ENV);
    if (env != null && !env.isBlank()) {
      return env;
    }
    return null;
  }

  private static Path findLauncherJar(Path pluginsDir) throws IOException {
    if (!Files.isDirectory(pluginsDir)) {
      throw new IOException("Missing plugins directory: " + pluginsDir);
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "org.eclipse.equinox.launcher_*.jar")) {
      for (Path candidate : stream) {
        return candidate;
      }
    }
    throw new IOException("Unable to locate org.eclipse.equinox.launcher in " + pluginsDir);
  }

  private static List<String> buildCommand(Path launcherJar, Path runtimeRoot, List<String> passThrough) {
    String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    List<String> command = new ArrayList<>();
    command.add(javaBin);
    command.add("-jar");
    command.add(launcherJar.toString());
    command.add("-application");
    command.add(APP_ID);
    command.add("-data");
    command.add(runtimeRoot.resolve("data").toString());
    command.add("-configuration");
    command.add(runtimeRoot.resolve("config").toString());
    command.add("-consoleLog");
    command.addAll(passThrough);
    return command;
  }

  private static List<String> applyAppDefaults(List<String> args) {
    String installFolder = findArgValue(args, "-install-folder");
    if (installFolder == null) {
      return args;
    }
    List<String> updated = new ArrayList<>(args);
    if (!hasArg(args, "-bundlePool", "-bundle-pool")) {
      updated.add("-bundle-pool");
      updated.add(Paths.get(installFolder, "bundle-pool").toString());
    }
    if (!hasArg(args, "-p2Path", "-p2-path")) {
      updated.add("-p2Path");
      updated.add(Paths.get(installFolder, "p2").toString());
    }
    return updated;
  }

  private static boolean hasArg(List<String> args, String... keys) {
    for (String key : keys) {
      for (String arg : args) {
        if (key.equals(arg)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String findArgValue(List<String> args, String... keys) {
    for (String key : keys) {
      for (int i = 0; i < args.size() - 1; i++) {
        if (key.equals(args.get(i))) {
          return args.get(i + 1);
        }
      }
    }
    return null;
  }

  private static Path resolveLogFile(ParsedArgs parsed, Path runtimeRoot) throws IOException {
    if (parsed.logDir != null) {
      Files.createDirectories(parsed.logDir);
      return parsed.logDir.resolve("launcher.log");
    }
    if (parsed.cacheMode == CacheMode.PERSISTENT) {
      Path logDir = runtimeRoot.resolve("logs");
      Files.createDirectories(logDir);
      return logDir.resolve("launcher-" + UUID.randomUUID() + ".log");
    }
    return null;
  }

  private static OutputStream openLogStream(Path logFile) throws IOException {
    if (logFile == null) {
      return null;
    }
    return new BufferedOutputStream(Files.newOutputStream(logFile));
  }

  private static void pipeOutput(InputStream input, OutputStream logStream) throws IOException {
    Objects.requireNonNull(input, "input");
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      System.out.write(buffer, 0, read);
      if (logStream != null) {
        logStream.write(buffer, 0, read);
        logStream.flush();
      }
    }
  }

  private static void addCleanupHook(Path runtimeRoot) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(runtimeRoot)));
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try {
      Files.walk(root)
          .sorted((a, b) -> b.compareTo(a))
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
              // Best effort cleanup
            }
          });
    } catch (IOException ignored) {
      // Best effort cleanup
    }
  }

  private static final class BundleEntry {
    private final String symbolicName;
    private final String version;
    private final String location;

    private BundleEntry(String symbolicName, String version, String location) {
      this.symbolicName = symbolicName;
      this.version = version;
      this.location = location;
    }
  }

  private enum CacheMode {
    EPHEMERAL,
    PERSISTENT
  }

  private static final class ParsedArgs {
    private final CacheMode cacheMode;
    private final Path cacheDir;
    private final Path logDir;
    private final boolean showHelp;
    private final List<String> passThroughArgs;

    private ParsedArgs(CacheMode cacheMode, Path cacheDir, Path logDir, boolean showHelp, List<String> passThroughArgs) {
      this.cacheMode = cacheMode;
      this.cacheDir = cacheDir;
      this.logDir = logDir;
      this.showHelp = showHelp;
      this.passThroughArgs = passThroughArgs;
    }

    private static ParsedArgs parse(String[] args) {
      CacheMode cacheMode = CacheMode.EPHEMERAL;
      Path cacheDir = null;
      Path logDir = null;
      boolean showHelp = false;
      List<String> passThrough = new ArrayList<>();
      boolean passthroughOnly = false;

      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (passthroughOnly) {
          passThrough.add(arg);
          continue;
        }
        if ("--".equals(arg)) {
          passthroughOnly = true;
          continue;
        }
        if ("--help".equals(arg) || "-h".equals(arg)) {
          showHelp = true;
          continue;
        }
        if (arg.startsWith("--cache=")) {
          String value = arg.substring("--cache=".length()).toLowerCase(Locale.ROOT);
          cacheMode = "persistent".equals(value) ? CacheMode.PERSISTENT : CacheMode.EPHEMERAL;
          continue;
        }
        if (arg.startsWith("--cache-dir=")) {
          cacheDir = Paths.get(arg.substring("--cache-dir=".length()));
          continue;
        }
        if (arg.startsWith("--log-dir=")) {
          logDir = Paths.get(arg.substring("--log-dir=".length()));
          continue;
        }
        if ("--persistent".equals(arg)) {
          cacheMode = CacheMode.PERSISTENT;
          continue;
        }
        if ("--ephemeral".equals(arg)) {
          cacheMode = CacheMode.EPHEMERAL;
          continue;
        }
        passThrough.add(arg);
      }
      return new ParsedArgs(cacheMode, cacheDir, logDir, showHelp, passThrough);
    }
  }
}
