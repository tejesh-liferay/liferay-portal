#!/bin/bash
#
# Clones the built liferay-forums-site-initializer CET artifact once per
# site listed in the SITES array below. Three entries are rewritten in
# each copy — everything else (the site content tree, OAuth config,
# Dockerfile, etc.) is copied through byte for byte:
#
#   - site-initializer/site-initializer.json  (externalReferenceCode, name)
#   - WEB-INF/liferay-plugin-package.properties (Bundle-SymbolicName)
#   - LCP.json (id)
#
# The Bundle-SymbolicName rewrite matters: every clone otherwise keeps the
# base artifact's original symbolic name, so OSGi treats every deployed
# copy as an update to the SAME bundle rather than a separate one —
# confirmed on a running bundle, where each new zip dropped into
# client-extensions/ stopped the previous bundle and started a new bundle
# ID under the identical symbolic name, never coexisting. Suffixing the
# symbolic name (and the LCP.json id, for the same reason on LXC) with
# each site's slug gives every clone its own bundle identity, so N zips
# deploy as N independent, simultaneously active bundles.
#
# A single siteInitializer CET bundle can only ever provision one site —
# site-initializer.json holds exactly one externalReferenceCode/name pair,
# and the workspace build tooling refuses more than one siteInitializer
# entry per project — so N sites require N deployed zips. This script
# produces those N zips from one already-built artifact (see LPD-101025).
#
# Requires Bash 4 or later, unzip, and zip. By default it reads
# liferay-forums-site-initializer.zip from the directory the script is in.
#
# Usage:
#   ./generate-site-initializer-zips.sh [--base <built.zip>] [--deploy <client-extensions-dir>] [--output-dir <dir>]

set -o errexit
set -o nounset
set -o pipefail

declare -A SITES=(
	[LIFERAY_ACME]="Acme, Inc. site"
	[LIFERAY_NORTHWIND]="Northwind site"
	[LIFERAY_RAY]="Ray site"
)

function main {
	local base
	local deploy_dir=""
	local output_dir="out"

	local script_dir

	script_dir=$(dirname "${BASH_SOURCE[0]}")

	base=${script_dir}/liferay-forums-site-initializer.zip

	if [[ ! -f ${base} ]]
	then
		base=${script_dir}/../../client-extensions/liferay-forums-site-initializer/dist/liferay-forums-site-initializer.zip
	fi

	while [[ ${#} -gt 0 ]]
	do
		case ${1} in
			--base)
				base=${2}

				shift 2
				;;
			--deploy)
				deploy_dir=${2}

				shift 2
				;;
			--output-dir)
				output_dir=${2}

				shift 2
				;;
			-h|--help)
				usage

				return 0
				;;
			*)
				echo "Unknown argument: ${1}" >&2

				usage

				return 1
				;;
		esac
	done

	if [[ ! -f ${base} ]]
	then
		echo "Error: base artifact not found: ${base}" >&2

		return 1
	fi

	if ! unzip -l "${base}" | grep --quiet "site-initializer/site-initializer.json"
	then
		echo "Error: ${base} has no site-initializer/site-initializer.json — is this a siteInitializer CET artifact?" >&2

		return 1
	fi

	if [[ -n ${deploy_dir} && ! -d ${deploy_dir} ]]
	then
		echo "Error: --deploy directory does not exist: ${deploy_dir}" >&2

		return 1
	fi

	mkdir -p "${output_dir}"

	_generate_zips "${base}" "${deploy_dir}" "${output_dir}"
}

function usage {
	cat <<-EOF
	Usage: $(basename "${0}") [--base <built-site-initializer.zip>] [--deploy <client-extensions-dir>] [--output-dir <dir>]

	Edit the SITES associative array at the top of this script to add,
	remove, or rename sites (externalReferenceCode -> name).
	EOF
}

function _escape_json {
	local value=${1//\\/\\\\}

	printf '%s' "${value//\"/\\\"}"
}

function _generate_zips {
	local base=${1}
	local deploy_dir=${2}
	local output_dir=${3}

	local base_name

	base_name=$(basename "${base}" .zip)

	local symbolic_name

	symbolic_name=$(unzip -p "${base}" WEB-INF/liferay-plugin-package.properties | sed --quiet --expression "s/^Bundle-SymbolicName=//p" | tr --delete "\r")

	if [[ -z ${symbolic_name} ]]
	then
		echo "Error: Bundle-SymbolicName not found in WEB-INF/liferay-plugin-package.properties of ${base}" >&2

		return 1
	fi

	local temp_dir

	temp_dir=$(mktemp --directory)

	trap "rm --force --recursive '${temp_dir}'" EXIT

	mkdir --parents "${temp_dir}/site-initializer" "${temp_dir}/WEB-INF"

	local -A used_slugs=()

	local erc
	local name
	local new_symbolic_name
	local out_zip
	local slug

	for erc in "${!SITES[@]}"
	do
		name=${SITES[${erc}]}
		slug=$(_slugify "${erc}")

		if [[ -n ${used_slugs[${slug}]+set} ]]
		then
			echo "Error: externalReferenceCode '${erc}' collides with '${used_slugs[${slug}]}' after slugifying to '${slug}' — output file names would collide." >&2

			return 1
		fi

		used_slugs[${slug}]=${erc}

		new_symbolic_name=${symbolic_name}-${slug}
		out_zip=$(realpath "${output_dir}")/${base_name}-${slug}.zip

		echo "Writing ${out_zip}  (externalReferenceCode=${erc}, name=${name}, bundle suffix=${slug})"

		printf '{\n  "externalReferenceCode": "%s",\n  "name": "%s"\n}' "$(_escape_json "${erc}")" "$(_escape_json "${name}")" > "${temp_dir}/site-initializer/site-initializer.json"

		unzip -p "${base}" WEB-INF/liferay-plugin-package.properties | sed --expression "s/^Bundle-SymbolicName=.*/Bundle-SymbolicName=${new_symbolic_name}/" > "${temp_dir}/WEB-INF/liferay-plugin-package.properties"

		unzip -p "${base}" LCP.json | sed --expression "s/^\t\"id\": \"[^\"]*\"/\t\"id\": \"${new_symbolic_name//-/}\"/" > "${temp_dir}/LCP.json"

		cp "${base}" "${out_zip}"

		(cd "${temp_dir}" && zip --quiet "${out_zip}" LCP.json site-initializer/site-initializer.json WEB-INF/liferay-plugin-package.properties)

		if [[ -n ${deploy_dir} ]]
		then
			cp "${out_zip}" "${deploy_dir}/$(basename "${out_zip}")"

			echo "  deployed to ${deploy_dir}/$(basename "${out_zip}")"
		fi
	done
}

function _slugify {
	local value=${1,,}

	value=$(printf '%s' "${value}" | tr --complement 'a-z0-9' '-' | tr --squeeze-repeats '-')
	value=${value#-}
	value=${value%-}

	printf '%s' "${value}"
}

main "${@}"
