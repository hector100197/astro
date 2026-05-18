package mx.astro.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire format for per-snapshot physics diagnostics. Sent as a JSON
 * {@code TextMessage} on the same WebSocket that streams binary snapshot
 * frames. Fields use compact short names because we send 60 of these per
 * second; the saving over the verbose Java field names is meaningful in
 * aggregate (~30% smaller payload).
 *
 * <p>The "type" discriminator lets the client distinguish diagnostics from
 * other future text-channel messages (status updates, warnings, errors).
 *
 * <pre>
 *   { "type": "diagnostics", "t": 1.5, "step": 300,
 *     "K": 1.0, "U": -2.0, "E": -1.0,
 *     "P": [0,0,0], "L": [0,0,1], "Q": 1.0 }
 * </pre>
 */
public record DiagnosticsDto(
        @JsonProperty("type")  String type,
        @JsonProperty("t")     double simTime,
        @JsonProperty("step")  long stepIndex,
        @JsonProperty("K")     double kineticEnergy,
        @JsonProperty("U")     double potentialEnergy,
        @JsonProperty("E")     double totalEnergy,
        @JsonProperty("P")     double[] momentum,
        @JsonProperty("L")     double[] angularL,
        @JsonProperty("Q")     double virialRatio,
        @JsonProperty("r10")   double r10,
        @JsonProperty("r50")   double r50,
        @JsonProperty("r90")   double r90
) {
    public static final String TYPE = "diagnostics";
}
