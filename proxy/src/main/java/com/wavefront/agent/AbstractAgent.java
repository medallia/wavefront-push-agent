package com.wavefront.agent;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.google.gson.Gson;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.wavefront.api.AgentAPI;
import com.wavefront.api.agent.AgentConfiguration;
import com.wavefront.common.Clock;
import com.wavefront.metrics.ExpectedAgentMetric;
import com.wavefront.metrics.JsonMetricsGenerator;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;

import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJacksonProvider;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent that runs remotely on a server collecting metrics.
 *
 * @author Clement Pang (clement@wavefront.com)
 */
public abstract class AbstractAgent {

  protected static final Logger logger = Logger.getLogger("agent");

  private static final Gson GSON = new Gson();
  private static final int GRAPHITE_LISTENING_PORT = 2878;
  private static final int OPENTSDB_LISTENING_PORT = 4242;
  private static final int HTTP_JSON_LISTENING_PORT = 3878;

  @Parameter(names = {"-f", "--file"}, description =
      "Proxy configuration file")
  private String pushConfigFile = null;

  @Parameter(names = {"-c", "--config"}, description =
      "Local configuration file to use (overrides using the server to obtain a config file)")
  private String configFile = null;

  @Parameter(names = {"-p", "--prefix"}, description =
      "Prefix to prepend to all push metrics before reporting.")
  protected String prefix = null;

  @Parameter(names = {"-t", "--token"}, description =
      "Token to auto-register agent with an account")
  private String token = null;

  @Parameter(names = {"-l", "--loglevel"}, description =
      "Log level for push data (NONE/SUMMARY/DETAILED); NONE is default")
  protected String pushLogLevel = "NONE";

  @Parameter(names = {"-v", "--validationlevel"}, description =
      "Validation level for push data (NO_VALIDATION/NUMERIC_ONLY/TEXT_ONLY/ALL); NO_VALIDATION is default")
  protected String pushValidationLevel = "NUMERIC_ONLY";

  @Parameter(names = {"-h", "--host"}, description = "Server URL")
  protected String server = "http://localhost:8082/api/";

  @Parameter(names = {"--buffer"}, description = "File to use for buffering failed transmissions to Wavefront servers" +
      ". Defaults to buffer.")
  private String bufferFile = "buffer";

  @Parameter(names = {"--retryThreads"}, description = "Number of threads retrying failed transmissions. Defaults to " +
      "the number of processors (min. 4). Buffer files are maxed out at 2G each so increasing the number of retry " +
      "threads effectively governs the maximum amount of space the agent will use to buffer points locally")
  protected int retryThreads = Math.max(4, Runtime.getRuntime().availableProcessors());

  @Parameter(names = {"--purgeBuffer"}, description = "Whether to purge the retry buffer on start-up. Defaults to " +
      "false.")
  private boolean purgeBuffer = false;

  @Parameter(names = {"--pushFlushInterval"}, description = "Milliseconds between flushes to . Defaults to 1000 ms")
  protected long pushFlushInterval = 1000;

  @Parameter(names = {"--pushFlushMaxPoints"}, description = "Maximum allowed points in a single push flush. Defaults" +
      " to 50,000")
  protected int pushFlushMaxPoints = 50000;

  @Parameter(names = {"--pushBlockedSamples"}, description = "Max number of blocked samples to print to log. Defaults" +
      " to 0.")
  protected int pushBlockedSamples = 0;

  @Parameter(names = {"--pushListenerPorts"}, description = "Comma-separated list of ports to listen on. Defaults to " +
      "2878.")
  protected String pushListenerPorts = "" + GRAPHITE_LISTENING_PORT;

  @Parameter(names = {"--graphitePorts"}, description = "Comma-separated list of ports to listen on for graphite " +
      "data. Defaults to empty list.")
  protected String graphitePorts = "";

  @Parameter(names = {"--graphiteFormat"}, description = "Comma-separated list of metric segments to extract and " +
      "reassemble as the hostname (1-based).")
  protected String graphiteFormat = "";

  @Parameter(names = {"--graphiteDelimiters"}, description = "Concatenated delimiters that should be replaced in the " +
      "extracted hostname with dots. Defaults to underscores (_).")
  protected String graphiteDelimiters = "_";

