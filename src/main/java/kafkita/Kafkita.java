package kafkita;

import de.flapdoodle.embed.process.runtime.Processes;
import java.io.*;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import kafka.Kafka;
import kafka.admin.AdminClient;
import kafka.admin.BrokerApiVersionsCommand;
import org.apache.commons.cli.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.kafka.common.utils.Utils;
import org.apache.zookeeper.client.FourLetterWordMain;
import org.apache.zookeeper.server.quorum.QuorumPeerMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kafkita {

  private static final Logger LOG = LoggerFactory.getLogger(Kafkita.class);

  public static final String DEFAULT_STACK_DIR = ".kafkita";

  public static final int[] DEFAULT_ZK_PORT_RANGE = new int[] {12100, 12200};
  public static final int[] DEFAULT_KAFKA_PORT_RANGE = new int[] {19000, 19100};

  public static class HealthThresholds {
    public int maxStartTries = Integer.MAX_VALUE;
    public Duration maxStartWait = Duration.ofSeconds(8);
    public int maxHealthTries = Integer.MAX_VALUE;
    public Duration maxHealthWait = Duration.ofSeconds(1);
    public Duration maxStopWait = Duration.ofSeconds(4);
  }

  public abstract static class Service {

    public final String id;
    public final File dir;
    public final File pidFile;
    public final File logFile;
    public HealthThresholds healthThresholds;

    // JDK 9 gives us this, we're in JDK 8
    public static class BuiltProcess {
      public final ProcessBuilder builder;
      public final Process process;

      protected BuiltProcess(ProcessBuilder builder, Process process) {
        this.builder = builder;
        this.process = process;
      }

      public static BuiltProcess start(ProcessBuilder builder) throws IOException {
        return new BuiltProcess(builder, builder.start());
      }
    }

    public BuiltProcess builtProcess = null;

    public Service(
        String id, File dir, File pidFile, File logFile, HealthThresholds healthThresholds) {
      this.id = id;
      this.dir = dir;
      this.pidFile = pidFile;
      this.logFile = logFile;
      this.healthThresholds = healthThresholds;

      // Just in case of unclean shutdown
      removePidFile();
    }

    public boolean waitForStart() throws InterruptedException {

      LOG.info(String.format("Starting service: id=%s", id));

      removePidFile();

      Duration nextWait = null;

      for (int i = 0; ; ++i) {

        this.builtProcess = null;
        try {

          this.builtProcess = tryStart();
          if (this.builtProcess == null)
            throw new IOException(String.format("No process was started: id=%s", id));

          if (!waitUntilHealthy()) {
            waitForStop();
            throw new IOException(String.format("Process was never found healthy: id=%s", id));
          }

          try {
            // Create PID file *last* so others know process is healthy
            writePidFile();
          } catch (IOException ex) {
            throw new RuntimeException(
                String.format("Could not write pid file %s", pidFile.getAbsolutePath()));
          }

          // We started successfully!
          LOG.info(String.format("Service started successfully: id=%s", id));
          return true;

        } catch (IOException ex) {
          LOG.warn(String.format("Error starting process: %s: id=%s", ex.getMessage(), id));
        }

        if (!(i < healthThresholds.maxStartTries)) break;

        nextWait = nextWait == null ? Duration.ofSeconds(1) : nextWait.plus(nextWait);
        if (nextWait.compareTo(healthThresholds.maxStartWait) > 0)
          nextWait = healthThresholds.maxStartWait;

        LOG.info(String.format("Waiting %ss for next restart: id=%s", nextWait.getSeconds(), id));
        Thread.sleep(nextWait.toMillis());
      }

      LOG.warn(String.format("Could not start service: id=%s", id));
      return false;
    }

    public abstract BuiltProcess tryStart() throws IOException;

    public boolean waitUntilHealthy() throws InterruptedException {

      LOG.info(String.format("Waiting for service: id=%s", id));

      Duration nextWait = null;

      for (int i = 0; ; ++i) {

        if (!builtProcess.process.isAlive()) {
          LOG.info(String.format("Service died during health checks: id=%s", id));
          return false;
        }

        try {
          if (isHealthy()) {
            LOG.info(String.format("Service is healthy: id=%s", id));
            return true;
          }

          LOG.info(String.format("Process not yet healthy: id=%s", id));
        } catch (IOException ex) {
          LOG.warn(String.format("Error in health check: %s: id=%s", ex.getMessage(), id));
        }

        if (!(i < healthThresholds.maxHealthTries)) break;

        nextWait = nextWait == null ? Duration.ofSeconds(1) : nextWait.plus(nextWait);
        if (nextWait.compareTo(healthThresholds.maxHealthWait) > 0)
          nextWait = healthThresholds.maxHealthWait;

        LOG.info(
            String.format("Waiting %ss for next health check: id=%s", nextWait.getSeconds(), id));
        Thread.sleep(nextWait.toMillis());
      }

      LOG.warn(String.format("Service was never healthy: id=%s", id));
      return false;
    }

    public abstract boolean isHealthy() throws IOException;

    public void writePidFile() throws IOException {
      Long pid = Processes.processId(builtProcess.process);
      FileUtils.writeStringToFile(
          pidFile, pid != null ? pid.toString() : "", StandardCharsets.UTF_8);
    }

    public Process getProcess() {
      return builtProcess.process;
    }

    public BuiltProcess getBuiltProcess() {
      return builtProcess;
    }

    public void removePidFile() {
      FileUtils.deleteQuietly(pidFile);
    }

    public int waitForStop() throws InterruptedException {

      LOG.info(String.format("Stopping service: id=%s", id));

      // Delete PID file *first* so that others know not to use the process
      removePidFile();

      try {

        if (!builtProcess.process.isAlive()) return builtProcess.process.exitValue();

        LOG.info(String.format("Waiting for exit: id=%s", id));

        Instant startWait = null;
        builtProcess.process.destroy();

        while (true) {

          if (!builtProcess.process.isAlive()) return builtProcess.process.exitValue();

          if (startWait == null) {
            startWait = Instant.now();
          } else {
            if (Instant.now().isAfter(startWait.plus(healthThresholds.maxStopWait))) break;
          }

          Thread.sleep(100);
        }

        LOG.info(String.format("Forced exit: id=%s", id));

        return builtProcess.process.destroyForcibly().waitFor();

      } finally {

        LOG.info(String.format("Stopped service: id=%s", id));

        this.builtProcess = null;
      }
    }
  }

  static class ZkService extends Service {

    public final File propFile;
    public final File dataDir;
    public final int[] portRange;
    public final File portFile;

    public Properties addlProps = new Properties();
    public Properties addlJvmProps = new Properties();

    public Integer port = null;
    public String[] jvmArgs = null;
    public String[] args = null;

    public ZkService(
        String id,
        File dir,
        File pidFile,
        File logFile,
        int[] portRange,
        File portFile,
        HealthThresholds healthThresholds) {
      super(id, dir, pidFile, logFile, healthThresholds);
      this.propFile = new File(dir, "zk.properties");
      this.dataDir = new File(dir, "data");
      this.portFile = portFile;
      this.portRange = portRange;
    }

    public Properties getAddlProps() {
      return addlProps;
    }

    public Properties getAddlJvmProps() {
      return addlJvmProps;
    }

    @Override
    public BuiltProcess tryStart() throws IOException {

      this.dir.mkdirs();
      this.dataDir.mkdirs();

      this.port = nextFreePort(this.id, this.portRange[0], this.portRange[1]);

      Map<String, String> zkMap = new HashMap<>();
      zkMap.put("kafkita.zk.clientPort", "" + this.port);
      zkMap.put("kafkita.zk.dataDir", this.dataDir.getAbsolutePath());

      String propsList = renderTemplate(readResourceAsString("zk.template.properties"), zkMap);
      String addlPropsList = renderTemplate(toPropListStr(addlJvmProps), zkMap);
      String jvmPropsList = renderTemplate(toPropListStr(addlJvmProps), zkMap);
      FileUtils.writeStringToFile(
          propFile,
          propsList + "\n\n#\n# Additional Props\n#\n\n" + addlPropsList,
          StandardCharsets.UTF_8);

      jvmArgs = ArrayUtils.addAll(toLog4jJvmArgs(logFile), toJvmArgs(jvmPropsList, "64m"));
      args = new String[] {QuorumPeerMain.class.getCanonicalName(), propFile.getAbsolutePath()};

      return execJavaProcess(WatchfulSubprocess.class, Arrays.asList(jvmArgs), Arrays.asList(args));
    }

    public boolean isHealthy() throws IOException {
      FourLetterWordMain.send4LetterWord("localhost", this.port, "srvr");
      return true;
    }

    @Override
    public void writePidFile() throws IOException {
      super.writePidFile();
      FileUtils.writeStringToFile(portFile, "" + port, StandardCharsets.UTF_8);
    }

    @Override
    public void removePidFile() {
      FileUtils.deleteQuietly(portFile);
      super.removePidFile();
    }
  }

  public static class KafkaService extends Service {

    public final File propFile;
    public final File logDir;
    public final int[] portRange;
    public final File portFile;
    public final ZkService zkService;

    public Properties addlProps = new Properties();
    public Properties addlJvmProps = new Properties();

    public Integer port = null;
    public String[] jvmArgs = null;
    public String[] args = null;

    public KafkaService(
        String id,
        File dir,
        File pidFile,
        File logFile,
        int[] portRange,
        File portFile,
        ZkService zkService,
        HealthThresholds healthThresholds) {
      super(id, dir, pidFile, logFile, healthThresholds);
      this.propFile = new File(dir, "kafka.properties");
      this.logDir = new File(dir, "data");
      this.portRange = portRange;
      this.portFile = portFile;
      this.zkService = zkService;
    }

    public Properties getAddlProps() {
      return addlProps;
    }

    public Properties getAddlJvmProps() {
      return addlJvmProps;
    }

    @Override
    public BuiltProcess tryStart() throws IOException {

      this.dir.mkdirs();
      this.logDir.mkdirs();

      this.port = nextFreePort(this.id, this.portRange[0], this.portRange[1]);

      Map<String, String> kafkaMap = new HashMap<>();
      kafkaMap.put("kafkita.kafka.zookeeper.connect", "localhost:" + zkService.port);
      kafkaMap.put("kafkita.kafka.port", "" + this.port);
      kafkaMap.put("kafkita.kafka.logDir", this.logDir.getAbsolutePath());

      // Setup some default buffering values based on the message size
      long messageMaxBytes =
          Long.parseLong(
              (String) getAddlProps().getOrDefault("message.max.bytes", "" + (1024 * 1024)));

      long messageBufferBytes =
          Long.parseLong(
              (String)
                  getAddlProps().getOrDefault("message.buffer.bytes", "" + (messageMaxBytes * 10)));

      long logSegmentBytes =
          Long.parseLong(
              (String)
                  getAddlProps().getOrDefault("log.segment.bytes", "" + (messageMaxBytes * 10)));

      kafkaMap.put("kafkita.kafka.message.max.bytes", "" + messageMaxBytes);
      kafkaMap.put("kafkita.kafka.message.buffer.bytes", "" + messageBufferBytes);
      kafkaMap.put("kafkita.kafka.log.segment.bytes", "" + logSegmentBytes);

      String propsList =
          renderTemplate(readResourceAsString("kafka.template.properties"), kafkaMap);
      String addlPropsList = renderTemplate(toPropListStr(getAddlProps()), kafkaMap);

      String jvmPropsList = renderTemplate(toPropListStr(getAddlJvmProps()), kafkaMap);
      FileUtils.writeStringToFile(
          propFile,
          propsList + "\n\n#\n# Additional Props\n#\n\n" + addlPropsList,
          StandardCharsets.UTF_8);

      jvmArgs = ArrayUtils.addAll(toLog4jJvmArgs(logFile), toJvmArgs(jvmPropsList, "192m"));
      args = new String[] {Kafka.class.getCanonicalName(), propFile.getAbsolutePath()};

      return execJavaProcess(WatchfulSubprocess.class, Arrays.asList(jvmArgs), Arrays.asList(args));
    }

    public boolean isHealthy() throws IOException {

      AdminClient adminClient = null;
      try {
        BrokerApiVersionsCommand.BrokerVersionCommandOptions opts =
            new BrokerApiVersionsCommand.BrokerVersionCommandOptions(
                new String[] {"--bootstrap-server", "localhost:" + this.port});
        Properties props =
            opts.options().has(opts.commandConfigOpt())
                ? Utils.loadProps((String) opts.options().valueOf(opts.commandConfigOpt()))
                : new Properties();
        props.put("bootstrap.servers", opts.options().valueOf(opts.bootstrapServerOpt()));
        adminClient = kafka.admin.AdminClient$.MODULE$.create(props);
        adminClient.awaitBrokers();
      } catch (Exception ex) {
        throw new IOException(ex);
      } finally {
        if (adminClient != null) adminClient.close();
      }
      return true;
    }

    @Override
    public void writePidFile() throws IOException {
      super.writePidFile();
      FileUtils.writeStringToFile(portFile, "" + port, StandardCharsets.UTF_8);
    }

    @Override
    public void removePidFile() {
      FileUtils.deleteQuietly(portFile);
      super.removePidFile();
    }
  }

  public final String id;
  public final File dir;
  public final int[] zkPortRange;
  public final int[] kafkaPortRange;

  public HealthThresholds healthThresholds = new HealthThresholds();

  public Properties zkJvmProperties = new Properties();
  public Properties zkProperties = new Properties();
  public Properties kafkaJvmProperties = new Properties();
  public Properties kafkaProperties = new Properties();

  public ZkService zkService = null;
  public KafkaService kafkaService = null;

  public Kafkita() {
    this(new File(DEFAULT_STACK_DIR));
  }

  public Kafkita(File dir) {
    this(dir.getAbsolutePath(), dir);
  }

  public Kafkita(String name, File dir) {
    this(dir.getAbsolutePath(), dir, null, null);
  }

  public Kafkita(File dir, int[] zkPortRange, int[] kafkaPortRange) {
    this(dir.getAbsolutePath(), dir, null, null);
  }

  public Kafkita(String id, File dir, int[] zkPortRange, int[] kafkaPortRange) {
    this.id = id != null ? id : dir.getAbsolutePath();
    this.dir = dir;
    this.zkPortRange = zkPortRange != null ? zkPortRange : DEFAULT_ZK_PORT_RANGE;
    this.kafkaPortRange = zkPortRange != null ? kafkaPortRange : DEFAULT_KAFKA_PORT_RANGE;
  }

  public String getId() {
    return id;
  }

  public HealthThresholds getHealthThresholds() {
    return healthThresholds;
  }

  public Properties getAddlProperties() {
    return this.kafkaProperties;
  }

  public Properties getAddlJvmProperties() {
    return this.kafkaJvmProperties;
  }

  public Properties getAddlZkProperties() {
    return this.zkProperties;
  }

  public Properties getAddlZkJvmProperties() {
    return this.zkJvmProperties;
  }

  public boolean waitForStart() throws InterruptedException {
    return waitForStart(false);
  }

  public boolean waitForStart(boolean zkOnly) throws InterruptedException {

    LOG.info(String.format("Starting kafkita services: id=%s", id));

    if (zkService == null) {

      ZkService zkService =
          new ZkService(
              id + "/zk",
              new File(dir, "zk"),
              new File(dir, "zk.pid"),
              new File(dir, "zk/zk"),
              zkPortRange,
              new File(dir, "zk.port"),
              healthThresholds);

      zkService.addlProps.putAll(zkProperties);
      zkService.addlJvmProps.putAll(zkJvmProperties);

      if (!zkService.waitForStart()) {
        waitForStop();
        return false;
      }

      this.zkService = zkService;
    }

    if (!zkOnly) {

      if (kafkaService == null) {

        KafkaService kafkaService =
            new KafkaService(
                id + "/kafka",
                new File(dir, "kafka"),
                new File(dir, "kafka.pid"),
                new File(dir, "kafka/kafka"),
                kafkaPortRange,
                new File(dir, "kafka.port"),
                this.zkService,
                healthThresholds);

        kafkaService.addlProps.putAll(kafkaProperties);
        kafkaService.addlJvmProps.putAll(kafkaJvmProperties);

        if (!kafkaService.waitForStart()) {
          waitForStop();
          return false;
        }

        this.kafkaService = kafkaService;
      }
    }

    LOG.info(String.format("Kafkita services started: id=%s", id));

    return true;
  }

  public int getZkPort() {
    return zkService.port;
  }

  public File getZkDataDir() {
    return zkService.dataDir;
  }

  public Properties getZkProperties() {
    Properties props = new Properties();
    try {
      props.load(new FileReader(zkService.propFile));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    return props;
  }

  public ZkService getZkService() {
    return zkService;
  }

  public int getPort() {
    return kafkaService.port;
  }

  public File getDir() {
    return dir;
  }

  public File getLogDir() {
    return kafkaService.logDir;
  }

  public Properties getProperties() {
    Properties props = new Properties();
    try {
      props.load(new FileReader(kafkaService.propFile));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    return props;
  }

  public KafkaService getService() {
    return kafkaService;
  }

  public void waitForStop() throws InterruptedException {

    LOG.info(String.format("Stopping kafkita services: id=%s", id));

    if (kafkaService != null) {
      kafkaService.waitForStop();
    }
    if (zkService != null) {
      zkService.waitForStop();
    }

    LOG.info(String.format("Kafkita services stopped: id=%s", id));
  }

  public static String toPropListStr(Properties props) throws IOException {
    StringWriter writer = new StringWriter();
    props.store(new PrintWriter(writer), null);
    return writer.getBuffer().toString();
  }

  public static String[] toJvmArgs(String propListStr, String defaultMemMax) throws IOException {
    Properties props = new Properties();
    props.load(new StringReader(propListStr));

    boolean hasMemMax = false;
    for (Map.Entry entry : props.entrySet()) {
      if (entry.getKey().toString().startsWith("Xmx")) {
        hasMemMax = true;
        break;
      }
    }

    if (!hasMemMax && defaultMemMax != null) {
      props.put("Xmx" + defaultMemMax, "");
    }

    return props
        .entrySet()
        .stream()
        .map(
            e ->
                (!((String) e.getKey()).startsWith("X"))
                    ? String.format("-D%s=%s", e.getKey(), e.getValue())
                    : String.format("-%s", e.getKey()))
        .collect(Collectors.toList())
        .toArray(new String[] {});
  }

  public static String[] toLog4jJvmArgs(File logFilePrefix) {
    return new String[] {
      "-Dlog4j.configuration=log4j.info.kafkita-service.properties",
      "-Dkafkita.service.logFilePrefix=" + logFilePrefix.getAbsolutePath()
    };
  }

  public static Service.BuiltProcess execJavaProcess(
      Class clazz, List<String> jvmArgs, List<String> args) throws IOException {

    String javaHome = System.getProperty("java.home");
    String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
    String classpath = System.getProperty("java.class.path");
    String className = clazz.getName();

    List<String> command = new ArrayList<>();
    command.add(javaBin);
    command.addAll(jvmArgs);
    command.add("-cp");
    command.add(classpath);
    command.add(className);
    command.addAll(args);

    ProcessBuilder builder = new ProcessBuilder(command);

    LOG.info(
        String.format(
            "Starting process: %s", builder.command().stream().collect(Collectors.joining(" "))));

    Service.BuiltProcess builtProcess = Service.BuiltProcess.start(builder);
    Runtime.getRuntime().addShutdownHook(new Thread(builtProcess.process::destroy));
    return builtProcess;
  }

  public static String readResourceAsString(String resourcePath) {
    return new BufferedReader(
            new InputStreamReader(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath),
                StandardCharsets.UTF_8))
        .lines()
        .collect(Collectors.joining("\n"));
  }

  public static String renderTemplate(String template, Map<String, String> vars) {
    template = StringSubstitutor.replaceSystemProperties(template);
    return new StringSubstitutor(vars).replace(template);
  }

  public static int nextFreePort(String serviceId, int portFrom, int portTo) {

    int minPort = Math.min(portFrom, portTo);
    int maxPort = Math.max(portFrom, portTo);

    // The randomization here has the nice property of being stable if the port range is changed, so
    // generally
    // the port choices will be "sticky" for a given appId even if the range gets adjusted.

    byte[] idMd5 = DigestUtils.md5(serviceId);
    long idSeed = ByteBuffer.wrap(idMd5).getLong();
    Random rng = new Random(idSeed);

    class PortChoice {

      final int port;
      final int weight;

      PortChoice(int port, int weight) {
        this.port = port;
        this.weight = weight;
      }
    }

    List<PortChoice> choices = new ArrayList<>(maxPort - minPort);
    for (int p = 0; p < maxPort; ++p) {
      int nextInt = rng.nextInt();
      if (p < minPort) continue; // Assign the same weights to the ports no matter the range
      choices.add(new PortChoice(p, nextInt));
    }

    Collections.sort(choices, (a, b) -> a.weight != b.weight ? (a.weight < b.weight ? -1 : 1) : 0);

    for (PortChoice choice : choices) {
      try {
        new ServerSocket(choice.port).close();
        return choice.port;
      } catch (IOException e) {
        continue;
      }
    }

    return -1;
  }

  public static void main(String[] args) throws ParseException, InterruptedException {

    final Kafkita kafkita = run(args);

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    LOG.warn(
                        String.format("JVM terminated, Kafkita stopping: id=%s", kafkita.getId()));
                    kafkita.waitForStop();
                  } catch (InterruptedException ex) {
                    LOG.warn(String.format("Kafkita shutdown interrupted: id=%s", kafkita.getId()));
                  }
                }));

    while (true) {
      Thread.sleep(1000);
    }
  }

  public static Kafkita run(String[] args) throws ParseException, InterruptedException {

    Options options = new Options();

    options.addOption("name", true, "Name of Kafkita stack instance");
    options.addOption("dir", true, "Directory for kafkita instance state");
    options.addOption("zkPortRange", true, "Zookeeper Port Range");
    options.addOption("kafkaPortRange", true, "Kafka Port Range");
    options.addOption("zkOnly", false, "Only start zookeeper");

    options.addOption("maxStartTries", true, "Max number of times to attempt to start");
    options.addOption("maxStartWaitSecs", true, "Max duration to wait after failure to start");
    options.addOption("maxHealthTries", true, "Max number of times to check health");
    options.addOption("maxHealthWaitSecs", true, "Max duration to wait after health check fails");
    options.addOption("maxStopWaitSecs", true, "Max duration to wait for clean stop");

    options.addOption(
        Option.builder()
            .longOpt("zP")
            .argName("property=value")
            .hasArgs()
            .valueSeparator()
            .numberOfArgs(2)
            .desc("Set zookeeper property")
            .build());

    options.addOption(
        Option.builder()
            .longOpt("zD")
            .argName("property=value")
            .hasArgs()
            .valueSeparator()
            .numberOfArgs(2)
            .desc("Set zookeeper JVM property")
            .build());

    options.addOption(
        Option.builder()
            .longOpt("kP")
            .argName("property=value")
            .hasArgs()
            .valueSeparator()
            .numberOfArgs(2)
            .desc("Set kafka broker property")
            .build());

    options.addOption(
        Option.builder()
            .longOpt("kD")
            .argName("property=value")
            .hasArgs()
            .valueSeparator()
            .numberOfArgs(2)
            .desc("Set kafka broker JVM property")
            .build());

    CommandLineParser parser = new DefaultParser();
    CommandLine cl = parser.parse(options, args);

    File dir = new File(DEFAULT_STACK_DIR);
    if (cl.hasOption("dir")) {
      dir = new File(cl.getOptionValue("dir"));
    }

    String name = null;
    if (cl.hasOption("name")) {
      name = cl.getOptionValue("name");
    }

    int[] zkPortRange = null;
    if (cl.hasOption("zkPortRange")) {
      String[] zkPortRangeStr = cl.getOptionValue("zkPortRange").split(":");
      zkPortRange =
          new int[] {Integer.parseInt(zkPortRangeStr[0]), Integer.parseInt(zkPortRangeStr[1])};
    }

    int[] kafkaPortRange = null;
    if (cl.hasOption("kafkaPortRange")) {
      String[] zkPortRangeStr = cl.getOptionValue("kafkaPortRange").split(":");
      kafkaPortRange =
          new int[] {Integer.parseInt(zkPortRangeStr[0]), Integer.parseInt(zkPortRangeStr[1])};
    }

    boolean zkOnly = cl.hasOption("zkOnly");

    Kafkita kafkita = new Kafkita(name, dir, zkPortRange, kafkaPortRange);

    if (cl.hasOption("maxStartTries"))
      kafkita.getHealthThresholds().maxStartTries =
          Integer.parseInt(cl.getOptionValue("maxStartTries"));
    if (cl.hasOption("maxStartWaitSecs"))
      kafkita.getHealthThresholds().maxStartWait =
          Duration.ofSeconds(Integer.parseInt(cl.getOptionValue("maxStartWaitSecs")));
    if (cl.hasOption("maxHealthTries"))
      kafkita.getHealthThresholds().maxStartTries =
          Integer.parseInt(cl.getOptionValue("maxHealthTries"));
    if (cl.hasOption("maxHealthWaitSecs"))
      kafkita.getHealthThresholds().maxStartWait =
          Duration.ofSeconds(Integer.parseInt(cl.getOptionValue("maxHealthWaitSecs")));
    if (cl.hasOption("maxStopWaitSecs"))
      kafkita.getHealthThresholds().maxStopWait =
          Duration.ofSeconds(Integer.parseInt(cl.getOptionValue("maxStopWaitSecs")));

    if (cl.hasOption("zP")) kafkita.getAddlZkProperties().putAll(cl.getOptionProperties("zP"));
    if (cl.hasOption("zD")) kafkita.getAddlZkJvmProperties().putAll(cl.getOptionProperties("zD"));
    if (cl.hasOption("kP")) kafkita.getAddlProperties().putAll(cl.getOptionProperties("kP"));
    if (cl.hasOption("kD")) kafkita.getAddlJvmProperties().putAll(cl.getOptionProperties("kD"));

    kafkita.waitForStart(zkOnly);

    return kafkita;
  }
}
