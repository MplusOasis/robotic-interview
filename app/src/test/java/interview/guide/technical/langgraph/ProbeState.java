package interview.guide.technical.langgraph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.bsc.langgraph4j.serializer.plain_text.jackson.TypeMapper;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/** Test-only contracts, deliberately unrelated to interview business state. */
class ProbeState extends AgentState {
  ProbeState(Map<String, Object> data) {
    super(data);
  }

  enum Decision { CONTINUE, STOP }

  @JsonIgnoreProperties(value = "@type", allowGetters = true)
  record ProbeDTO(String label, Decision decision, List<String> items) {
    @JsonProperty("@type")
    public String type() {
      return ProbeDTO.class.getName();
    }
  }

  static JacksonStateSerializer<ProbeState> serializer() {
    var serializer = new JacksonStateSerializer<ProbeState>(ProbeState::new) {};
    serializer.typeMapper().register(new TypeMapper.Reference<ProbeDTO>(ProbeDTO.class) {});
    return serializer;
  }
}
