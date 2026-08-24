package com.doctorpet.domain.chat.config;

import com.doctorpet.global.config.CorsConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final ChatChannelInterceptor chatChannelInterceptor;
    private final ChatOutboundChannelInterceptor chatOutboundChannelInterceptor;
    private final Environment environment;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket만 등록한다. SockJS fallback은 채팅 정책 범위 밖이다.
        // HTTP API CORS와 WebSocket 핸드셰이크의 Origin 검증이 어긋나지 않도록 같은 설정값을 쓴다.
        // 빈 값이면 Origin이 있는 교차 오리진 요청을 모두 거부해 CORS와 동일하게 fail-closed 한다.
        List<String> allowedOrigins = CorsConfig.parseAllowedOrigins(
                environment.getProperty("cors.allowed-origins", ""));
        registry.addEndpoint("/ws/chat")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // SUBSCRIBE 뒤 subscription-ready SEND가 역전되면 준비 프레임 자체가 유실될 수 있다.
        // 단일 인스턴스 SimpleBroker 채팅 범위에서 inbound STOMP 프레임을 순서대로 처리한다.
        registration.taskExecutor().corePoolSize(1).maxPoolSize(1);
        registration.interceptors(chatChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(chatOutboundChannelInterceptor);
    }
}
