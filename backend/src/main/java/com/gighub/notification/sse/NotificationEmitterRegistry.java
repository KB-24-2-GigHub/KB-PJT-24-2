package com.gighub.notification.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 수신자별 SSE 연결을 들고 있다가 새 알림이 적재되면 해당 사용자에게만 신호를 보냅니다(#386).
 *
 * <p><b>보내는 것은 신호뿐입니다.</b> 알림 본문을 실어 보내지 않습니다. 계약은
 * {@code GET /api/notifications}와 {@code /unread-count}가 이미 소유하고 있고(SPEC-382-01),
 * SSE는 "지금 다시 조회하라"는 전달 수단입니다. 여기서 Payload를 새로 정의하면 같은 알림을
 * 두 벌의 형태로 유지해야 합니다.</p>
 *
 * <p><b>단일 인스턴스 전제입니다.</b> 연결을 이 프로세스의 Heap에 들고 있으므로 Tomcat이 둘
 * 이상이면 다른 인스턴스에 붙은 사용자는 신호를 받지 못합니다. 그때는 공유 Broker가 필요하며,
 * 이 클래스로 해결되지 않습니다. 신호를 놓쳐도 목록 조회는 그대로 동작하므로 실패 영향은
 * "새로고침해야 보인다"까지입니다.</p>
 *
 * <p><b>하트비트는 전용 Scheduler 하나로 묶습니다.</b> 연결마다 타이머를 만들면 연결 수만큼
 * 스레드가 늘어납니다. 공용 {@code TaskScheduler} Bean을 쓰지 않는 이유는, 현재 Root Context에
 * {@code TaskScheduler}가 정확히 하나(정산·분쟁용)뿐이고 두 번째 Bean을 추가하면 그 둘을
 * 타입으로 주입받는 기존 {@code SchedulingConfigurer}가 모호해지기 때문입니다. 25초 주기의
 * 짧은 쓰기 하나를 위해 정산 Scheduler의 스레드를 나눠 쓰지도 않습니다.</p>
 */
@Component
public class NotificationEmitterRegistry {

    /**
     * Tomcat 9의 async 기본 타임아웃은 30초입니다. 명시하지 않으면 연결이 30초마다 끊깁니다.
     * 30분마다 한 번은 끊고 브라우저가 다시 붙게 두어, 죽은 연결이 무한히 쌓이지 않게 합니다.
     */
    static final long EMITTER_TIMEOUT_MILLIS = 30L * 60 * 1000;

    /** 배포 nginx의 {@code proxy_read_timeout}이 60초다. 그 절반 이하로 보내야 안전하다. */
    static final long HEARTBEAT_INTERVAL_MILLIS = 25_000L;

    /** 새 알림이 있으니 다시 조회하라는 신호의 이름입니다. */
    public static final String NOTIFICATION_EVENT = "notification";

    private static final Logger log = LoggerFactory.getLogger(NotificationEmitterRegistry.class);

    private final Map<Long, Set<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();

    private ScheduledExecutorService heartbeatScheduler;

    @PostConstruct
    void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "notification-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL_MILLIS,
                HEARTBEAT_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    /**
     * 한 사용자의 스트림을 엽니다. 구독자는 항상 인증 Principal이며 여기서 대상을 고르지 않습니다.
     *
     * @param userId 인증 Principal에서 얻은 수신자
     * @return Controller가 그대로 반환할 Emitter
     */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = register(userId, new SseEmitter(EMITTER_TIMEOUT_MILLIS));

        // 첫 바이트를 즉시 흘려 프록시와 브라우저가 연결 성립을 확인하게 한다.
        if (!send(emitter, SseEmitter.event().comment("connected"))) {
            remove(userId, emitter);
        }
        return emitter;
    }

    /** 연결을 수신자에 묶고 정리 Callback을 겁니다. */
    SseEmitter register(Long userId, SseEmitter emitter) {
        emittersByUserId
                .computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet())
                .add(emitter);

        // 세 경로를 모두 걸어야 한다. 하나라도 빠지면 죽은 연결이 남아 하트비트가 계속 쓴다.
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(error -> remove(userId, emitter));
        return emitter;
    }

    /**
     * 새 알림이 적재된 수신자에게 다시 조회하라는 신호를 보냅니다.
     *
     * <p>연결이 없으면 아무 일도 하지 않습니다. 예외를 던지지 않습니다. 호출자는 이미 Commit된
     * 도메인 처리 뒤에 있고, 신호 실패가 그 결과를 뒤집으면 안 됩니다.</p>
     *
     * @param recipientUserId 알림이 적재된 수신자
     */
    public void notifyRecipient(Long recipientUserId) {
        Set<SseEmitter> emitters = emittersByUserId.get(recipientUserId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            if (!send(emitter, SseEmitter.event().name(NOTIFICATION_EVENT).data("new"))) {
                remove(recipientUserId, emitter);
            }
        }
    }

    /** 현재 유지 중인 연결 수입니다. 누수 여부를 확인하는 용도입니다. */
    public int connectionCount() {
        return emittersByUserId.values().stream().mapToInt(Set::size).sum();
    }

    void sendHeartbeat() {
        emittersByUserId.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                // 주석 줄이라 브라우저 Event로 잡히지 않는다. 연결만 살려 둔다.
                if (!send(emitter, SseEmitter.event().comment("keep-alive"))) {
                    remove(userId, emitter);
                }
            }
        });
    }

    /**
     * @return 전송에 성공하면 {@code true}, 연결이 끊겨 정리해야 하면 {@code false}
     */
    private boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            // 브라우저가 탭을 닫거나 프록시가 끊은 흔한 경우다. 본문·개인 정보는 남기지 않는다.
            log.debug("SSE 전송에 실패해 연결을 정리합니다.", disconnected);
            return false;
        }
    }

    private void remove(Long userId, SseEmitter emitter) {
        emittersByUserId.computeIfPresent(userId, (key, emitters) -> {
            emitters.remove(emitter);
            // 빈 Set을 남기면 로그아웃한 사용자만큼 Map이 계속 자란다.
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