  @Parameter(names = {"--httpJsonPorts"}, description = "Comma-separated list of ports to listen on for json metrics " +
      "data. Binds, by default, to " + HTTP_JSON_LISTENING_PORT)
  protected String httpJsonPorts = "" + HTTP_JSON_LISTENING_PORT;

  @Parameter(names = {"--hostname"}, description = "Hostname for the agent. Defaults to FQDN of machine.")
  protected String hostname;

  @Parameter(names = {"--idFile"}, description = "File to read agent id from. Defaults to ~/.dshell/id")
  protected String idFile = null;

  @Parameter(names = {"--graphiteWhitelistRegex"}, description = "(DEPRECATED for whitelistRegex)", hidden = true)
  protected String graphiteWhitelistRegex;

  @Parameter(names = {"--graphiteBlacklistRegex"}, description = "(DEPRECATED for blacklistRegex)", hidden = true)
  protected String graphiteBlacklistRegex;

  @Parameter(names = {"--whitelistRegex"}, description = "Regex pattern (java.util.regex) that graphite input lines must match to be accepted")
  protected String whitelistRegex;

  @Parameter(names = {"--blacklistRegex"}, description = "Regex pattern (java.util.regex) that graphite input lines must NOT match to be accepted")
  protected String blacklistRegex;

  @Parameter(names = {"--opentsdbPorts"}, description = "Comma-separated list of ports to listen on for opentsdb " +
      "data. Defaults to: " + OPENTSDB_LISTENING_PORT)
  protected String opentsdbPorts = "" + OPENTSDB_LISTENING_PORT;

  @Parameter(names = {"--opentsdbWhitelistRegex"}, description = "Regex pattern (java.util.regex) that opentsdb input lines must match to be accepted")
  protected String opentsdbWhitelistRegex;

  @Parameter(names = {"--opentsdbBlacklistRegex"}, description = "Regex pattern (java.util.regex) that opentsdb input lines must NOT match to be accepted")
  protected String opentsdbBlacklistRegex;

  @Parameter(names = {"--splitPushWhenRateLimited"}, description = "Whether to split the push batch size when the push is rejected by Wavefront due to rate limit.  Default false.")
  protected boolean splitPushWhenRateLimited = false;

  @Parameter(names = {"--retryBackoffBaseSeconds"}, description = "For exponential backoff when retry threads are throttled, the base (a in a^b) in seconds.  Default 2.0")
  protected double retryBackoffBaseSeconds = 2.0;

  @Parameter(names = {"--customSourceTags"}, description = "Comma separated list of point tag keys that should be treated as the source in Wavefront in the absence of a tag named source or host")
  protected String customSourceTagsProperty = "fqdn";

  @Parameter(description = "Unparsed parameters")
  protected List<String> unparsed_params;

  protected QueuedAgentService agentAPI;
  protected ResourceBundle props;
  protected final AtomicLong bufferSpaceLeft = new AtomicLong();
  protected List<String> customSourceTags = new ArrayList<String>();

  protected final boolean localAgent;
  protected final boolean pushAgent;

  /**
   * Executors for support tasks.
   */
  private final ScheduledExecutorService auxiliaryExecutor = Executors.newScheduledThreadPool(1);
  protected UUID agentId;
  private final Runnable updateConfiguration = new Runnable() {
    @Override
    public void run() {
      try {
        AgentConfiguration config = fetchConfig();
        if (config != null) {
          processConfiguration(config);
        }
      } finally {
        auxiliaryExecutor.schedule(this, 60, TimeUnit.SECONDS);
      }
    }
  };

  public AbstractAgent() {
    this(false, false);
  }

  public AbstractAgent(boolean localAgent, boolean pushAgent) {
    this.pushAgent = pushAgent;
    this.localAgent = localAgent;
    try {
      this.hostname = InetAddress.getLocalHost().getCanonicalHostName();
      Metrics.newGauge(ExpectedAgentMetric.BUFFER_BYTES_LEFT.metricName,
          new Gauge<Long>() {
            @Override
            public Long value() {
              return bufferSpaceLeft.get();
            }
          }
      );
    } catch (UnknownHostException e) {
      throw Throwables.propagate(e);
    }
  }

