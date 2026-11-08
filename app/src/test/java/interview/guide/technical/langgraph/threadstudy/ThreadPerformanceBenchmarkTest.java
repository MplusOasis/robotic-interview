package interview.guide.technical.langgraph.threadstudy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Synthetic checkpoint benchmark; no Spring context, production DB, Redis or real model. */
@EnabledIfEnvironmentVariable(named = "P102_THREAD_BENCHMARK", matches = "true")
class ThreadPerformanceBenchmarkTest {
  enum Scope { SESSION, TURN, RUN }
  static final String PAYLOAD = "x".repeat(4096);
  static final Path OUTPUT = Path.of("build/reports/p102-thread-benchmark.json");
  static volatile Sample active;
  static HikariDataSource pool;
  static JdbcTemplate jdbc;

  static class State extends AgentState {
    State(Map<String, Object> data) { super(data); }
  }

  static class Sample {
    final Map<String, Object> data = new LinkedHashMap<>();
    final Map<String, List<Double>> timings = new LinkedHashMap<>();
    final AtomicLong heapPeak = new AtomicLong();
    final AtomicInteger connectionsPeak = new AtomicInteger();
    final AtomicInteger waitingPeak = new AtomicInteger();

    Sample() {
      for (String key : List.of("runMs", "turnMs", "sessionMs", "putMs", "getMs", "releaseMs", "acquireMs")) {
        timings.put(key, Collections.synchronizedList(new ArrayList<>()));
      }
      data.put("timings", timings);
    }

    void add(String key, long nanos) { timings.get(key).add(nanos / 1_000_000.0); }
  }

  static PostgresSaverV2 rawSaver() throws Exception {
    var serializer = new JacksonStateSerializer<State>(State::new) {};
    return PostgresSaverV2.builder().datasource(pool).stateSerializer(serializer)
        .createTables(false).dropTablesFirst(false).build();
  }

  static BaseCheckpointSaver timedSaver(PostgresSaverV2 delegate) {
    return (BaseCheckpointSaver) Proxy.newProxyInstance(BaseCheckpointSaver.class.getClassLoader(),
        new Class<?>[]{BaseCheckpointSaver.class}, (proxy, method, args) -> {
          long start = System.nanoTime();
          try {
            return method.invoke(delegate, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          } finally {
            Sample sample = active;
            String key = method.getName() + "Ms";
            if (sample != null && sample.timings.containsKey(key)) {
              sample.add(key, System.nanoTime() - start);
            }
          }
        });
  }

  static CompiledGraph<State> graph(BaseCheckpointSaver saver, AtomicInteger failCalls) throws Exception {
    return new StateGraph<State>(new JacksonStateSerializer<State>(State::new) {})
        .addNode("one", node_async(s -> Map.of("stage", 1)))
        .addNode("two", node_async(s -> {
          if (failCalls != null && failCalls.getAndIncrement() == 0) {
            throw new IllegalStateException("intentional-benchmark-recovery");
          }
          return Map.of("stage", 2);
        }))
        .addNode("three", node_async(s -> Map.of("stage", 3)))
        .addEdge(START, "one").addEdge("one", "two").addEdge("two", "three").addEdge("three", END)
        .compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build());
  }

  static Map<String, Object> input(String session, String turn, String run) {
    // All State keys are replaced, including stage, so shared threads cannot retain old fields.
    return Map.of("schemaVersion", 1, "sessionId", session, "turnId", turn,
        "workflowRunId", run, "eventId", UUID.randomUUID().toString(), "payload", PAYLOAD, "stage", 0);
  }

  static RunnableConfig config(Scope scope, String session, String turn, String run) {
    return RunnableConfig.builder().threadId(switch (scope) {
      case SESSION -> session;
      case TURN -> turn;
      case RUN -> run;
    }).build();
  }

  static long cpuNs() {
    return ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
  }

