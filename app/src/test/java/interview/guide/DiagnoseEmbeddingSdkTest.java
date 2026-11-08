package interview.guide;

import com.openai.client.OpenAIClient;
import interview.guide.common.ai.ApiPathResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

class DiagnoseEmbeddingSdkTest {

  @Test
  void probe() {
    String key = System.getenv("DIAGNOSE_API_KEY");
    String baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4";
    OpenAIClient client = ApiPathResolver.buildOpenAiClient(baseUrl, key);
    OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
        .openAiClient(client)
        .options(OpenAiEmbeddingOptions.builder().model("embedding-3").dimensions(1024).build())
        .build();
    List<String> lines = new ArrayList<>();
    try {
      var response = model.call(new EmbeddingRequest(List.of("hi"), null));
      lines.add("DIAG_SDK_OK dims=" + response.getResults().get(0).getOutput().length);

      String longText = "MySQL索引优化、事务隔离级别、执行计划与锁机制详解。".repeat(60);
      List<String> texts = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        texts.add("source_name: MySQL-基础篇\nchunk_index: " + i + "\n" + longText);
      }
      lines.add("DIAG_SDK_BATCH10_OK count=" + model.embed(texts).size());
    } catch (Exception e) {
      lines.add("DIAG_SDK_FAIL class=" + e.getClass().getName());
      lines.add("DIAG_SDK_MESSAGE=" + e.getMessage());
      Throwable cause = e.getCause();
      while (cause != null) {
        lines.add("DIAG_SDK_CAUSE=" + cause.getClass().getName() + " | " + cause.getMessage());
        cause = cause.getCause();
      }
    }
    try {
      Files.write(Path.of("build/diag-result.txt"), lines);
    } catch (Exception ignored) {
      System.out.println(String.join("\n", lines));
    }
  }
}