  protected abstract void startListeners();

  private void loadListenerConfigurationFile() throws IOException {
    // If they've specified a push configuration file, override the command line values
    if (pushConfigFile != null) {
      Properties prop = new Properties();
      try {
        prop.load(new FileInputStream(pushConfigFile));
        prefix = Strings.emptyToNull(prop.getProperty("prefix", prefix));
        pushLogLevel = prop.getProperty("pushLogLevel", pushLogLevel);
        pushValidationLevel = prop.getProperty("pushValidationLevel", pushValidationLevel);
        token = prop.getProperty("token", token);
        server = prop.getProperty("server", server);
        hostname = prop.getProperty("hostname", hostname);
        idFile = prop.getProperty("idFile", idFile);
        pushFlushInterval = Integer.parseInt(prop.getProperty("pushFlushInterval",
            String.valueOf(pushFlushInterval)));
        pushFlushMaxPoints = Integer.parseInt(prop.getProperty("pushFlushMaxPoints",
            String.valueOf(pushFlushMaxPoints)));
        pushBlockedSamples = Integer.parseInt(prop.getProperty("pushBlockedSamples",
            String.valueOf(pushBlockedSamples)));
        pushListenerPorts = prop.getProperty("pushListenerPorts", pushListenerPorts);
        httpJsonPorts = prop.getProperty("jsonListenerPorts", httpJsonPorts);
        graphitePorts = prop.getProperty("graphitePorts", graphitePorts);
        graphiteFormat = prop.getProperty("graphiteFormat", graphiteFormat);
        graphiteDelimiters = prop.getProperty("graphiteDelimiters", graphiteDelimiters);
        graphiteWhitelistRegex = prop.getProperty("graphiteWhitelistRegex", graphiteWhitelistRegex);
        graphiteBlacklistRegex = prop.getProperty("graphiteBlacklistRegex", graphiteBlacklistRegex);
        whitelistRegex = prop.getProperty("whitelistRegex", whitelistRegex);
        blacklistRegex = prop.getProperty("blacklistRegex", blacklistRegex);
        opentsdbPorts = prop.getProperty("opentsdbPorts", opentsdbPorts);
        opentsdbWhitelistRegex = prop.getProperty("opentsdbWhitelistRegex", opentsdbWhitelistRegex);
        opentsdbBlacklistRegex = prop.getProperty("opentsdbBlacklistRegex", opentsdbBlacklistRegex);
        splitPushWhenRateLimited = Boolean.parseBoolean(prop.getProperty("splitPushWhenRateLimited",
            String.valueOf(splitPushWhenRateLimited)));
        retryBackoffBaseSeconds = Double.parseDouble(prop.getProperty("retryBackoffBaseSeconds",
            String.valueOf(retryBackoffBaseSeconds)));
        customSourceTagsProperty = prop.getProperty("customSourceTags", customSourceTagsProperty);
        logger.warning("Loaded configuration file " + pushConfigFile);
      } catch (Throwable exception) {
        logger.severe("Could not load configuration file " + pushConfigFile);
        throw exception;
      }

      // Compatibility with deprecated fields
      if (whitelistRegex == null && graphiteWhitelistRegex != null) {
        whitelistRegex = graphiteWhitelistRegex;
      }

      if (blacklistRegex == null && graphiteBlacklistRegex != null) {
        blacklistRegex = graphiteBlacklistRegex;
      }

      PostPushDataTimedTask.setPointsPerBatch(pushFlushMaxPoints);
      QueuedAgentService.setSplitBatchSize(pushFlushMaxPoints);
      QueuedAgentService.setRetryBackoffBaseSeconds(retryBackoffBaseSeconds);
    }
  }

