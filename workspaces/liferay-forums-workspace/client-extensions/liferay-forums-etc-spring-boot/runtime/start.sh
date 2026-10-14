#!/usr/bin/env bash

function main {
	cd "$(dirname "${0}")"

	exec java \
		--add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
		-XX:MaxRAMPercentage=50.0 \
		-jar liferay-forums-etc-spring-boot.jar \
		"${@}"
}

main "${@}"