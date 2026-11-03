# Changelog

All notable changes to Liferay Forums are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html), independent of the Liferay DXP version:

- **Patch** (1.0.x): bug fixes only, with no object, site, or API changes.
- **Minor** (1.x.0): backward-compatible features.
- **Major** (x.0.0): changes that need an upgrade step, such as removed or renamed object fields.

Every entry names the LPD issue of the change. See [Versions](README.md#versions) for the Liferay DXP version each release supports.

## [Unreleased]

### Changed

- Pin the microservice's dependencies to fixed versions, so every build of a commit is the same (LPD-113635).

### Fixed

- Title the unfiltered discussions list All Discussions, the name the delete-category dialog refers to (LPD-113631).
- Use the singular when a deleted category has one subcategory (LPD-113632).
- Say that deleting a category without subcategories keeps its topics (LPD-113634).
- Sort topics without a last post date by their creation date in Recent Activity, instead of above all other topics on PostgreSQL and Oracle. New topics get their creation date as the last post date, and the microservice fills it in for existing topics each time it starts (LPD-113650).
- Keep the From, From Name and To of the reply and mention email templates when `liferay-forums-batch.zip` is deployed again, instead of resetting them to the placeholder `forums-noreply@example.xyz`. The batch now only adds these two templates when they do not exist, and the README says to set their addresses. On an existing installation, check both templates after upgrading, since they keep whatever they hold today (LPD-113666).
- Link to topics through the site's friendly URL instead of its name, so that the page after posting a new topic, the related topics, and the Moderation page's links open the topic on a site whose friendly URL differs from its name, such as an existing site the forum was added to. The topic page's breadcrumb and back links also keep the site when the URL starts with a language, such as `/de` (LPD-113795).
