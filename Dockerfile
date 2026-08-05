# CI/CD 배포 MVP — 멀티스테이지 빌드.
# 1단계(build)에서 Gradle로 부트 JAR을 만들고, 2단계(runtime)는 그 JAR만 가져와 실행한다.
# 이렇게 나누는 이유: Gradle·JDK 전체(수백 MB)가 실제 운영 이미지에 남으면 이미지가 불필요하게
# 커지고 공격 표면(빌드 도구 취약점 등)도 늘어난다. 런타임 이미지에는 JRE와 JAR만 남긴다.

# ---- 1단계: 빌드 ----
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# 의존성만 먼저 복사해 캐시를 최대한 활용한다 — build.gradle이 안 바뀌면 소스만 바뀌어도
# 의존성 다운로드를 매번 새로 하지 않는다(Docker 레이어 캐싱).
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- 2단계: 런타임 ----
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# root로 실행하지 않는다(컨테이너가 뚫려도 호스트 권한 상승 위험을 줄인다).
RUN groupadd -r doctorpet && useradd -r -g doctorpet doctorpet

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown doctorpet:doctorpet app.jar

# 청구 감사 로그 전용 디렉터리(PR #91). 여기에 docker-compose가 볼륨을 마운트해, 배포 시 컨테이너가
# 교체돼도 감사 파일이 보존되게 한다. 비루트(doctorpet)로 실행하므로 미리 소유권을 넘겨 쓰기 권한을 준다.
RUN mkdir -p /app/logs && chown -R doctorpet:doctorpet /app/logs
USER doctorpet

EXPOSE 8080
# 액추에이터(health/prometheus) 전용 관리 포트(이슈 #125) — 앱 포트(8080)와 분리해 mysql·redis와
# 같은 방식으로 호스트에 게시하지 않는다(docker-compose.yml 참고). 컨테이너 "내부"에서 curl하는
# 아래 HEALTHCHECK는 호스트 게시 여부와 무관하게 그대로 동작한다.
EXPOSE 8081

# 컨테이너 자체 헬스체크 — docker-compose/오케스트레이터가 "떠는 있지만 아직 요청 못 받는"
# 상태와 "정상 기동 완료"를 구분할 수 있게 한다. Actuator health가 permitAll로 열려 있어야 한다
# (SecurityConfig의 actuatorSecurityFilterChain 참고). 이슈 #125로 관리 포트가 8081로 바뀌었다.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
