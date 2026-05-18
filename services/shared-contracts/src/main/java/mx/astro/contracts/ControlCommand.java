package mx.astro.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound control command sent by a connected client to influence its live
 * simulation stream. JSON wire format:
 *
 * <pre>
 *   { "action": "pause" }
 *   { "action": "resume" }
 *   { "action": "setDt", "value": 0.001 }
 *   { "action": "reset" }
 *   { "action": "loadScenario", "scenarioName": "pleiades" }
 * </pre>
 *
 * Semantics:
 *   - pause          — stop advancing the integrator; keep the WebSocket alive.
 *   - resume         — resume integrator stepping.
 *   - setDt          — change Δt at runtime.
 *   - reset          — re-generate the current scenario's initial conditions, t=0.
 *   - loadScenario   — close the current run, start a fresh one with the named
 *                      scenario from the catalog (also resets t=0).
 *
 * Unknown actions are ignored; the server logs a warning.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ControlCommand(
        @JsonProperty("action") String action,
        @JsonProperty("value") Double value,
        @JsonProperty("scenarioName") String scenarioName,
        @JsonProperty("nBodies") Integer nBodies,
        @JsonProperty("jobId") String jobId
) {
    public static final String PAUSE          = "pause";
    public static final String RESUME         = "resume";
    public static final String SET_DT         = "setDt";
    public static final String SET_N          = "setN";
    public static final String SET_SOFTENING  = "setSoftening";
    public static final String RESET          = "reset";
    public static final String LOAD_SCENARIO  = "loadScenario";
    public static final String REPLAY_JOB     = "replayJob";
    public static final String SEEK_REPLAY    = "seekReplay";   // value = 0..(totalFrames-1)
}