  static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
  }

  static long gcMs() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
  }

  static Map<String, Object> cgroup(GenericContainer<?> pg) throws Exception {
    var result = pg.execInContainer("sh", "-c",
        "cat /sys/fs/cgroup/cpu.stat; echo memory_current; cat /sys/fs/cgroup/memory.current; "
            + "echo memory_peak; cat /sys/fs/cgroup/memory.peak");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("exitCode", result.getExitCode());
    data.put("raw", result.getStdout());
    data.put("stderr", result.getStderr());
    return data;
  }

  static void runSession(Scope scope, int turns, BaseCheckpointSaver saver,
                         CompiledGraph<State> graph, CountDownLatch gate) throws Exception {
    gate.await();
    long sessionStart = System.nanoTime();
    String session = UUID.randomUUID().toString();
    RunnableConfig last = null;
    for (int t = 0; t < turns; t++) {
      long turnStart = System.nanoTime();
      String turn = UUID.randomUUID().toString();
      // Two logical runs per turn model generation/revision and answer handling.
      for (int r = 0; r < 2; r++) {
        String run = UUID.randomUUID().toString();
        last = config(scope, session, turn, run);
        long start = System.nanoTime();
        var result = graph.invoke(GraphInput.args(input(session, turn, run)), last).orElseThrow();
        active.add("runMs", System.nanoTime() - start);
        assertThat(result.value("workflowRunId").orElseThrow()).isEqualTo(run);
        assertThat(result.value("stage").orElseThrow()).isEqualTo(3);
        if (scope == Scope.RUN) { saver.release(last); }
      }
      if (scope == Scope.TURN) { saver.release(last); }
      active.add("turnMs", System.nanoTime() - turnStart);
    }
    if (scope == Scope.SESSION) { saver.release(last); }
    active.add("sessionMs", System.nanoTime() - sessionStart);
  }

  static Map<String, Object> measure(GenericContainer<?> pg, Scope scope, int turns,
                                     int concurrency, int iteration) throws Exception {
    // This is the disposable p102_benchmark container only; reset outside the measured interval.
    jdbc.execute("TRUNCATE LG4JCheckpoint, LG4JThreadTag, LG4JThread RESTART IDENTITY");
    var saver = timedSaver(rawSaver()); // One shared Saver instance per scenario for all three schemes.
    var graph = graph(saver, null);
    Sample sample = new Sample();
    sample.data.put("scope", scope.name());
    sample.data.put("turns", turns);
    sample.data.put("concurrency", concurrency);
    sample.data.put("iteration", iteration);
    sample.data.put("warmup", iteration == 0);
    sample.data.put("runsPerTurn", 2);
    sample.data.put("cgroupBefore", cgroup(pg));
    sample.data.put("heapBeforeBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    ManagementFactory.getMemoryPoolMXBeans().forEach(bean -> bean.resetPeakUsage());
    var executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(concurrency));
    var monitor = new ScheduledThreadPoolExecutor(1);
    var gate = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    active = sample;
    monitor.scheduleAtFixedRate(() -> {
      sample.heapPeak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
      sample.connectionsPeak.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
      sample.waitingPeak.accumulateAndGet(pool.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max);
    }, 0, 20, TimeUnit.MILLISECONDS);
    for (int c = 0; c < concurrency; c++) {
      futures.add(executor.submit(() -> { runSession(scope, turns, saver, graph, gate); return null; }));
    }
    long cpuBefore = cpuNs();
    long gcBefore = gcCount();
    long gcTimeBefore = gcMs();
    long start = System.nanoTime();
    try {
      gate.countDown();
      for (var future : futures) { future.get(10, TimeUnit.MINUTES); }
    } finally {
      sample.data.put("wallMs", (System.nanoTime() - start) / 1_000_000.0);
      sample.data.put("jvmCpuMs", (cpuNs() - cpuBefore) / 1_000_000.0);
      sample.data.put("gcCount", gcCount() - gcBefore);
      sample.data.put("gcMs", gcMs() - gcTimeBefore);
      monitor.shutdownNow();
      executor.shutdownNow();
      monitor.awaitTermination(10, TimeUnit.SECONDS);
      executor.awaitTermination(10, TimeUnit.SECONDS);
      active = null;
    }
    sample.data.put("cgroupAfter", cgroup(pg));
    sample.data.put("heapAfterBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    sample.data.put("heapSampledPeakBytes", sample.heapPeak.get());
    sample.data.put("heapPoolPeakSumBytes", ManagementFactory.getMemoryPoolMXBeans().stream()
        .filter(bean -> bean.getType() == MemoryType.HEAP).mapToLong(bean -> bean.getPeakUsage().getUsed()).sum());
    sample.data.put("activeConnectionsSampledPeak", sample.connectionsPeak.get());
    sample.data.put("waitingConnectionsSampledPeak", sample.waitingPeak.get());
    sample.data.put("activeConnectionsAfter", pool.getHikariPoolMXBean().getActiveConnections());
    sample.data.put("serverConnectionsAfter", jdbc.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()", Long.class));
    sample.data.put("storage", jdbc.queryForMap("SELECT "
        + "(SELECT count(*) FROM LG4JThread) AS threads, "
        + "(SELECT count(*) FROM LG4JThreadTag) AS tags, count(*) AS checkpoints, "
        + "coalesce(sum(octet_length(state_data)),0) AS state_bytes, "
        + "min(octet_length(state_data)) AS min_state_bytes, max(octet_length(state_data)) AS max_state_bytes "
        + "FROM LG4JCheckpoint"));
    sample.data.put("relations", jdbc.queryForList("SELECT relname, "
        + "pg_table_size(oid) AS table_bytes, pg_indexes_size(oid) AS index_bytes, "
        + "pg_total_relation_size(oid) AS total_bytes FROM pg_class "
        + "WHERE relname IN ('lg4jthread','lg4jthreadtag','lg4jcheckpoint') ORDER BY relname"));
    assertThat(sample.timings.get("runMs")).hasSize(turns * concurrency * 2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM LG4JThread", Long.class)).isZero();
    long cleanupStart = System.nanoTime();
    try (Connection connection = pool.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM LG4JCheckpoint");
        statement.executeUpdate("DELETE FROM LG4JThreadTag");
        statement.executeUpdate("DELETE FROM LG4JThread");
        connection.commit();
      }
    }
    sample.data.put("cleanupMs", (System.nanoTime() - cleanupStart) / 1_000_000.0);
    return sample.data;
  }

  @Test
  @DisplayName("独立PG17三种Thread四种场景预热及三轮量化测量")
  void benchmark() throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    List<Map<String, Object>> samples = new ArrayList<>();
    List<Map<String, Object>> recovery = new ArrayList<>();
    report.put("samples", samples);
    report.put("recovery", recovery);
    report.put("synthetic", true);
    report.put("java", System.getProperty("java.version"));
    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    report.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
    report.put("langgraph", "1.9.7");
    report.put("payloadBytes", PAYLOAD.length());
    try (var pg = new GenericContainer<>("pgvector/pgvector:pg17")
        .withEnv("POSTGRES_DB", "p102_benchmark").withEnv("POSTGRES_USER", "probe")
        .withEnv("POSTGRES_PASSWORD", UUID.randomUUID().toString()).withExposedPorts(5432)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
            .withMemory(1024L * 1024 * 1024).withNanoCPUs(2_000_000_000L))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
            .withStartupTimeout(Duration.ofMinutes(2)))) {
      pg.start();
      var config = new HikariConfig();
      config.setJdbcUrl("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/p102_benchmark");
      config.setUsername("probe");
      config.setPassword(pg.getEnvMap().get("POSTGRES_PASSWORD"));
      config.setMaximumPoolSize(5);
      config.setMinimumIdle(5);
      config.setMetricsTrackerFactory((name, stats) -> new IMetricsTracker() {
        @Override public void recordConnectionAcquiredNanos(long elapsed) {
          Sample sample = active;
          if (sample != null) { sample.add("acquireMs", elapsed); }
        }
      });
      try (var dataSource = new HikariDataSource(config)) {
        pool = dataSource;
        jdbc = new JdbcTemplate(pool);
        String version = jdbc.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("17.11");
        report.put("postgres", version);
        report.put("containerImageId", pg.getContainerInfo().getImageId());
        report.put("containerCpuLimit", 2);
        report.put("containerMemoryLimitBytes", 1024L * 1024 * 1024);
        report.put("postgresSettings", jdbc.queryForList("SELECT name,setting,unit FROM pg_settings "
            + "WHERE name IN ('shared_buffers','fsync','synchronous_commit','max_connections')"));
        var sql = new ClassPathResource("db/migration/v2.1__init.sql");
        assertThat(sql.getURL().toString()).contains("langgraph4j-postgres-saver-1.9.7.jar");
        try (var connection = pool.getConnection()) { ScriptUtils.executeSqlScript(connection, sql); }
        for (int turns : List.of(10, 30)) {
          for (int concurrency : List.of(1, 5)) {
            for (int iteration = 0; iteration <= 3; iteration++) {
              for (int offset = 0; offset < 3; offset++) {
                Scope scope = Scope.values()[(iteration + offset) % 3];
                samples.add(measure(pg, scope, turns, concurrency, iteration));
                writeReport(report);
                System.out.println("P102 measured " + scope + " turns=" + turns
                    + " concurrent=" + concurrency + " iteration=" + iteration);
              }
            }
          }
        }
        for (Scope scope : Scope.values()) {
          for (int attempt = 1; attempt <= 3; attempt++) {
            String session = UUID.randomUUID().toString();
            String turn = UUID.randomUUID().toString();
            String run = UUID.randomUUID().toString();
            var cfg = config(scope, session, turn, run);
            var failCalls = new AtomicInteger();
            try {
              graph(rawSaver(), failCalls).invoke(GraphInput.args(input(session, turn, run)), cfg);
              throw new AssertionError("Expected injected failure");
            } catch (Exception expected) {
              assertThat(expected).hasStackTraceContaining("intentional-benchmark-recovery");
            }
            long readStart = System.nanoTime();
            var rebuilt = rawSaver();
            assertThat(rebuilt.get(cfg).orElseThrow().state().get("workflowRunId")).isEqualTo(run);
            double readMs = (System.nanoTime() - readStart) / 1_000_000.0;
            long resumeStart = System.nanoTime();
            var restored = graph(rebuilt, failCalls).invoke(GraphInput.resume(), cfg).orElseThrow();
            double resumeMs = (System.nanoTime() - resumeStart) / 1_000_000.0;
            assertThat(restored.value("workflowRunId").orElseThrow()).isEqualTo(run);
            assertThat(restored.value("stage").orElseThrow()).isEqualTo(3);
            rebuilt.release(cfg);
            recovery.add(Map.of("scope", scope.name(), "sample", attempt, "readAndRebuildMs", readMs,
                "resumeAndGraphRebuildMs", resumeMs, "runPreserved", true, "failedNodeCalls", failCalls.get()));
            writeReport(report);
          }
        }
        report.put("completed", true);
      }
    } finally {
      writeReport(report);
    }
  }

  static void writeReport(Map<String, Object> report) throws Exception {
    Files.createDirectories(OUTPUT.getParent());
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), report);
  }
}
