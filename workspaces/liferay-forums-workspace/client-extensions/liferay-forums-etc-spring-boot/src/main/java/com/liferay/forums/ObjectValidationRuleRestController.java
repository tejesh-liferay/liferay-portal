/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;
import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumModerationService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Roselaine Marques
 */
@RequestMapping("/object-validation-rule")
@RestController
public class ObjectValidationRuleRestController extends BaseRestController {

	@PostMapping("/ban")
	public ResponseEntity<String> ban(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		if (_isServiceAccountActor(jwt)) {
			return _respond(payloadJSONObject, true);
		}

		String authToken = _serviceAuthToken();

		long creatorUserId = _resolveCreatorUserId(payloadJSONObject);
		long siteId = _resolveSiteIdByThread(payloadJSONObject, authToken);

		boolean banned = _forumModerationService.isBanned(
			creatorUserId, siteId, authToken);

		if (banned && _log.isInfoEnabled()) {
			_log.info("Refused a post from banned user " + creatorUserId);
		}

		return _respond(payloadJSONObject, !banned);
	}

	@PostMapping("/locked")
	public ResponseEntity<String> locked(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		if (_isServiceAccountActor(jwt)) {
			return _respond(payloadJSONObject, true);
		}

		long threadId = payloadJSONObject.optLong(
			"r_threadMessages_c_c2m0ThreadId");

		boolean locked = _forumModerationService.isThreadLocked(
			threadId, _serviceAuthToken());

		if (locked && _log.isInfoEnabled()) {
			_log.info("Refused a message on locked thread " + threadId);
		}

		return _respond(payloadJSONObject, !locked);
	}

	@PostMapping("/priority")
	public ResponseEntity<String> priority(
		@AuthenticationPrincipal Jwt jwt, @RequestBody String json) {

		if (jwt != null) {
			log(jwt, _log, json);
		}

		JSONObject payloadJSONObject = new JSONObject(json);

		// Like "ban" and "locked", this re-runs on every write to a thread,
		// not just its creation. ForumThreadRestController bumps a thread's
		// lastPostDate on every message posted to it, as the service
		// account — without this skip, that write re-evaluates this
		// validation (and pays for a site resolution and a thread lookup)
		// on every single reply, for a priority check that has nothing to
		// do with lastPostDate.

		if (_isServiceAccountActor(jwt)) {
			return _respond(payloadJSONObject, true);
		}

		double priority = payloadJSONObject.optDouble("priority", 0);

		if (priority <= 0) {
			return _respond(payloadJSONObject, true);
		}

		String authToken = _serviceAuthToken();

		long siteId = _resolveSiteIdByThread(payloadJSONObject, authToken);

		if (_forumModerationService.isThreadPriorityUnchanged(
				payloadJSONObject.optString("externalReferenceCode"), priority,
				siteId, authToken)) {

			return _respond(payloadJSONObject, true);
		}

		long creatorUserId = _resolveCreatorUserId(payloadJSONObject);

		boolean allowed = _forumModerationService.canModerateForum(
			creatorUserId, authToken);

		if (!allowed && _log.isInfoEnabled()) {
			_log.info("Refused a thread priority set by user " + creatorUserId);
		}

		return _respond(payloadJSONObject, allowed);
	}

	private boolean _isServiceAccountActor(Jwt jwt) {
		if (jwt == null) {
			return false;
		}

		return _SERVICE_ACCOUNT_USER_NAME.equals(
			jwt.getClaimAsString("username"));
	}

	private long _resolveCreatorUserId(JSONObject payloadJSONObject) {
		JSONObject creatorJSONObject = payloadJSONObject.optJSONObject(
			"creator");

		if (creatorJSONObject != null) {
			return creatorJSONObject.optLong("id", 0L);
		}

		return 0L;
	}

	// "ban" runs on C2M0Message (has a parent thread relationship) and on
	// C2M0Thread itself; "priority" runs only on C2M0Thread. Prefer whatever
	// the payload already carries; only reach for the thread lookup (an API
	// call) when the payload has no groupId of its own. An existing thread
	// carries its own id, so both cases resolve through it; a brand new
	// thread's first post carries neither and this returns 0.

	private long _resolveSiteIdByThread(
		JSONObject payloadJSONObject, String authToken) {

		long siteId = payloadJSONObject.optLong("groupId", 0L);

		if (siteId > 0L) {
			return siteId;
		}

		long threadId = payloadJSONObject.optLong(
			"r_threadMessages_c_c2m0ThreadId", 0L);

		if (threadId <= 0L) {
			threadId = payloadJSONObject.optLong("id", 0L);
		}

		return _forumModerationService.resolveSiteIdByThreadId(
			threadId, authToken);
	}

	private ResponseEntity<String> _respond(
		JSONObject payloadJSONObject, boolean validationCriteriaMet) {

		payloadJSONObject.put("validationCriteriaMet", validationCriteriaMet);

		return new ResponseEntity<>(
			payloadJSONObject.toString(), HttpStatus.OK);
	}

	private String _serviceAuthToken() {
		return _liferayOAuth2AccessTokenManager.getTokenValue(
			_OAUTH_APPLICATION_HEADLESS_SERVER_ERC);
	}

	private static final String _OAUTH_APPLICATION_HEADLESS_SERVER_ERC =
		"liferay-forums-etc-spring-boot-oahs";

	private static final String _SERVICE_ACCOUNT_USER_NAME =
		"default-service-account";

	private static final Log _log = LogFactory.getLog(
		ObjectValidationRuleRestController.class);

	@Autowired
	private ForumModerationService _forumModerationService;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}