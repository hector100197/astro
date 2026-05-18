package mx.astro.simulation.infrastructure.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import mx.astro.contracts.ControlCommand;
import mx.astro.simulation.application.LiveStreamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driving adapter: accepts WebSocket connections at /snapshots and starts a
 * streaming session per client.
 *
 * <p>Bidirectional: server → client uses {@link BinaryMessage} (binary
 * snapshot frames); client → server uses {@link TextMessage} carrying JSON
 * {@link ControlCommand} payloads.
 *
 * <p>On disconnect the streaming session is torn down.
 */
@Component
public class SnapshotWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWebSocketHandler.class);

    private final LiveStreamingService streamingService;
    private final ObjectMapper objectMapper;
    private final Map<String, String> sessionToStreamId = new ConcurrentHashMap<>();

    public SnapshotWebSocketHandler(LiveStreamingService streamingService, ObjectMapper objectMapper) {
        this.streamingService = streamingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: sessionId={} from {}",
                session.getId(),
                session.getRemoteAddress() == null ? "?" : session.getRemoteAddress());

        String streamId = streamingService.startStream(
                frame -> sendBinary(session, frame),
                json  -> sendText(session, json)
        );
        sessionToStreamId.put(session.getId(), streamId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket closed: sessionId={} status={}", session.getId(), status);
        String streamId = sessionToStreamId.remove(session.getId());
        if (streamId != null) {
            streamingService.stopStream(streamId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error sessionId={}: {}", session.getId(), exception.getMessage());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String streamId = sessionToStreamId.get(session.getId());
        if (streamId == null) {
            log.warn("Text message on session {} with no stream — ignoring", session.getId());
            return;
        }
        ControlCommand cmd;
        try {
            cmd = objectMapper.readValue(message.getPayload(), ControlCommand.class);
        } catch (Exception e) {
            log.warn("Failed to parse control command on session {}: {}",
                    session.getId(), e.getMessage());
            return;
        }
        dispatch(streamId, cmd);
    }

    private void dispatch(String streamId, ControlCommand cmd) {
        if (cmd.action() == null) {
            log.warn("Control command missing action on stream {}", streamId);
            return;
        }
        switch (cmd.action()) {
            case ControlCommand.PAUSE  -> streamingService.pauseStream(streamId);
            case ControlCommand.RESUME -> streamingService.resumeStream(streamId);
            case ControlCommand.SET_DT -> {
                if (cmd.value() == null) {
                    log.warn("setDt without value on stream {}", streamId);
                } else {
                    streamingService.setDt(streamId, cmd.value());
                }
            }
            case ControlCommand.RESET  -> streamingService.resetStream(streamId);
            case ControlCommand.SET_N -> {
                if (cmd.nBodies() == null) {
                    log.warn("setN without nBodies on stream {}", streamId);
                } else {
                    streamingService.setN(streamId, cmd.nBodies());
                }
            }
            case ControlCommand.SET_SOFTENING -> {
                if (cmd.value() == null) {
                    log.warn("setSoftening without value on stream {}", streamId);
                } else {
                    streamingService.setSoftening(streamId, cmd.value());
                }
            }
            case ControlCommand.LOAD_SCENARIO -> {
                if (cmd.scenarioName() == null || cmd.scenarioName().isBlank()) {
                    log.warn("loadScenario without scenarioName on stream {}", streamId);
                } else {
                    streamingService.loadScenarioStream(streamId, cmd.scenarioName());
                }
            }
            case ControlCommand.REPLAY_JOB -> {
                if (cmd.jobId() == null || cmd.jobId().isBlank()) {
                    log.warn("replayJob without jobId on stream {}", streamId);
                } else {
                    try {
                        streamingService.replayJobStream(streamId,
                                java.util.UUID.fromString(cmd.jobId()));
                    } catch (IllegalArgumentException e) {
                        log.warn("replayJob with malformed jobId {}: {}", cmd.jobId(), e.getMessage());
                    }
                }
            }
            case ControlCommand.SEEK_REPLAY -> {
                if (cmd.value() == null) {
                    log.warn("seekReplay without value on stream {}", streamId);
                } else {
                    streamingService.seekReplay(streamId, cmd.value().intValue());
                }
            }
            default -> log.warn("Unknown action '{}' on stream {}", cmd.action(), streamId);
        }
    }

    private void sendBinary(WebSocketSession session, ByteBuffer frame) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new BinaryMessage(frame));
        } catch (Exception e) {
            log.warn("Failed to send frame on session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void sendText(WebSocketSession session, String json) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send text on session {}: {}", session.getId(), e.getMessage());
        }
    }
}