  /**
   * Entry-point for the application.
   *
   * @param args Command-line parameters passed on to JCommander to configure the daemon.
   */
  public void start(String[] args) throws IOException {
    try {
      logger.info("Arguments: " + Joiner.on(", ").join(args));
      new JCommander(this, args);
      if (unparsed_params != null) {
        logger.info("Unparsed arguments: " + Joiner.on(", ").join(unparsed_params));
      }

      /* ------------------------------------------------------------------------------------
       * Configuration Setup.
       * ------------------------------------------------------------------------------------ */

      // 1. Load the listener configuration, if it exists
      loadListenerConfigurationFile();

      // 2. Read or create the unique Id for the daemon running on this machine.
      readOrCreateDaemonId();

      // read build information.
      props = ResourceBundle.getBundle("build");

      // create List of custom tags from the configuration string
      String[] tags = customSourceTagsProperty.split(",");
      for (String tag : tags) {
        tag = tag.trim();
        if (!customSourceTags.contains(tag)) {
          customSourceTags.add(tag);
        } else {
          logger.warning("Custom source tag: " + tag + " was repeated. Check the customSourceTags property in wavefront.conf");
        }
      }

      // 3. Setup proxies.
      AgentAPI service = createAgentService();
      try {
        setupQueueing(service);
      } catch (IOException e) {
        logger.log(Level.SEVERE, "Cannot setup local file for queueing due to IO error", e);
        throw e;
      }

      // 4. Start the (push) listening endpoints
      startListeners();

      // 5. Poll or read the configuration file to use.
      AgentConfiguration config;
      if (configFile != null) {
        logger.info("Loading configuration file from: " + configFile);
        try {
          config = GSON.fromJson(new FileReader(configFile),
              AgentConfiguration.class);
        } catch (FileNotFoundException e) {
          throw new RuntimeException("Cannot read config file: " + configFile);
        }
        try {
          config.validate(localAgent);
        } catch (RuntimeException ex) {
          logger.log(Level.SEVERE, "cannot parse config file", ex);
          throw new RuntimeException("cannot parse config file", ex);
        }
        agentId = null;
      } else {
        config = fetchConfig();
        logger.info("scheduling regular configuration polls");
        auxiliaryExecutor.schedule(updateConfiguration, 10, TimeUnit.SECONDS);

        URI url = URI.create(server);
        if (url.getPath().endsWith("/api/")) {
          String configurationLogMessage = "TO CONFIGURE THIS PROXY AGENT, USE THIS KEY: " + agentId;
          logger.warning(Strings.repeat("*", configurationLogMessage.length()));
          logger.warning(configurationLogMessage);
          logger.warning(Strings.repeat("*", configurationLogMessage.length()));
        }
      }
      // 6. Setup work units and targets based on the configuration.
      if (config != null) {
        logger.info("initial configuration is available, setting up proxy agent");
        processConfiguration(config);
      }
      logger.info("setup complete");
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Aborting start-up", t);
      System.exit(1);
    }
  }

  /**
   * Create RESTeasy proxies for remote calls via HTTP.
   */
  protected AgentAPI createAgentService() {
    ResteasyProviderFactory factory = ResteasyProviderFactory.getInstance();
    factory.registerProvider(JsonNodeWriter.class);
    factory.registerProvider(ResteasyJacksonProvider.class);
    PoolingClientConnectionManager connectionManager = new PoolingClientConnectionManager();
    connectionManager.setMaxTotal(200);
    connectionManager.setDefaultMaxPerRoute(100);
    HttpClient httpClient = new DefaultHttpClient(connectionManager);
    HttpParams httpParams = httpClient.getParams();
    HttpConnectionParams.setConnectionTimeout(httpParams, 5000); //5s
    HttpConnectionParams.setSoTimeout(httpParams, 60000); //60s
    ResteasyClient client = new ResteasyClientBuilder().
        httpEngine(new ApacheHttpClient4Engine(httpClient)).
        providerFactory(factory).
        build();
    ResteasyWebTarget target = client.target(server);
    return target.proxy(AgentAPI.class);
  }

  private void setupQueueing(AgentAPI service) throws IOException {
    agentAPI = new QueuedAgentService(service, bufferFile, retryThreads,
        Executors.newScheduledThreadPool(retryThreads + 1, new ThreadFactory() {

          private AtomicLong counter = new AtomicLong();

          @Override
          public Thread newThread(Runnable r) {
            Thread toReturn = new Thread(r);
            toReturn.setName("submission worker: " + counter.getAndIncrement());
            return toReturn;
          }
        }), purgeBuffer, agentId, splitPushWhenRateLimited, pushLogLevel);
  }

