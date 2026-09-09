/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums;

import com.liferay.client.extension.util.spring.boot3.BaseRestController;
import com.liferay.client.extension.util.spring.boot3.client.LiferayOAuth2AccessTokenManager;
import com.liferay.forums.service.ForumModerationService;
import com.liferay.portal.kernel.util.GetterUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Roselaine Marques
 */
@RestController
public class ForumModerationRestController extends BaseRestController {

	@PostMapping("/object-action/handle-suspicious-activity-update")
	public ResponseEntity<String> onHandleSuspiciousActivityUpdate(
			@AuthenticationPrincipal Jwt jwt, @RequestBody String json)
		throws Exception {

		if (jwt != null) {
			log(jwt, _log, json);
		}
		else if (_log.isInfoEnabled()) {
			_log.info(json);
		}

		// Writes this microservice makes itself (the revert below, the
		// cascade) run as the service account and must never re-enter this
		// handler, or a revert and its own re-triggered onAfterUpdate
		// oscillate between the bad and reverted value forever.

		if (_isServiceAccountActor(jwt)) {
			return new ResponseEntity<>(json, HttpStatus.OK);
		}

		String authToken = _serviceAuthToken();

		String actorAuthToken = _actorAuthToken(jwt, authToken);
		long actorUserId = _resolveActorUserId(jwt);

		_forumNotificationExecutor.execute(
			() -> _fanOut(
				"handle-suspicious-activity-update",
				() -> _processHandleSuspiciousActivityUpdate(
					json, actorUserId, actorAuthToken, authToken)));

		return new ResponseEntity<>(json, HttpStatus.OK);
	}

	@PostMapping("/object-action/recreate-deleted-suspicious-activity")
	public ResponseEntity<String> onRecreateDeletedSuspiciousActivity(
			@AuthenticationPrincipal Jwt jwt, @RequestBody String json)
		throws Exception {

		if (jwt != null) {
			log(jwt, _log, json);
		}
		else if (_log.isInfoEnabled()) {
			_log.info(json);
		}

		if (_isServiceAccountActor(jwt)) {
			return new ResponseEntity<>(json, HttpStatus.OK);
		}

		String authToken = _serviceAuthToken();

		String actorAuthToken = _actorAuthToken(jwt, authToken);

		_forumNotificationExecutor.execute(
			() -> _fanOut(
				"recreate-deleted-suspicious-activity",
				() -> _processRecreateDeletedSuspiciousActivity(
					json, actorAuthToken, authToken)));

		return new ResponseEntity<>(json, HttpStatus.OK);
	}

	private String _actorAuthToken(Jwt jwt, String authToken) {
		if (jwt == null) {
			return authToken;
		}

		return jwt.getTokenValue();
	}

	private void _fanOut(String handler, Runnable task) {
		try {
			task.run();
		}
		catch (Throwable throwable) {
			_log.error(
				"Unhandled failure in " + handler + " fan-out", throwable);
		}
	}

	private boolean _isServiceAccountActor(Jwt jwt) {
		if (jwt == null) {
			return false;
		}

		return _SERVICE_ACCOUNT_USER_NAME.equals(
			jwt.getClaimAsString("username"));
	}

	private void _processHandleSuspiciousActivityUpdate(
		String json, long actorUserId, String actorAuthToken,
		String authToken) {

		JSONObject payloadJSONObject = new JSONObject(json);

		JSONObject objectEntryJSONObject = payloadJSONObject.optJSONObject(
			"objectEntry");

		JSONObject valuesJSONObject = (objectEntryJSONObject != null) ?
			objectEntryJSONObject.optJSONObject("values") : null;

		if (valuesJSONObject == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onHandleSuspiciousActivityUpdate: the payload carries " +
						"no entry values");
			}

			return;
		}

		long entryId = objectEntryJSONObject.optLong("id", 0L);

		if (actorUserId == _resolveCreatorUserId(objectEntryJSONObject)) {
			if (!_forumModerationService.canManageForumSuspiciousActivity(
					entryId, actorAuthToken, authToken)) {

				_revertSelfEdit(payloadJSONObject, authToken);

				return;
			}
		}

		String threadERC = valuesJSONObject.optString(
			"r_threadSuspiciousActivities_c_forumThreadERC", "");

		if (threadERC.isEmpty()) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onHandleSuspiciousActivityUpdate: missing " +
						"r_threadSuspiciousActivities_c_forumThreadERC in " +
							"payload");
			}

			return;
		}

		if (!valuesJSONObject.optBoolean("validated", false)) {
			return;
		}

		_forumModerationService.cascadeValidateSuspiciousActivities(
			threadERC, entryId, authToken);
	}

	private void _processRecreateDeletedSuspiciousActivity(
		String json, String actorAuthToken, String authToken) {

		JSONObject payloadJSONObject = new JSONObject(json);

		JSONObject objectEntryJSONObject = payloadJSONObject.optJSONObject(
			"objectEntry");

		JSONObject valuesJSONObject = (objectEntryJSONObject != null) ?
			objectEntryJSONObject.optJSONObject("values") : null;

		if (valuesJSONObject == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onRecreateDeletedSuspiciousActivity: the payload " +
						"carries no entry values");
			}

			return;
		}

		long entryId = payloadJSONObject.optLong("classPK", 0L);

		if (_forumModerationService.canManageForumSuspiciousActivity(
				entryId, actorAuthToken, authToken)) {

			return;
		}

		String threadERC = valuesJSONObject.optString(
			"r_threadSuspiciousActivities_c_forumThreadERC", "");

		if (threadERC.isEmpty()) {
			/* enters this block when parent thread is deleted */
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onRecreateDeletedSuspiciousActivity: missing " +
						"r_threadSuspiciousActivities_c_forumThreadERC in " +
							"payload");
			}

			return;
		}

		_forumModerationService.recreateForumSuspiciousActivity(
			valuesJSONObject.toString(), authToken);
	}

	private long _resolveActorUserId(Jwt jwt) {
		if (jwt == null) {
			return 0L;
		}

		return GetterUtil.getLong(jwt.getClaimAsString("sub"));
	}

	private long _resolveCreatorUserId(JSONObject objectEntryJSONObject) {
		JSONObject creatorJSONObject = objectEntryJSONObject.optJSONObject(
			"creator");

		if (creatorJSONObject != null) {
			return creatorJSONObject.optLong("id", 0L);
		}

		return 0L;
	}

	private void _revertSelfEdit(
		JSONObject payloadJSONObject, String authToken) {

		JSONObject originalObjectEntryJSONObject =
			payloadJSONObject.optJSONObject("originalObjectEntry");

		JSONObject originalValuesJSONObject =
			(originalObjectEntryJSONObject != null) ?
				originalObjectEntryJSONObject.optJSONObject("values") : null;

		if (originalValuesJSONObject == null) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"onHandleSuspiciousActivityUpdate: the payload carries " +
						"no original entry values to revert to");
			}

			return;
		}

		long entryId = payloadJSONObject.optLong("classPK", 0L);

		_forumModerationService.revertForumSuspiciousActivity(
			entryId, originalValuesJSONObject.toString(), authToken);
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
		ForumModerationRestController.class);

	@Autowired
	private ForumModerationService _forumModerationService;

	@Autowired
	@Qualifier("forumNotificationExecutor")
	private ThreadPoolTaskExecutor _forumNotificationExecutor;

	@Autowired
	private LiferayOAuth2AccessTokenManager _liferayOAuth2AccessTokenManager;

}