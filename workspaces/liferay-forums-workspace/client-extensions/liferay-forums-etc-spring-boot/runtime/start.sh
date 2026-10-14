#!/usr/bin/env bash

function main {
	cd "$(dirname "${0}")"

	if [[ -n "${LIFERAY_HOME:-}" ]]
	then
		export LIFERAY_ROUTES_CLIENT_EXTENSION="${LIFERAY_HOME}/routes/default/liferay-forums-etc-spring-boot"
		export LIFERAY_ROUTES_DXP="${LIFERAY_HOME}/routes/default/dxp"

		if [[ ! -d "${LIFERAY_ROUTES_CLIENT_EXTENSION}" ]]
		then
			echo "${LIFERAY_ROUTES_CLIENT_EXTENSION} does not exist. Deploy liferay-forums-etc-spring-boot.zip to Liferay and check that LIFERAY_HOME is correct."

			exit 1
		fi
	elif ! grep --quiet "^liferay-forums-etc-spring-boot-oahs.oauth2.headless.server.client.id=." application.properties
	then
		echo "Set LIFERAY_HOME when running on the Liferay machine, or fill in application.properties when running on another machine. See README.txt."

		exit 1
	fi

	exec java \
		--add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
		-XX:MaxRAMPercentage=50.0 \
		-jar liferay-forums-etc-spring-boot.jar \
		"${@}"
}

main "${@}"