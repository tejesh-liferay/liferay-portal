@echo off

cd /d "%~dp0"

java --add-opens=java.base/java.lang.invoke=ALL-UNNAMED -XX:MaxRAMPercentage=50.0 -jar liferay-forums-etc-spring-boot.jar %*
