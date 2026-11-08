package interview.guide.technical.langgraph.threadstudy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Correctness probes only. Deliberately not a substitute for the missing benchmark prompt. */
@Testcontainers
class ThreadArchitectureProbeTest {
  enum Scope { SESSION, TURN, RUN }

  private static final String PASSWORD = UUID.randomUUID().toString();
  @Container
  static final GenericContainer<?> POSTGRES = new GenericContainer<>("pgvector/pgvector:pg17")
      .withEnv("POSTGRES_DB", "p102_correctness")
      .withEnv("POSTGRES_USER", "probe")
      .withEnv("POSTGRES_PASSWORD", PASSWORD)
      .withExposedPorts(5432)
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
          .withStartupTimeout(Duration.ofMinutes(2)));
  static HikariDataSource pool;
  static JdbcTemplate jdbc;
  static final List<String> ROWS = new ArrayList<>(List.of("scope,probe,observation,value"));

  static class State extends AgentState {
    State(Map<String, Object> data) { super(data); }
  }

  static JacksonStateSerializer<State> serializer() {
    return new JacksonStateSerializer<State>(State::new) {};
  }

  @BeforeAll
  static void initialize() throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://" + POSTGRES.getHost() + ":"
        + POSTGRES.getMappedPort(5432) + "/p102_correctness");
    config.setUsername("probe");
    config.setPassword(PASSWORD);
    config.setMaximumPoolSize(3);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    var sql = new ClassPathResource("db/migration/v2.1__init.sql");
    assertThat(sql.getURL().toString()).contains("langgraph4j-postgres-saver-1.9.7.jar");
    try (var connection = pool.getConnection()) {
      ScriptUtils.executeSqlScript(connection, sql);
    }
    assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
        + "WHERE table_name='lg4jcheckpoint' AND column_name='state_data'", String.class))
        .isEqualTo("text");
    ROWS.add("ALL,environment,postgres," + jdbc.queryForObject("SHOW server_version", String.class));
    ROWS.add("ALL,environment,java," + System.getProperty("java.version"));
    jdbc.execute("CREATE TABLE p102_command (session_id TEXT, event_id TEXT, "
        + "run_id TEXT, PRIMARY KEY(session_id,event_id))");
    jdbc.execute("CREATE TABLE p102_session (session_id TEXT PRIMARY KEY, "
        + "active_run TEXT, fence BIGINT NOT NULL DEFAULT 0)");
  }

  @AfterAll
  static void finish() throws Exception {
    if (pool != null) {
      ROWS.add("ALL,connections,active_at_end," + pool.getHikariPoolMXBean().getActiveConnections());
      pool.close();
    }
    Path output = Path.of("build/reports/p102-thread-correctness.csv");
    Files.createDirectories(output.getParent());
    Files.write(output, ROWS, StandardCharsets.UTF_8);
  }

  static String thread(Scope scope, String session, String turn, String run) {
    return "p102:" + scope + ":" + switch (scope) {
      case SESSION -> session;
      case TURN -> session + ":" + turn;
      case RUN -> session + ":" + run;
    };
  }

  static RunnableConfig config(String thread) {
    return RunnableConfig.builder().threadId(thread).build();
  }

  static PostgresSaverV2 saver() throws Exception {
    return PostgresSaverV2.builder().datasource(pool).stateSerializer(serializer())
        .createTables(false).dropTablesFirst(false).build();
  }

  static CompiledGraph<State> graph(PostgresSaverV2 saver, boolean interrupt,
                                    AtomicInteger secondCalls, boolean failOnce) throws Exception {
    var compile = CompileConfig.builder().checkpointSaver(saver).releaseThread(false);
    if (interrupt) { compile.interruptBefore("second"); }
    Map<String, Channel<?>> channels = Map.of("history", Channels.<String>appender(ArrayList::new));
    return new StateGraph<State>(channels, serializer())
        .addNode("first", node_async(s -> Map.of("firstRun", s.value("workflowRunId").orElseThrow())))
        .addNode("second", node_async(s -> {
          if (secondCalls.incrementAndGet() == 1 && failOnce) {
            throw new IllegalStateException("intentional-p102-node-failure");
          }
          return Map.of("secondRun", s.value("workflowRunId").orElseThrow());
        }))
        .addEdge(START, "first").addEdge("first", "second").addEdge("second", END)
        .compile(compile.build());
  }

  @ParameterizedTest
  @EnumSource(Scope.class)
  @DisplayName("三种粒度下新Run的args合并残留字段及追加历史")
  void newRunStateIsolation(Scope scope) throws Exception {
    String session = UUID.randomUUID().toString();
    var saver = saver();
    var graph = graph(saver, false, new AtomicInteger(), false);
    graph.invoke(GraphInput.args(Map.of("workflowRunId", "r1", "optionalEvidence", "old",
        "history", List.of("r1"))), config(thread(scope, session, "t1", "r1")));
    var sameTurn = graph.invoke(GraphInput.args(Map.of("workflowRunId", "r2", "history", List.of("r2"))),
        config(thread(scope, session, "t1", "r2"))).orElseThrow();
    boolean sharesTurn = scope != Scope.RUN;
    assertThat(sameTurn.value("optionalEvidence").isPresent()).isEqualTo(sharesTurn);
    assertThat((List<?>) sameTurn.value("history").orElseThrow()).hasSize(sharesTurn ? 2 : 1);
    var nextTurn = graph.invoke(GraphInput.args(Map.of("workflowRunId", "r3", "history", List.of("r3"))),
        config(thread(scope, session, "t2", "r3"))).orElseThrow();
    assertThat(nextTurn.value("optionalEvidence").isPresent()).isEqualTo(scope == Scope.SESSION);
    ROWS.add(scope + ",args,same_turn_stale_field," + sharesTurn);
    ROWS.add(scope + ",args,next_turn_stale_field," + (scope == Scope.SESSION));
    ROWS.add(scope + ",args,next_turn_history_size," + ((List<?>) nextTurn.value("history").orElseThrow()).size());
  }

  @ParameterizedTest
  @EnumSource(Scope.class)
  @DisplayName("resume只按Thread定位不会校验原Run且可显式选旧Checkpoint")
  void resumeIdentity(Scope scope) throws Exception {
    String session = UUID.randomUUID().toString();
    var saver = saver();
    var a = config(thread(scope, session, "t1", "r1"));
    var b = config(thread(scope, session, "t1", "r2"));
    graph(saver, true, new AtomicInteger(), false)
        .invoke(GraphInput.args(Map.of("workflowRunId", "r1")), a);
    var oldCheckpoint = saver.get(a).orElseThrow();
    graph(saver, true, new AtomicInteger(), false)
        .invoke(GraphInput.args(Map.of("workflowRunId", "r2")), b);
    var resumed = graph(saver(), false, new AtomicInteger(), false)
        .invoke(GraphInput.resume(), a).orElseThrow();
    String expected = scope == Scope.RUN ? "r1" : "r2";
    assertThat(resumed.value("secondRun").orElseThrow()).isEqualTo(expected);
    ROWS.add(scope + ",resume,actual_run_when_requesting_r1," + expected);
    // Explicit historical selection is possible, but it writes into the current thread lineage.
    var explicit = graph(saver(), false, new AtomicInteger(), false)
        .invoke(GraphInput.resume(oldCheckpoint), a).orElseThrow();
    assertThat(explicit.value("secondRun").orElseThrow()).isEqualTo("r1");
    ROWS.add(scope + ",resume,explicit_old_checkpoint_run,r1");
  }

  @ParameterizedTest
  @EnumSource(Scope.class)
  @DisplayName("同Run失败恢复及release归档后同名新Run无状态残留")
  void retryAndRelease(Scope scope) throws Exception {
    String session = UUID.randomUUID().toString();
    var cfg = config(thread(scope, session, "t1", "r1"));
    var saver = saver();
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> graph(saver, false, calls, true)
        .invoke(GraphInput.args(Map.of("workflowRunId", "r1", "optionalEvidence", "old")), cfg))
        .hasStackTraceContaining("intentional-p102-node-failure");
    var resumed = graph(saver(), false, calls, true).invoke(GraphInput.resume(), cfg).orElseThrow();
    assertThat(resumed.value("workflowRunId").orElseThrow()).isEqualTo("r1");
    assertThat(calls.get()).isEqualTo(2);
    int before = jdbc.queryForObject("SELECT count(*) FROM LG4JCheckpoint c JOIN LG4JThread t "
        + "ON c.thread_id=t.thread_id WHERE t.thread_name=?", Integer.class, cfg.threadId().orElseThrow());
    saver.release(cfg);
    assertThat(saver.get(cfg)).isEmpty();
    var archived = saver.tag(cfg, 1).orElseThrow();
    assertThat(archived.checkpoints()).hasSize(before);
    var fresh = graph(saver, false, new AtomicInteger(), false)
        .invoke(GraphInput.args(Map.of("workflowRunId", "r2")), cfg).orElseThrow();
    assertThat(fresh.value("optionalEvidence")).isEmpty();
    saver.release(cfg);
    assertThat(saver.tag(cfg, 2).orElseThrow().checkpoints()).isNotEmpty();
    assertThat(saver.tag(cfg, 1).orElseThrow().checkpoints()).hasSize(before);
    ROWS.add(scope + ",retry,second_node_attempts," + calls.get());
    ROWS.add(scope + ",release,archived_checkpoint_rows," + before);
    ROWS.add(scope + ",release,tag_versions,2");
    ROWS.add(scope + ",release,new_generation_stale_field,false");
  }

  @ParameterizedTest
  @EnumSource(Scope.class)
  @DisplayName("共享Thread显式移除可选字段与重置追加Channel后再装载新Run")
  void explicitReset(Scope scope) throws Exception {
    String session = UUID.randomUUID().toString();
    var saver = saver();
    var graph = graph(saver, false, new AtomicInteger(), false);
    var a = config(thread(scope, session, "t1", "r1"));
    var b = config(thread(scope, session, "t1", "r2"));
    graph.invoke(GraphInput.args(Map.of("workflowRunId", "r1", "optionalEvidence", "old",
        "history", List.of("r1"))), a);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("workflowRunId", "r2");
    input.put("optionalEvidence", AgentState.MARK_FOR_REMOVAL);
    input.put("history", AgentState.MARK_FOR_RESET);
    var result = graph.invoke(GraphInput.args(input), b).orElseThrow();
    assertThat(result.value("optionalEvidence")).isEmpty();
    assertThat((List<?>) result.value("history").orElseThrow()).isEmpty();
    ROWS.add(scope + ",reset,stale_field,false");
    ROWS.add(scope + ",reset,history_size,0");
  }

  @ParameterizedTest
  @EnumSource(Scope.class)
  @DisplayName("同会话重复命令唯一约束并发CAS认领及过期fence拒绝")
  void duplicateAndConcurrentAdmission(Scope scope) throws Exception {
    String session = UUID.randomUUID().toString();
    String event = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO p102_session(session_id) VALUES (?)", session);
    var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2));
    var gate = new CountDownLatch(1);
    try {
      var one = executor.submit(() -> {
        gate.await();
        return jdbc.update("INSERT INTO p102_command VALUES (?,?,?) ON CONFLICT DO NOTHING",
            session, event, "r1");
      });
      var two = executor.submit(() -> {
        gate.await();
        return jdbc.update("INSERT INTO p102_command VALUES (?,?,?) ON CONFLICT DO NOTHING",
            session, event, "r2");
      });
      gate.countDown();
      int accepted = one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS);
      assertThat(accepted).isEqualTo(1);
      var claimOne = executor.submit(() -> jdbc.update("UPDATE p102_session "
          + "SET active_run='r1',fence=fence+1 WHERE session_id=? AND active_run IS NULL", session));
      var claimTwo = executor.submit(() -> jdbc.update("UPDATE p102_session "
          + "SET active_run='r2',fence=fence+1 WHERE session_id=? AND active_run IS NULL", session));
      int claims = claimOne.get(10, TimeUnit.SECONDS) + claimTwo.get(10, TimeUnit.SECONDS);
      assertThat(claims).isEqualTo(1);
      jdbc.update("UPDATE p102_session SET fence=fence+1 WHERE session_id=?", session);
      int stale = jdbc.update("UPDATE p102_session SET active_run=NULL WHERE session_id=? AND fence=1", session);
      assertThat(stale).isZero();
      ROWS.add(scope + ",admission,duplicate_commands_accepted," + accepted);
      ROWS.add(scope + ",admission,concurrent_claims_accepted," + claims);
      ROWS.add(scope + ",admission,stale_fence_updates," + stale);
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}