  /**
   * Read or create the Daemon id for this machine. Reads from ~/.dshell/id.
   */
  private void readOrCreateDaemonId() {
    File agentIdFile;
    if (idFile != null) {
      agentIdFile = new File(idFile);
    } else {
      File userHome = new File(System.getProperty("user.home"));
      if (!userHome.exists() || !userHome.isDirectory()) {
        logger.severe("Cannot read from user.home, quitting");
        System.exit(1);
      }
      File configDirectory = new File(userHome, ".dshell");
      if (configDirectory.exists()) {
        if (!configDirectory.isDirectory()) {
          logger.severe(configDirectory + " must be a directory!");
          System.exit(1);
        }
      } else {
        if (!configDirectory.mkdir()) {
          logger.severe("Cannot create .dshell directory under " + userHome);
          System.exit(1);
        }
      }
      agentIdFile = new File(configDirectory, "id");
    }
    if (agentIdFile.exists()) {
      if (agentIdFile.isFile()) {
        try {
          agentId = UUID.fromString(Files.readFirstLine(agentIdFile, Charsets.UTF_8));
          logger.info("Proxy Agent Id read from file: " + agentId);
        } catch (IllegalArgumentException ex) {
          logger.severe("Cannot read proxy agent id from " + agentIdFile +
              ", content is malformed");
          System.exit(1);
        } catch (IOException e) {
          logger.log(Level.SEVERE, "Cannot read from " + agentIdFile, e);
          System.exit(1);
        }
      } else {
        logger.severe(agentIdFile + " is not a file!");
        System.exit(1);
      }
    } else {
      agentId = UUID.randomUUID();
      logger.info("Proxy Agent Id created: " + agentId);
      try {
        Files.write(agentId.toString(), agentIdFile, Charsets.UTF_8);
      } catch (IOException e) {
        logger.severe("Cannot write to " + agentIdFile);
        System.exit(1);
      }
    }
  }

  /**
   * Fetch configuration of the daemon from remote server.
   *
   * @return Fetched configuration. {@code null} if the configuration is invalid.
   */
  private AgentConfiguration fetchConfig() {
    AgentConfiguration newConfig;
    try {
      logger.info("fetching configuration from server at: " + server);
      File buffer = new File(bufferFile).getAbsoluteFile();
      try {
        while (buffer != null && buffer.getUsableSpace() == 0) {
          buffer = buffer.getParentFile();
        }
        if (buffer != null) {
          // the amount of space is limited by the number of retryThreads.
          bufferSpaceLeft.set(Math.min((long) Integer.MAX_VALUE * retryThreads, buffer.getUsableSpace()));
        }
      } catch (Throwable t) {
        logger.warning("cannot compute remaining space in buffer file partition: " + t);
      }

      JsonNode agentMetrics = JsonMetricsGenerator.generateJsonMetrics(Metrics.defaultRegistry(), true, true, true);
      newConfig = agentAPI.checkin(agentId, hostname, token, props.getString("build.version"),
          System.currentTimeMillis(), localAgent, agentMetrics, pushAgent);
    } catch (Exception ex) {
      logger.warning("cannot fetch proxy agent configuration from remote server: " + Throwables.getRootCause(ex));
      return null;
    }
    if (newConfig.currentTime != null) {
      Clock.set(newConfig.currentTime);
    }
    try {
      newConfig.validate(localAgent);
    } catch (Exception ex) {
      logger.log(Level.WARNING, "configuration file read from server is invalid", ex);
      try {
        agentAPI.agentError(agentId, "Configuration file is invalid: " + ex.toString());
      } catch (Exception e) {
        logger.log(Level.WARNING, "cannot report error to collector", e);
      }
      return null;
    }
    return newConfig;
  }

  /**
   * Actual agents can do additional configuration.
   *
   * @param config The configuration to process.
   */
  protected void processConfiguration(AgentConfiguration config) {
    try {
      agentAPI.agentConfigProcessed(agentId);
    } catch (RuntimeException e) {
      // cannot throw or else configuration update thread would die.
    }
  }
}
