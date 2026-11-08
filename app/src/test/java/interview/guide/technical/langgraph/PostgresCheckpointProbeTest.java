package interview.guide.technical.langgraph;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaverV2;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Testcontainers(disabledWithoutDocker = true)
class PostgresCheckpointProbeTest {
  // A fresh disposable container, random host port, no mounts or business credentials.
  private static final String PASSWORD = UUID.randomUUID().toString();
  @Container
  static final GenericContainer<?> POSTGRES = new GenericContainer<>("pgvector/pgvector:pg17")
      .withEnv("POSTGRES_DB", "p101_probe")
      .withEnv("POSTGRES_USER", "probe")
      .withEnv("POSTGRES_PASSWORD", PASSWORD)
      .withExposedPorts(5432)
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
          .withStartupTimeout(Duration.ofMinutes(2)));
  static HikariDataSource pool;
  static JdbcTemplate jdbc;

  @BeforeAll
  static void initializeIsolatedSchema() throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://" + POSTGRES.getHost() + ":"
        + POSTGRES.getMappedPort(5432) + "/p101_probe");
    config.setUsername("probe");
    config.setPassword(PASSWORD);
    config.setMaximumPoolSize(3);
    config.setConnectionTimeout(5000);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    System.out.println("P1-01 isolated PostgreSQL version: "
        + jdbc.queryForObject("SHOW server_version", String.class));
    // Apply the exact published SQL only in this container. No application context/Flyway.
    var sql = new ClassPathResource("db/migration/v2.1__init.sql");
    assertThat(sql.getURL().toString()).contains("langgraph4j-postgres-saver-1.9.7.jar");
    try (var connection = pool.getConnection()) {
      ScriptUtils.executeSqlScript(connection, sql);
    }
    jdbc.execute("CREATE TABLE transaction_probe (id INTEGER PRIMARY KEY)");
  }

  @AfterAll
  static void closePool() {
    if (pool != null) {
      pool.close();
    }
  }

  static PostgresSaverV2 saver() throws Exception {
    return PostgresSaverV2.builder().datasource(pool).stateSerializer(ProbeState.serializer())
        .createTables(false).dropTablesFirst(false).build();
  }

  static CompiledGraph<ProbeState> graph(PostgresSaverV2 saver, AtomicInteger first,
                                       AtomicInteger second, boolean interrupt, boolean failOnce)
      throws Exception {
    var builder = CompileConfig.builder().checkpointSaver(saver).releaseThread(false);
    if (interrupt) {
      builder.interruptBefore("second");
    }
    return new StateGraph<>(ProbeState.serializer())
        .addNode("first", node_async(s -> {
          first.incrementAndGet();
          return Map.of("dto", new ProbeState.ProbeDTO("saved", ProbeState.Decision.CONTINUE,
              List.of("a", "b")), "count", 1);
        }))
        .addNode("second", node_async(s -> {
          if (second.incrementAndGet() == 1 && failOnce) {
            throw new IllegalStateException("retry-probe");
          }
          assertThat(s.value("dto").orElseThrow()).isInstanceOf(ProbeState.ProbeDTO.class);
          return Map.of("count", (int) s.value("count").orElseThrow() + 1);
        }))
        .addEdge(START, "first").addEdge("first", "second").addEdge("second", END)
        .compile(builder.build());
  }

  @Test
  @DisplayName("发布包V2使用TEXT并可重建Graph和Saver恢复及释放版本标签")
  void restartAndRelease() throws Exception {
    assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
        + "WHERE table_name='lg4jcheckpoint' AND column_name='state_data'", String.class))
        .isEqualTo("text");
    var run = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
    var first = new AtomicInteger();
    var second = new AtomicInteger();
    graph(saver(), first, second, true, false).invoke(GraphInput.noArgs(), run);
    assertThat(first.get()).isEqualTo(1);
    assertThat(second.get()).isZero();
    var recreatedSaver = saver();
    assertThat(recreatedSaver.get(run).orElseThrow().state()).containsEntry("count", 1);
    var result = graph(recreatedSaver, first, second, false, false)
        .invoke(GraphInput.resume(), run).orElseThrow();
    assertThat(result.data()).containsEntry("count", 2);
    assertThat(first.get()).isEqualTo(1);
    assertThat(second.get()).isEqualTo(1);
    recreatedSaver.release(run);
    var released = saver().tag(run, 1).orElseThrow();
    assertThat(released.checkpoints()).isNotEmpty();
    assertThat(saver().get(run)).isEmpty();
    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
  }

  @Test
  @DisplayName("失败后同一run恢复仅重试失败节点且连接归还")
  void retrySameRun() throws Exception {
    var run = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
    var first = new AtomicInteger();
    var second = new AtomicInteger();
    var original = graph(saver(), first, second, false, true);
    assertThatThrownBy(() -> original.invoke(GraphInput.noArgs(), run)).hasStackTraceContaining("retry-probe");
    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    var result = graph(saver(), first, second, false, true)
        .invoke(GraphInput.resume(), run).orElseThrow();
    assertThat(result.data()).containsEntry("count", 2);
    assertThat(first.get()).isEqualTo(1);
    assertThat(second.get()).isEqualTo(2);
    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
  }

  @Test
  @DisplayName("普通DataSource下业务事务回滚不会回滚Saver独立提交")
  void transactionBoundary() throws Exception {
    var run = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build();
    var compiled = graph(saver(), new AtomicInteger(), new AtomicInteger(), true, false);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
    transaction.executeWithoutResult(status -> {
      jdbc.update("INSERT INTO transaction_probe(id) VALUES (1)");
      compiled.invoke(GraphInput.noArgs(), run);
      status.setRollbackOnly();
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_probe", Integer.class)).isZero();
    assertThat(saver().get(run)).isPresent();
    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
  }
}
