package interview.guide.technical.langgraph;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.UserScopedCache;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

class GraphProbeTest {
  @Test
  @DisplayName("两个节点传递状态并按条件分别选择结束或继续")
  void sequenceAndRouting() throws Exception {
    var graph = new StateGraph<>(ProbeState.serializer())
        .addNode("first", node_async(s -> Map.of("count", 1)))
        .addNode("second", node_async(s -> Map.of("count", (int) s.value("count").orElseThrow() + 1)))
        .addEdge(START, "first")
        .addConditionalEdges("first", edge_async(s -> s.<Boolean>value("continue").orElseThrow()
            ? "yes" : "no"), Map.of("yes", "second", "no", END))
        .addEdge("second", END).compile();
    assertThat(graph.invoke(GraphInput.args(Map.of("continue", true)), RunnableConfig.builder().build()).orElseThrow().data()).containsEntry("count", 2);
    assertThat(graph.invoke(GraphInput.args(Map.of("continue", false)), RunnableConfig.builder().build()).orElseThrow().data()).containsEntry("count", 1);
  }

  @Test
  @DisplayName("节点错误可识别且递归上限阻止无限循环")
  void failureAndLimit() throws Exception {
    var broken = new StateGraph<>(ProbeState.serializer())
        .addNode("fail", node_async(s -> { throw new IllegalStateException("probe-failure"); }))
        .addEdge(START, "fail").addEdge("fail", END).compile();
    assertThatThrownBy(() -> broken.invoke(GraphInput.noArgs(), RunnableConfig.builder().build())).hasStackTraceContaining("probe-failure");
    var calls = new AtomicInteger();
    var loop = new StateGraph<>(ProbeState.serializer())
        .addNode("loop", node_async(s -> Map.of("count", calls.incrementAndGet())))
        .addEdge(START, "loop").addEdge("loop", "loop")
        .compile(CompileConfig.builder().recursionLimit(3).build());
    assertThatThrownBy(() -> loop.invoke(GraphInput.noArgs(), RunnableConfig.builder().build())).hasStackTraceContaining("Maximum number of iterations");
    assertThat(calls.get()).isBetween(1, 3);
  }

  @Test
  @DisplayName("Jackson2序列化保留record枚举及集合的真实类型")
  void serialization() throws Exception {
    var dto = new ProbeState.ProbeDTO("样本", ProbeState.Decision.CONTINUE, List.of("a", "b"));
    var serializer = ProbeState.serializer();
    var restored = serializer.readDataFromString(serializer.writeDataAsString(
        new ProbeState(Map.of("dto", dto, "items", List.of(dto)))));
    assertThat(restored.value("dto").orElseThrow()).isEqualTo(dto);
    assertThat(restored.value("items").orElseThrow()).isEqualTo(List.of(dto));
  }

  record OutputDTO(String label) {}

  @Test
  @DisplayName("Graph通过真实Registry公开入口调用Fake模型并执行结构化输出及失败重试")
  void springAiBridge() throws Exception {
    var calls = new AtomicInteger();
    ChatModel fake = prompt -> {
      assertThat(prompt.getInstructions()).hasSize(2);
      int call = calls.incrementAndGet();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(
          call == 1 ? "{\"label\":\"ok\"}" : "not-json"))));
    };
    var registry = new LlmProviderRegistry(new LlmProviderProperties(), null, null, null);
    // Registry hardcodes OpenAiChatModel construction. Inject only its test cache;
    // execute the real public routing/cache method, never a real network model.
    // 阶段二缓存改为用户级有界 TTL 缓存，Key 形如 platform::probe:plain。
    var testCache = new UserScopedCache<ChatClient>("LlmChatClientTest", 8, Duration.ofMinutes(5));
    testCache.get("platform::probe:plain", key -> ChatClient.create(fake));
    ReflectionTestUtils.setField(registry, "clientCache", testCache);
    var properties = new StructuredOutputProperties();
    properties.setStructuredSchemaValidationEnabled(false);
    properties.setStructuredMaxAttempts(2);
    var invoker = new StructuredOutputInvoker(properties, null);
    var converter = new BeanOutputConverter<>(OutputDTO.class);
    var graph = new StateGraph<>(ProbeState.serializer())
        .addNode("structured", node_async(s -> {
          var result = invoker.invoke(registry.getPlainChatClient("probe"), converter.getFormat(),
              "probe", converter, ErrorCode.AI_SERVICE_ERROR, "probe", "probe",
              LoggerFactory.getLogger(GraphProbeTest.class));
          return Map.of("label", result.label());
        })).addEdge(START, "structured").addEdge("structured", END).compile();
    assertThat(graph.invoke(GraphInput.noArgs(), RunnableConfig.builder().build()).orElseThrow().data()).containsEntry("label", "ok");
    assertThatThrownBy(() -> graph.invoke(GraphInput.noArgs(), RunnableConfig.builder().build()))
        .hasStackTraceContaining(BusinessException.class.getName());
    assertThat(calls.get()).isEqualTo(3);
  }
}
