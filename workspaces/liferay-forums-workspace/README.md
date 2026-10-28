# Liferay Forums

Liferay Forums is delivered as three client extension zips and one script:

| File | What It Does |
| --- | --- |
| `liferay-forums-batch.zip` | Creates the object folder, object definitions, and notification templates. |
| `liferay-forums-etc-spring-boot.zip` | The microservice behind the forum's object actions and validations (notifications, bans, locks, thread priority). Deployed to Liferay and also run on its own. |
| `liferay-forums-site-initializer.zip` | Creates one forums site with its pages, fragments, and permissions. |
| `generate-site-initializer-zips.sh` | Makes one copy of the site initializer zip per site, for installing several forums sites. |

`<liferay-home>` below is the Liferay installation directory, the one that contains `osgi` and `routes`.

## Requirements

- A self-hosted Liferay DXP. On Liferay DXP 2026.Q1, see [On Liferay DXP 2026.Q1](#on-liferay-dxp-2026q1) first.

- Java 17 or later on the machine that runs the microservice, either the Liferay machine or another machine that can reach Liferay over HTTP.

- Bash 4 or later, `unzip`, and `zip` on Linux to run `generate-site-initializer-zips.sh`.

## Deployment Order

Deploy the zips one at a time, in the order of the steps below, into a Liferay that has finished starting. Each zip depends on what the one before it creates:

- The microservice zip registers the OAuth scopes of the objects that exist when it is deployed. Deployed before the batch import finishes, the microservice gets `403 Forbidden` from Liferay, and posting fails.

- The site initializer creates the display pages of the forum's threads and messages. Run while Liferay is still starting, for example from a zip left in `<liferay-home>/osgi/client-extensions` across a restart, it skips them, and every thread link returns a not found page.

To recover from either, deploy the affected zip again: delete it from `<liferay-home>/osgi/client-extensions`, wait until the Liferay log reports its bundle `STOPPED`, then copy it back in. A site initializer deployed again updates the existing site and leaves its pages and forum content as they are, but see [On Liferay DXP 2026.Q1](#on-liferay-dxp-2026q1) before doing so on that release.

## 1. Deploy the Batch

Copy `liferay-forums-batch.zip` into `<liferay-home>/osgi/client-extensions`. It creates the objects the other zips depend on, so wait until its import finishes in the Liferay log before continuing.

## 2. Deploy and Start the Microservice

1. Copy `liferay-forums-etc-spring-boot.zip` into `<liferay-home>/osgi/client-extensions`. This registers the microservice's OAuth applications and object actions with Liferay, and Liferay writes the microservice's credentials to `<liferay-home>/routes/default`. Liferay does not run the microservice itself.

1. Unzip the same `liferay-forums-etc-spring-boot.zip` into a directory of your choice, such as `/opt/liferay-forums`, on the Liferay machine or on another machine.

1. Start it from that directory, as described for where it runs below.

1. Check that it is up:

	```bash
	curl http://localhost:58081/ready
	```

Keep the microservice running whenever Liferay is running, for example as a system service. Forum posting, notifications, and moderation depend on it.

The start scripts stop with a message, rather than starting half configured, when neither `LIFERAY_HOME` nor `application.properties` is set up, or when `LIFERAY_HOME` does not contain the microservice's credentials, which means the zip was not deployed to that Liferay yet.

### On the Liferay Machine

Point `LIFERAY_HOME` at the Liferay installation. The microservice reads the Liferay host and its OAuth credentials from `<liferay-home>/routes/default`, so there is nothing to configure.

On Linux:

```bash
LIFERAY_HOME=/opt/liferay ./start.sh
```

On Windows:

```bat
set LIFERAY_HOME=C:\liferay
start.bat
```

### On Another Machine

Liferay always calls the microservice at `http://localhost:58081`, so port `58081` on the Liferay machine must be forwarded to the microservice machine.

1. In `application.properties` next to the jar, uncomment and fill in all four settings:

	```properties
	com.liferay.lxc.dxp.mainDomain=liferay.example.com
	com.liferay.lxc.dxp.server.protocol=https
	liferay-forums-etc-spring-boot-oahs.oauth2.headless.server.client.id=<client ID>
	liferay-forums-etc-spring-boot-oahs.oauth2.headless.server.client.secret=<client secret>
	```

	`mainDomain` and `server.protocol` are how the microservice machine reaches Liferay; include the port in `mainDomain` unless it is 80 or 443. The client ID and secret belong to **Liferay Forums Etc Spring Boot OAuth Application Headless Server** in *Control Panel* → *Security* → *OAuth 2 Administration*.

1. Start it without `LIFERAY_HOME`: `./start.sh` on Linux, or `start.bat` on Windows.

1. On the Liferay machine, forward port `58081` to the microservice machine. For example, with `socat`:

	```bash
	socat TCP-LISTEN:58081,bind=127.0.0.1,fork,reuseaddr TCP:<microservice-host>:58081
	```

	Or open an SSH tunnel from the microservice machine instead:

	```bash
	ssh -N -R 58081:localhost:58081 <user>@<liferay-host>
	```

	Keep the forward running as long as the microservice runs, and allow port `58081` through any firewall between the two machines.

1. On the Liferay machine, check that the forward reaches the microservice:

	```bash
	curl http://localhost:58081/ready
	```

## 3. Create Sites

### One Site

Copy `liferay-forums-site-initializer.zip` into `<liferay-home>/osgi/client-extensions`. Liferay creates the site `FORUMS`, with external reference code `LIFERAY_FORUMS`, when the zip is deployed.

### Several Sites

A site initializer zip creates exactly one site, so each site needs its own copy of the zip. `generate-site-initializer-zips.sh` makes those copies, giving each one its own site external reference code, site name, and bundle name so that they deploy side by side.

1. Open `generate-site-initializer-zips.sh` and edit the `SITES` list near the top. Each entry maps a site's external reference code to its name:

	```bash
	declare -A SITES=(
		[LIFERAY_ACME]="Acme, Inc. site"
		[LIFERAY_NORTHWIND]="Northwind site"
	)
	```

1. From the directory that holds the script and `liferay-forums-site-initializer.zip`, generate the zips:

	```bash
	bash generate-site-initializer-zips.sh
	```

	This writes one zip per site, named after its external reference code:

	```
	out/liferay-forums-site-initializer-liferay-acme.zip
	out/liferay-forums-site-initializer-liferay-northwind.zip
	```

1. Copy the generated zips into `<liferay-home>/osgi/client-extensions`, or let the script copy them as it writes them by adding `--deploy`:

	```bash
	bash generate-site-initializer-zips.sh --deploy /opt/liferay/osgi/client-extensions
	```

Deploy the generated zips instead of `liferay-forums-site-initializer.zip`, unless the `FORUMS` site is wanted as well.

The script takes these options:

| Option | Purpose |
| --- | --- |
| `--base <zip>` | The site initializer zip to copy. Defaults to `liferay-forums-site-initializer.zip` next to the script. |
| `--deploy <dir>` | Also copies each generated zip into this directory. |
| `--output-dir <dir>` | Where the generated zips are written. Defaults to `out`. |

Each external reference code must produce a different file name, or the script stops before writing any zip whose name would collide with another.

## On Liferay DXP 2026.Q1

Liferay DXP 2026.Q1 has two problems that affect the forum's site initializer:

| Problem | Fix |
| --- | --- |
| The forum's roles are created without their external reference codes, which are only set when a site initializer runs again. Until then, moderators do not see **Lock Topic**, and bans are not enforced. | LPD-112124 |
| Every time a site initializer runs again on an existing site, the site's friendly URL is reset to `/group-<site ID>`, and the forum's links return a not found page. | LPD-85594, included from Liferay DXP 2026.Q1.12 |

On Liferay DXP 2026.Q1.4, install hotfix 84 before deploying the zips. It contains both fixes, so neither workaround below is needed:

```
https://hotfixes.liferay.com/2026.q1.4-lts/liferay-dxp-2026.q1.4-lts-hotfix-84.zip
```

On other 2026.Q1 releases without these fixes:

1. After the first site is created, deploy one site initializer zip a second time, as described in [Deployment Order](#deployment-order), so that the roles get their external reference codes. Creating a second site has the same effect, so this is only needed when a single site is created.

1. After every site initializer deployment that runs on an existing site, including the one above, check the site's friendly URL in *Site Settings* and set it back if it changed. The forum's pages expect the URL the site was created with, `/forums` for the `FORUMS` site.

Signed in as an administrator, check that this address returns the **Forum Banned** role rather than a not found error:

```
<liferay-url>/o/headless-admin-user/v1.0/roles/by-external-reference-code/FORUM_BANNED
```