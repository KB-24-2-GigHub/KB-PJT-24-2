package com.gighub.notification.sse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 연결 레지스트리의 경계 두 가지를 고정한다(#386).
 *
 * <p>하나는 수신자 격리다. 신호가 다른 사용자의 연결로 새면 알림이 왔다는 사실 자체가 새어 나간다.
 * 다른 하나는 정리다. 끊긴 연결이 남으면 25초마다 죽은 Emitter에 계속 쓰게 된다.</p>
 */
class NotificationEmitterRegistryTest {

    private static final long OWNER_ID = 11L;
    private static final long WORKER_ID = 22L;

    private final NotificationEmitterRegistry registry = new NotificationEmitterRegistry();

    /** 신호는 자기 스트림에만 간다. 이것이 깨지면 타인의 알림 발생 사실이 새어 나간다. */
    @Test
    void signalsOnlyTheSubscribedRecipient() throws IOException {
        SseEmitter ownerStream = registry.register(OWNER_ID, mock(SseEmitter.class));
        SseEmitter workerStream = registry.register(WORKER_ID, mock(SseEmitter.class));

        registry.notifyRecipient(OWNER_ID);

        verify(ownerStream).send(any(SseEmitter.SseEventBuilder.class));
        verify(workerStream, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** 연결이 없는 수신자에게 보내는 것은 오류가 아니다. 대부분의 사용자는 접속해 있지 않다. */
    @Test
    void ignoresSignalForARecipientWithoutConnection() {
        assertDoesNotThrow(() -> registry.notifyRecipient(OWNER_ID));
        assertEquals(0, registry.connectionCount());
    }

    /** 완료·타임아웃·오류 세 경로 모두에서 항목이 사라져야 하트비트가 죽은 연결에 쓰지 않는다. */
    @Test
    void removesTheConnectionOnCompletionTimeoutAndError() {
        assertRemovedBy(emitter -> {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(emitter).onCompletion(captor.capture());
            captor.getValue().run();
        });
        assertRemovedBy(emitter -> {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(emitter).onTimeout(captor.capture());
            captor.getValue().run();
        });
        assertRemovedBy(emitter -> {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Consumer<Throwable>> captor = ArgumentCaptor.forClass(Consumer.class);
            verify(emitter).onError(captor.capture());
            captor.getValue().accept(new IllegalStateException("closed"));
        });
    }

    /** 전송 실패는 예외가 아니라 정리다. 브라우저 탭이 닫히면 흔히 일어난다. */
    @Test
    void dropsTheConnectionWhenSendingFails() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe"))
                .when(broken).send(any(SseEmitter.SseEventBuilder.class));
        registry.register(OWNER_ID, broken);

        assertDoesNotThrow(() -> registry.notifyRecipient(OWNER_ID));

        assertEquals(0, registry.connectionCount());
    }

    @Test
    void heartbeatWritesToLiveConnectionsAndKeepsThem() throws IOException {
        SseEmitter live = registry.register(OWNER_ID, mock(SseEmitter.class));

        registry.sendHeartbeat();

        verify(live).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(1, registry.connectionCount());
    }

    @Test
    void heartbeatDropsDeadConnections() throws IOException {
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));
        registry.register(OWNER_ID, dead);

        assertDoesNotThrow(registry::sendHeartbeat);

        assertEquals(0, registry.connectionCount());
    }

    private void assertRemovedBy(Consumer<SseEmitter> disconnect) {
        NotificationEmitterRegistry fresh = new NotificationEmitterRegistry();
        SseEmitter emitter = fresh.register(OWNER_ID, mock(SseEmitter.class));

        disconnect.accept(emitter);

        assertEquals(0, fresh.connectionCount());
    }
}
