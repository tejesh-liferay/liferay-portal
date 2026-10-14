@echo off

cd /d "%~dp0"

if defined LIFERAY_HOME (
	set "LIFERAY_ROUTES_CLIENT_EXTENSION=%LIFERAY_HOME%\routes\default\liferay-forums-etc-spring-boot"
	set "LIFERAY_ROUTES_DXP=%LIFERAY_HOME%\routes\default\dxp"

	if not exist "%LIFERAY_HOME%\routes\default\liferay-forums-etc-spring-boot\" (
		echo %LIFERAY_HOME%\routes\default\liferay-forums-etc-spring-boot does not exist. Deploy liferay-forums-etc-spring-boot.zip to Liferay and check that LIFERAY_HOME is correct.

		exit /b 1
	)
) else (
	findstr /b /r /c:"liferay-forums-etc-spring-boot-oahs\.oauth2\.headless\.server\.client\.id=." application.properties > nul

	if errorlevel 1 (
		echo Set LIFERAY_HOME when running on the Liferay machine, or fill in application.properties when running on another machine. See README.txt.

		exit /b 1
	)
)

java --add-opens=java.base/java.lang.invoke=ALL-UNNAMED -XX:MaxRAMPercentage=50.0 -jar liferay-forums-etc-spring-boot.jar %*
