# 빌드된 WAR 을 받아 포장만 한다. Gradle 빌드는 CI 러너 또는 개발자 로컬이 담당한다.
# 빌드 컨텍스트는 backend/ 이며, build/libs/gig-hub.war 가 미리 만들어져 있어야 한다.
#
#   sh backend/gradlew -p backend war
#   docker build -t gighub-api:local backend
FROM tomcat:9.0-jdk17-temurin

# Tomcat 기본 안내 페이지를 제거하고 우리 WAR 을 ROOT 컨텍스트로 배포한다.
# ROOT 로 두어야 /api/** 경로가 그대로 매핑된다.
RUN rm -rf /usr/local/tomcat/webapps/ROOT

# compose 의 healthcheck 와 배포 후 스모크 확인에 사용한다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 계약 PDF 저장 경로. compose 가 호스트 /opt/gighub/documents 를 여기에 연결한다.
# 볼륨이 없으면 재배포마다 발급된 계약서가 사라진다.
RUN mkdir -p /var/gighub/documents

COPY build/libs/gig-hub.war /usr/local/tomcat/webapps/ROOT.war

# DB 접속정보와 배포 설정은 이미지에 굽지 않는다.
# 실행 시 -Dgighub.database.config 로 마운트된 파일을 가리킨다.
EXPOSE 8080
